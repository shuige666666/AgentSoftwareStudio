package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 在单个工具会话内识别没有新写入、没有新证据或反复得到相同验证失败的循环。
 */
final class WorkspaceAgentStagnationTracker {

    private final WorkspaceAgentLimitsConfig.StagnationPolicy policy;
    private final Set<String> observedEvidence = new LinkedHashSet<>();
    private int observedWriteVersion = -1;
    private String lastRequest = "";
    private int lastRequestWriteVersion = -1;
    private int identicalRequestCount;
    private int callsWithoutNewEvidence;
    private String lastFailedVerification = "";
    private int lastFailedVerificationWriteVersion = -1;
    private int repeatedVerificationFailuresAfterEdit;

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
        String evidence = normalize(call == null ? "" : call.name()) + "|" + normalizeResult(result);
        if (observedEvidence.add(evidence)) {
            callsWithoutNewEvidence = 0;
        } else {
            callsWithoutNewEvidence++;
        }
        if (callsWithoutNewEvidence >= policy.getMaxCallsWithoutNewEvidence()) {
            return Decision.stop("No new tool evidence was produced for " + callsWithoutNewEvidence
                    + " consecutive calls");
        }

        if (isVerification(call) && isFailedVerification(result)) {
            String failedVerification = normalizeResult(result);
            if (failedVerification.equals(lastFailedVerification)
                    && writeVersion != lastFailedVerificationWriteVersion) {
                repeatedVerificationFailuresAfterEdit++;
            } else if (!failedVerification.equals(lastFailedVerification)) {
                repeatedVerificationFailuresAfterEdit = 1;
            }
            lastFailedVerification = failedVerification;
            lastFailedVerificationWriteVersion = writeVersion;
            if (repeatedVerificationFailuresAfterEdit
                    >= policy.getMaxRepeatedVerificationFailuresAfterEdit()) {
                return Decision.stop("The same verification failure remained after "
                        + repeatedVerificationFailuresAfterEdit + " separate edit attempts");
            }
        } else if (isVerification(call)) {
            lastFailedVerification = "";
            lastFailedVerificationWriteVersion = -1;
            repeatedVerificationFailuresAfterEdit = 0;
        }
        return Decision.continueSession();
    }

    private boolean isVerification(ToolCall call) {
        String name = call == null ? "" : call.name();
        return "compile_main".equals(name) || "run_tests".equals(name) || "validate_contract".equals(name);
    }

    private boolean isFailedVerification(String result) {
        String normalized = normalize(result);
        if (normalized.contains("\"passed\":true") || normalized.contains("\"accepted\":true")) {
            return false;
        }
        return normalized.contains("\"passed\":false")
                || normalized.contains("\"accepted\":false")
                || normalized.matches(".*\"exitcode\":(?:-[0-9]+|[1-9][0-9]*).*")
                || normalized.contains("compilation failure")
                || normalized.contains("test failures");
    }

    private String normalizeResult(String value) {
        return normalize(value)
                .replaceAll("[a-z]:/[^\\s\\\"]+", "<path>")
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

    record Decision(boolean stop, String reason) {
        private static Decision stop(String reason) { return new Decision(true, reason); }
        private static Decision continueSession() { return new Decision(false, ""); }
    }
}
