package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceRepairServiceTest {

    @Test
    void routesTestOwnedFailureToTestOnlyToolSessionAndCapturesBaseline() {
        FailureTriageService triage = mock(FailureTriageService.class);
        WorkspaceAgentRuntime runtime = mock(WorkspaceAgentRuntime.class);
        RunJournalService journal = mock(RunJournalService.class);
        WorkspaceRepairService service = new WorkspaceRepairService(triage, runtime, journal);
        SoftwareStudioWorkflowData data = repairData(FailureKind.TEST_COMPILE);
        when(triage.decide(data)).thenReturn(new RepairDecision(
                RepairTarget.TESTS, true, true, "fingerprint", "test-owned"));
        when(runtime.run(eq(data), eq(WorkspaceAgentMode.REPAIR_TEST), any(), any(), any()))
                .thenAnswer(invocation -> {
                    data.repairCandidateChangedFiles = List.of("src/test/java/demo/AppTest.java");
                    return true;
                });

        service.repair(data, message -> { });

        assertEquals(1, data.repairBudget.usedLlmRepairs());
        assertTrue(data.repairBaselineCodes.containsKey("src/main/java/demo/App.java"));
        assertEquals(List.of("src/test/java/demo/AppTest.java"), data.repairCandidateChangedFiles);
        verify(runtime).run(eq(data), eq(WorkspaceAgentMode.REPAIR_TEST), any(), any(), any());
    }

    @Test
    void incompleteToolSessionStopsWithoutLeavingRollbackSnapshot() {
        FailureTriageService triage = mock(FailureTriageService.class);
        WorkspaceAgentRuntime runtime = mock(WorkspaceAgentRuntime.class);
        WorkspaceRepairService service = new WorkspaceRepairService(
                triage, runtime, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = repairData(FailureKind.MAIN_COMPILE);
        when(triage.decide(data)).thenReturn(new RepairDecision(
                RepairTarget.IMPLEMENTATION, true, true, "fingerprint", "implementation-owned"));
        when(runtime.run(eq(data), eq(WorkspaceAgentMode.REPAIR_IMPLEMENTATION), any(), any(), any()))
                .thenReturn(false);

        service.repair(data, message -> { });

        assertTrue(data.repairStopRequested);
        assertFalse(data.shouldFix);
        assertTrue(data.repairBaselineCodes.isEmpty());
        assertEquals("TOOL_SESSION_INCOMPLETE", data.repairStopReason);
    }

    private SoftwareStudioWorkflowData repairData(FailureKind kind) {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.codes.add(new SourceCode("src/main/java/demo/App.java", "java", "class App {}"));
        data.projectPath = "target/workspace-repair-test";
        data.repairBudget = RepairBudget.forToolDrivenProject();
        data.pendingFailureKind = kind;
        data.pendingErrorType = kind.name();
        data.pendingFixLog = "real failure";
        data.shouldFix = true;
        data.finalVerificationStarted = true;
        return data;
    }
}
