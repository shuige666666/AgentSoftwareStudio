package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 根据结构化失败类型选择责任阶段，并在重复失败或环境错误时停止无效修复。
 */
@Service
public class FailureTriageService {

    /**
     * 生成本轮修复决策；同一失败连续出现两次时停止继续消耗 LLM 预算。
     */
    public RepairDecision decide(SoftwareStudioWorkflowData data) {
        FailureKind failureKind = data.pendingFailureKind == null
                ? FailureKind.UNKNOWN
                : data.pendingFailureKind;
        String fingerprint = fingerprint(data.currentSliceId(), failureKind, data.pendingFixLog);

        if (fingerprint.equals(data.lastFailureFingerprint)) {
            data.repeatedFailureCount++;
        } else {
            data.lastFailureFingerprint = fingerprint;
            data.repeatedFailureCount = 1;
        }

        if (data.repeatedFailureCount >= 2) {
            data.repairStopRequested = true;
            data.repeatedFailureStopCount++;
            data.markRepairStopped("REPEATED_FAILURE");
            return new RepairDecision(
                    RepairTarget.STOP, false, false, fingerprint,
                    "相同失败在修复后再次出现，停止重复调用。");
        }
        if (failureKind == FailureKind.ENVIRONMENT) {
            data.repairStopRequested = true;
            data.markRepairStopped("ENVIRONMENT");
            return new RepairDecision(
                    RepairTarget.ENVIRONMENT, false, false, fingerprint,
                    "环境错误不应通过修改生成源码修复。");
        }
        if (failureKind == FailureKind.ARCHITECTURE) {
            data.repairStopRequested = true;
            data.markRepairStopped("ARCHITECTURE");
            return new RepairDecision(
                    RepairTarget.ARCHITECTURE, false, false, fingerprint,
                    "架构错误需要重新规划，不能在最终修复阶段盲目改源码。");
        }
        if (!data.canRepairNow()) {
            data.repairStopRequested = true;
            if (data.repairBudget != null) {
                data.repairBudget.markExhausted(data.currentSliceId(), data.finalVerificationStarted);
            }
            data.markRepairStopped("BUDGET_EXHAUSTED");
            return new RepairDecision(
                    RepairTarget.STOP, false, false, fingerprint,
                    "全局或当前切片修复预算已经耗尽。");
        }

        RepairTarget target = switch (failureKind) {
            case BUILD_PROFILE -> RepairTarget.PROJECT_PROFILE;
            case TEST_COMPILE, TEST_DISCOVERY -> RepairTarget.TESTS;
            case TEST_CODE -> isImplementationOwnedTestRuntimeFailure(data)
                    ? RepairTarget.IMPLEMENTATION
                    : RepairTarget.TESTS;
            case CONTRACT -> RepairTarget.CONTRACT;
            case ARCHITECTURE -> RepairTarget.ARCHITECTURE;
            case TEST_ASSERTION -> isLikelyTestOwnedAssertion(data)
                    ? RepairTarget.TESTS
                    : RepairTarget.IMPLEMENTATION;
            case MAIN_COMPILE, SPRING_CONTEXT, IMPLEMENTATION, UNKNOWN -> RepairTarget.IMPLEMENTATION;
            case ENVIRONMENT -> RepairTarget.ENVIRONMENT;
            case NONE -> RepairTarget.STOP;
        };
        return new RepairDecision(target, target != RepairTarget.STOP, true, fingerprint,
                "失败已路由到 " + target + " 阶段。");
    }

    /**
     * 识别由生产 DTO、模型或序列化配置引起的测试运行错误，避免 TestWriter 重写正确测试。
     */
    private boolean isImplementationOwnedTestRuntimeFailure(SoftwareStudioWorkflowData data) {
        String failureLog = data == null || data.pendingFixLog == null ? "" : data.pendingFixLog;
        String log = failureLog.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        return log.contains("invaliddefinitionexception")
                && (log.contains("cannot construct instance") || log.contains("no creators"))
                || log.contains("mismatchedinputexception") && log.contains("through reference chain")
                || log.contains("jsonmappingexception") && log.contains("cannot deserialize");
    }

    /**
     * 识别测试提前引用未来资源或依赖固定顺序的断言，优先重写测试而不是扭曲生产实现。
     */
    private boolean isLikelyTestOwnedAssertion(SoftwareStudioWorkflowData data) {
        String failureLog = data == null ? "" : data.pendingFixLog;
        String log = failureLog == null ? "" : failureLog.toLowerCase(java.util.Locale.ROOT);
        // 模板解析失败说明生产 Controller 返回了不可解析的视图，不能因日志包含 templates/ 而改写测试掩盖问题。
        if (log.contains("templateinputexception")
                || log.contains("error resolving template")
                || log.contains("template might not exist")) {
            return false;
        }
        return hasUnhandledMockMvcStatusExpectation(data, log)
                || hasGeneratedIdAssumption(data)
                || log.contains("classpathresource")
                || log.contains("filenotfoundexception")
                || log.contains("should exist")
                || log.contains("frontendcontracttest")
                        && (log.contains("assertionfailederror") || log.contains("expected:"))
                || log.contains("static/index.html")
                || log.contains("static/app.js")
                || log.contains("templates/")
                || log.contains("json path \"$[0]")
                || log.contains("json path '$[0]");
    }

    /**
     * MockMvc 会重新抛出未映射的 Controller 异常；把它直接断言为 HTTP 500 属于测试假设错误。
     */
    private boolean hasUnhandledMockMvcStatusExpectation(SoftwareStudioWorkflowData data, String log) {
        if (data == null || data.codes == null || log == null
                || !log.contains("servletexception: request processing failed")) {
            return false;
        }
        return data.codes.stream()
                .filter(java.util.Objects::nonNull)
                .filter(code -> code.filename() != null
                        && code.filename().replace('\\', '/').startsWith("src/test/"))
                .map(com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .anyMatch(code -> code.contains("status().isInternalServerError()"));
    }

    /**
     * 识别集成测试对数据库自增主键的固定值假设，避免为脆弱测试扭曲生产实现。
     */
    private boolean hasGeneratedIdAssumption(SoftwareStudioWorkflowData data) {
        if (data == null || data.codes == null) {
            return false;
        }
        boolean generatedEntity = data.codes.stream()
                .filter(java.util.Objects::nonNull)
                .filter(code -> code.filename() != null && code.filename().replace('\\', '/').startsWith("src/main/"))
                .map(com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .anyMatch(code -> code.contains("@GeneratedValue"));
        if (!generatedEntity) {
            return false;
        }
        return data.codes.stream()
                .filter(java.util.Objects::nonNull)
                .filter(code -> code.filename() != null && code.filename().replace('\\', '/').startsWith("src/test/"))
                .map(com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .anyMatch(code -> code.contains("@SpringBootTest")
                        && code.contains("Repository")
                        && code.matches("(?s).*\\.setId\\(\\s*\\d+[lL]?\\s*\\).*"));
    }

    private String fingerprint(String sliceId, FailureKind failureKind, String log) {
        String normalizedLog = log == null ? "" : log
                .replaceAll("[A-Za-z]:[/\\\\][^\\s:]+", "<path>")
                .replaceAll("(?m):\\[?\\d+(?:,\\d+)?\\]?", ":<line>")
                .replaceAll("\\s+", " ")
                .trim();
        String input = (sliceId == null ? "UNSCOPED" : sliceId) + "|" + failureKind.name() + "|" + normalizedLog;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
