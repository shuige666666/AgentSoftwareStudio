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
}
