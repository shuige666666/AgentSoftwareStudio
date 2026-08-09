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
        String fingerprint = fingerprint(failureKind, data.pendingFixLog);

        if (fingerprint.equals(data.lastFailureFingerprint)) {
            data.repeatedFailureCount++;
        } else {
            data.lastFailureFingerprint = fingerprint;
            data.repeatedFailureCount = 1;
        }

        if (data.repeatedFailureCount >= 2) {
            data.repairStopRequested = true;
            data.repeatedFailureStopCount++;
            return new RepairDecision(
                    RepairTarget.STOP, false, false, fingerprint,
                    "相同失败在修复后再次出现，停止重复调用。");
        }
        if (failureKind == FailureKind.ENVIRONMENT) {
            data.repairStopRequested = true;
            return new RepairDecision(
                    RepairTarget.ENVIRONMENT, false, false, fingerprint,
                    "环境错误不应通过修改生成源码修复。");
        }
        if (failureKind == FailureKind.ARCHITECTURE) {
            data.repairStopRequested = true;
            return new RepairDecision(
                    RepairTarget.ARCHITECTURE, false, false, fingerprint,
                    "架构错误需要重新规划，不能在最终修复阶段盲目改源码。");
        }
        if (data.currentAttempt >= data.maxRetries) {
            data.repairStopRequested = true;
            return new RepairDecision(
                    RepairTarget.STOP, false, false, fingerprint,
                    "共享修复预算已经耗尽。");
        }

        RepairTarget target = switch (failureKind) {
            case BUILD_PROFILE -> RepairTarget.PROJECT_PROFILE;
            case TEST_COMPILE, TEST_DISCOVERY, TEST_CODE -> RepairTarget.TESTS;
            case CONTRACT -> RepairTarget.CONTRACT;
            case ARCHITECTURE -> RepairTarget.ARCHITECTURE;
            case MAIN_COMPILE, TEST_ASSERTION, SPRING_CONTEXT, IMPLEMENTATION, UNKNOWN -> RepairTarget.IMPLEMENTATION;
            case ENVIRONMENT -> RepairTarget.ENVIRONMENT;
            case NONE -> RepairTarget.STOP;
        };
        return new RepairDecision(target, target != RepairTarget.STOP, true, fingerprint,
                "失败已路由到 " + target + " 阶段。");
    }

    private String fingerprint(FailureKind failureKind, String log) {
        String normalizedLog = log == null ? "" : log
                .replaceAll("[A-Za-z]:[/\\\\][^\\s:]+", "<path>")
                .replaceAll("(?m):\\[?\\d+(?:,\\d+)?\\]?", ":<line>")
                .replaceAll("\\s+", " ")
                .trim();
        String input = failureKind.name() + "|" + normalizedLog;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
