package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在单个工具会话内识别没有新写入、没有新证据或反复得到相同验证失败的循环。
 */
final class WorkspaceAgentStagnationTracker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MAVEN_COMMAND = Pattern.compile(
            "(?:^|[;&|])\\s*(?:mvn|mvnw|\\./mvnw)\\s+([^;&|\\r\\n]+)", Pattern.MULTILINE);
    private static final Pattern TEST_GOAL = Pattern.compile("(?:^|\\s)(?:test|verify|package)(?:\\s|$)");
    private static final Pattern BUILD_GOAL = Pattern.compile("(?:^|\\s)(?:compile|test-compile|package|verify)(?:\\s|$)");
    private static final Pattern SKIP_TESTS = Pattern.compile("-D(?:skipTests|maven.test.skip)(?:=true)?(?:\\s|$)");
    private static final Pattern TEST_SELECTOR = Pattern.compile("-Dtest=([^\\s]+)");
    private static final Pattern GREEN_TESTS = Pattern.compile(
            "Tests run:\\s*[1-9]\\d*,\\s*Failures:\\s*0,\\s*Errors:\\s*0");

    private final WorkspaceAgentLimitsConfig.StagnationPolicy policy;
    private final Set<String> observedEvidence = new LinkedHashSet<>();
    private int observedWriteVersion = -1;
    private String lastRequest = "";
    private int lastRequestWriteVersion = -1;
    private int identicalRequestCount;
    private int callsWithoutNewEvidence;
    private String lastFailedVerification = "";
    private String lastFailedVerificationKind = "";
    private int lastFailedVerificationWriteVersion = -1;
    private int repeatedVerificationFailuresAfterEdit;
    private boolean failureAdviceSent;

    WorkspaceAgentStagnationTracker(WorkspaceAgentLimitsConfig.StagnationPolicy policy) {
        this.policy = policy;
    }

    /**
     * 观察一次真实工具结果；命中阈值时只结束当前会话，磁盘候选仍由外层验证决定去留。
     */
    Decision observe(ToolCall call, String result, WorkspaceAgentSession session) {
        int writeVersion = session.writeVersion();
        String request = normalize(call == null ? "" : call.name()) + "|"
                + normalize(call == null ? "" : call.arguments());
        if (request.equals(lastRequest) && writeVersion == lastRequestWriteVersion) {
            identicalRequestCount++;
        } else {
            lastRequest = request;
            lastRequestWriteVersion = writeVersion;
            identicalRequestCount = 1;
        }
        if (identicalRequestCount >= policy.getMaxIdenticalToolCalls()) {
            return Decision.stop("Repeated the same tool request " + identicalRequestCount
                    + " times without changing the workspace");
        }

        if (writeVersion != observedWriteVersion) {
            observedWriteVersion = writeVersion;
            observedEvidence.clear();
            callsWithoutNewEvidence = 0;
        }
        VerificationEvidence verification = verificationEvidence(call, result);
        // 日志路径和运行时长不是新进展，验证只比较检查范围、状态和错误摘要。
        String evidence = verification == null
                ? normalize(call == null ? "" : call.name()) + "|" + normalizeResult(result)
                : verification.kind() + "|" + verification.failed() + "|" + verification.failure();
        if (observedEvidence.add(evidence)) {
            callsWithoutNewEvidence = 0;
        } else {
            callsWithoutNewEvidence++;
        }
        if (callsWithoutNewEvidence >= policy.getMaxCallsWithoutNewEvidence()) {
            return Decision.stop("No new tool evidence was produced for " + callsWithoutNewEvidence
                    + " consecutive calls");
        }

        if (verification != null && verification.failed() && !verification.failure().isBlank()) {
            String failedVerification = verification.kind() + "|" + verification.failure();
            if (failedVerification.equals(lastFailedVerification)
                    && writeVersion != lastFailedVerificationWriteVersion) {
                repeatedVerificationFailuresAfterEdit++;
            } else if (!failedVerification.equals(lastFailedVerification)) {
                repeatedVerificationFailuresAfterEdit = 1;
                failureAdviceSent = false;
            }
            lastFailedVerification = failedVerification;
            lastFailedVerificationKind = verification.kind();
            lastFailedVerificationWriteVersion = writeVersion;
            int limit = policy.getMaxRepeatedVerificationFailuresAfterEdit();
            if (session.mode() == WorkspaceAgentMode.DEVELOPMENT
                    && repeatedVerificationFailuresAfterEdit >= limit && !failureAdviceSent) {
                // 新开发协议先给一次诊断机会，避免直接继承旧工具的机械两轮停止。
                failureAdviceSent = true;
                return Decision.advise("The same verification failure survived " + limit
                        + " edited versions. Recheck the failing test setup, declared constraints and production code "
                        + "before another patch. A failed setup is not evidence that every test body failed. "
                        + "Use current files and the error summary, make one coherent fix, then rerun verification. "
                        + "Do not weaken valid assertions or change unrelated code. Failure: " + verification.failure());
            }
            if (repeatedVerificationFailuresAfterEdit >= limit
                    && (session.mode() != WorkspaceAgentMode.DEVELOPMENT
                    || repeatedVerificationFailuresAfterEdit > limit)) {
                return Decision.stop("The same verification failure remained after "
                        + repeatedVerificationFailuresAfterEdit + " separate edit attempts");
            }
        } else if (verification != null && verification.passed()
                && verification.kind().equals(lastFailedVerificationKind)) {
            // 编译成功和定向测试成功不能清除尚未解决的全量测试失败。
            lastFailedVerification = "";
            lastFailedVerificationKind = "";
            lastFailedVerificationWriteVersion = -1;
            repeatedVerificationFailuresAfterEdit = 0;
            failureAdviceSent = false;
        }
        return Decision.continueSession();
    }

    /** 读取新旧工具的验证结果；参数拒绝、普通 Shell 诊断及环境阻塞不计为重复源码失败。 */
    private VerificationEvidence verificationEvidence(ToolCall call, String result) {
        if (call == null) return null;
        try {
            JsonNode node = JSON.readTree(result);
            if (node == null) return null;
            String kind;
            boolean failed;
            boolean passed;
            String output;
            if ("submit_checkpoint".equals(call.name())) {
                String status = node.path("status").asText();
                if (!"PASSED".equals(status) && !"FAILED".equals(status)) return null;
                failed = "FAILED".equals(status);
                passed = "PASSED".equals(status);
                kind = !"PASSED".equals(node.path("build").path("status").asText()) ? "build"
                        : !"PASSED".equals(node.path("test").path("status").asText()) ? "test" : "contract";
                if (passed) kind = lastFailedVerificationKind.isBlank() ? "checkpoint" : lastFailedVerificationKind;
                output = node.path("failureOutput").asText();
            } else {
                kind = switch (call.name()) {
                    case "compile_main" -> "build";
                    case "run_tests" -> "test";
                    case "validate_contract" -> "contract";
                    case "bash" -> mavenKind(JSON.readTree(call.arguments()).path("command").asText());
                    default -> "";
                };
                if (kind.isBlank()) return null;
                output = node.path("output").asText();
                if (node.path("timedOut").asBoolean() || !node.path("infrastructureError").asText("").isBlank()) return null;
                // Bash 的 126/127 表示工具不可执行或未找到，不是 Maven 验证后的源码失败。
                if ("bash".equals(call.name()) && node.hasNonNull("exitCode")
                        && (node.path("exitCode").asInt() == 126 || node.path("exitCode").asInt() == 127)) return null;
                failed = node.has("passed") && !node.path("passed").asBoolean()
                        || node.has("accepted") && !node.path("accepted").asBoolean()
                        || node.hasNonNull("exitCode") && node.path("exitCode").asInt() != 0;
                passed = !failed && (node.path("passed").asBoolean() || node.path("accepted").asBoolean());
                if ("bash".equals(call.name()) && kind.startsWith("test")
                        && !GREEN_TESTS.matcher(output).find()) passed = false;
                if ("run_tests".equals(call.name())) {
                    JsonNode selectors = JSON.readTree(call.arguments()).path("selectors");
                    if (selectors.isArray() && !selectors.isEmpty()) kind += ":" + selectors;
                }
                if (node.hasNonNull("failureHighlights")) output = node.path("failureHighlights").asText();
                if ("contract".equals(kind) && output.isBlank()) output = node.toString();
            }
            // 部分 Maven 线程耗尽被归为测试失败；两类工具都不能据此判定源码修复停滞。
            if (output.contains("unable to create native thread")) return null;
            return new VerificationEvidence(kind, failed, passed, failed && !output.isBlank()
                    ? normalizeResult(WorkspaceToolRegistry.failureHighlights(output)) : "");
        } catch (Exception ignored) {
            // 不完整协议结果交回模型处理，不能凭解析失败提前终止开发。
            return null;
        }
    }

    /** 识别真正执行的 Maven 编译、测试命令，保留定向测试的独立范围。 */
    private String mavenKind(String command) {
        var match = MAVEN_COMMAND.matcher(command);
        if (!match.find()) return "";
        String args = match.group(1);
        if (TEST_GOAL.matcher(args).find() && !SKIP_TESTS.matcher(args).find()) {
            var selectors = TEST_SELECTOR.matcher(args);
            return selectors.find() ? "test:" + selectors.group(1) : "test";
        }
        return BUILD_GOAL.matcher(args).find() ? "build" : "";
    }

    private String normalizeResult(String value) {
        return normalize(value)
                .replaceAll("[a-z]:/[^\\s\\\"]+", "<path>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}[t ][0-9:.]+(?:z|[+-][0-9:]+)?", "<timestamp>")
                .replaceAll("(?:time elapsed|durationmillis|elapsedmillis)\\\"?\\s*[:=]\\s*[0-9.]+", "time=<n>")
                .replaceAll("(?<=\\.java):\\d+|(?<=\\.java:)\\[\\d+,\\d+\\]", "<line>")
                .replaceAll("([a-z_$][\\w$]*\\.[\\w$]+):\\d+", "$1:<line>")
                .replaceAll("(?:total\\s+time|duration|elapsed|time)(?:millis|ms)?\\s*[:=]\\s*[0-9.]+(?:\\s*[a-z]+)?", "time=<n>")
                .replaceAll("finished at[:=]?\\s*[^\\s\\\"]+", "finished-at=<time>")
                .replaceAll("line\\s+\\d+", "line <n>");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record VerificationEvidence(String kind, boolean failed, boolean passed, String failure) { }

    record Decision(boolean stop, String reason, String guidance) {
        private static Decision stop(String reason) { return new Decision(true, reason, ""); }
        private static Decision continueSession() { return new Decision(false, "", ""); }
        private static Decision advise(String guidance) { return new Decision(false, "", guidance); }
    }
}
