package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一配置各类工作区工具会话的模型轮次和工具调用上限；0 仍可显式表示不限制。
 */
@Component
@ConfigurationProperties(prefix = "studio.agent-runtime")
public class WorkspaceAgentLimitsConfig {

    private SessionLimit implement = new SessionLimit(24, 72);
    private SessionLimit test = new SessionLimit(12, 36);
    private SessionLimit frontendIntegration = new SessionLimit(8, 24);
    private SessionLimit repairImplementation = new SessionLimit(16, 48);
    private SessionLimit repairTest = new SessionLimit(16, 48);
    private SessionLimit repairContract = new SessionLimit(16, 48);
    private StagnationPolicy stagnation = new StagnationPolicy();
    private ContextPolicy context = new ContextPolicy();

    public SessionLimit forMode(WorkspaceAgentMode mode) {
        return switch (mode) {
            case IMPLEMENT -> implement;
            case TEST -> test;
            case FRONTEND_INTEGRATION -> frontendIntegration;
            case REPAIR_IMPLEMENTATION -> repairImplementation;
            case REPAIR_TEST -> repairTest;
            case REPAIR_CONTRACT -> repairContract;
        };
    }

    public SessionLimit getImplement() { return implement; }
    public void setImplement(SessionLimit implement) { if (implement != null) this.implement = implement; }
    public SessionLimit getTest() { return test; }
    public void setTest(SessionLimit test) { if (test != null) this.test = test; }
    public SessionLimit getFrontendIntegration() { return frontendIntegration; }
    public void setFrontendIntegration(SessionLimit frontendIntegration) { if (frontendIntegration != null) this.frontendIntegration = frontendIntegration; }
    public SessionLimit getRepairImplementation() { return repairImplementation; }
    public void setRepairImplementation(SessionLimit repairImplementation) { if (repairImplementation != null) this.repairImplementation = repairImplementation; }
    public SessionLimit getRepairTest() { return repairTest; }
    public void setRepairTest(SessionLimit repairTest) { if (repairTest != null) this.repairTest = repairTest; }
    public SessionLimit getRepairContract() { return repairContract; }
    public void setRepairContract(SessionLimit repairContract) { if (repairContract != null) this.repairContract = repairContract; }
    public StagnationPolicy getStagnation() { return stagnation; }
    public void setStagnation(StagnationPolicy stagnation) { if (stagnation != null) this.stagnation = stagnation; }
    public ContextPolicy getContext() { return context; }
    public void setContext(ContextPolicy context) { if (context != null) this.context = context; }

    public static class SessionLimit {
        private int maxModelTurns;
        private int maxToolCalls;

        public SessionLimit() {
        }

        private SessionLimit(int maxModelTurns, int maxToolCalls) {
            this.maxModelTurns = maxModelTurns;
            this.maxToolCalls = maxToolCalls;
        }

        public int getMaxModelTurns() { return maxModelTurns; }
        public void setMaxModelTurns(int maxModelTurns) { this.maxModelTurns = requireNonNegative(maxModelTurns); }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = requireNonNegative(maxToolCalls); }

        private int requireNonNegative(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Workspace Agent limits must be zero or positive");
            }
            return value;
        }
    }

    /**
     * 控制单个工具会话中的无效重复，阈值只负责提前交接候选，不直接判定候选代码失败。
     */
    public static class StagnationPolicy {
        private int maxIdenticalToolCalls = 3;
        private int maxCallsWithoutNewEvidence = 8;
        private int maxRepeatedVerificationFailuresAfterEdit = 2;

        public int getMaxIdenticalToolCalls() { return maxIdenticalToolCalls; }
        public void setMaxIdenticalToolCalls(int value) { this.maxIdenticalToolCalls = requirePositive(value); }
        public int getMaxCallsWithoutNewEvidence() { return maxCallsWithoutNewEvidence; }
        public void setMaxCallsWithoutNewEvidence(int value) { this.maxCallsWithoutNewEvidence = requirePositive(value); }
        public int getMaxRepeatedVerificationFailuresAfterEdit() { return maxRepeatedVerificationFailuresAfterEdit; }
        public void setMaxRepeatedVerificationFailuresAfterEdit(int value) {
            this.maxRepeatedVerificationFailuresAfterEdit = requirePositive(value);
        }
    }

    /**
     * 控制发送给模型的历史长度，保留原始目标、最近工具证据和最近若干完整模型轮次。
     */
    public static class ContextPolicy {
        private int compactAfterModelTurns = 6;
        private int retainedModelTurns = 3;
        private int maxLatestToolResultChars = 6_000;

        public int getCompactAfterModelTurns() { return compactAfterModelTurns; }
        public void setCompactAfterModelTurns(int value) { this.compactAfterModelTurns = requirePositive(value); }
        public int getRetainedModelTurns() { return retainedModelTurns; }
        public void setRetainedModelTurns(int value) { this.retainedModelTurns = requirePositive(value); }
        public int getMaxLatestToolResultChars() { return maxLatestToolResultChars; }
        public void setMaxLatestToolResultChars(int value) { this.maxLatestToolResultChars = requirePositive(value); }
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Workspace Agent policy values must be positive");
        }
        return value;
    }
}
