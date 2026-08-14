package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceAgentLimitsConfigTest {

    @Test
    void everySessionModeHasASafeDefaultLimit() {
        WorkspaceAgentLimitsConfig config = new WorkspaceAgentLimitsConfig();

        assertLimit(config, WorkspaceAgentMode.IMPLEMENT, 24, 72);
        assertLimit(config, WorkspaceAgentMode.TEST, 12, 36);
        assertLimit(config, WorkspaceAgentMode.FRONTEND_INTEGRATION, 8, 24);
        assertLimit(config, WorkspaceAgentMode.REPAIR_IMPLEMENTATION, 16, 48);
        assertLimit(config, WorkspaceAgentMode.REPAIR_TEST, 16, 48);
        assertLimit(config, WorkspaceAgentMode.REPAIR_CONTRACT, 16, 48);
    }

    @Test
    void rejectsNegativeLimitsBecauseZeroAlreadyMeansUnlimited() {
        WorkspaceAgentLimitsConfig.SessionLimit limit = new WorkspaceAgentLimitsConfig.SessionLimit();

        assertThrows(IllegalArgumentException.class, () -> limit.setMaxModelTurns(-1));
        assertThrows(IllegalArgumentException.class, () -> limit.setMaxToolCalls(-1));
    }

    private void assertLimit(
            WorkspaceAgentLimitsConfig config,
            WorkspaceAgentMode mode,
            int modelTurns,
            int toolCalls) {
        assertEquals(modelTurns, config.forMode(mode).getMaxModelTurns());
        assertEquals(toolCalls, config.forMode(mode).getMaxToolCalls());
    }
}
