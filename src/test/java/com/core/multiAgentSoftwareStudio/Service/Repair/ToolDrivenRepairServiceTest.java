package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolDrivenRepairServiceTest {

    private DockerSandboxService sandboxService;
    private ProjectProfileService profileService;
    private PersistenceNodeService persistenceNodeService;
    private ProjectRepairService projectRepairService;
    private RepairRegressionGuardService regressionGuardService;
    private ToolDrivenRepairService service;

    @BeforeEach
    void setUp() {
        sandboxService = mock(DockerSandboxService.class);
        profileService = mock(ProjectProfileService.class);
        persistenceNodeService = mock(PersistenceNodeService.class);
        projectRepairService = mock(ProjectRepairService.class);
        regressionGuardService = mock(RepairRegressionGuardService.class);
        RunJournalService journalService = mock(RunJournalService.class);

        service = new ToolDrivenRepairService(
                sandboxService,
                new VerificationResultService(),
                profileService,
                persistenceNodeService,
                projectRepairService,
                new RepairProgressEvaluator(),
                regressionGuardService,
                journalService);

        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), anyList())).thenReturn(List.of());
        when(profileService.evaluate(any(), anyList(), any(), anyList()))
                .thenReturn(ProjectQualityPolicyResult.empty());
        when(persistenceNodeService.persistWholeProject(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void compilePassesWithoutCallingRepairAgent() {
        SoftwareStudioWorkflowData data = data();
        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(success("mvn -DskipTests compile", "BUILD SUCCESS"));

        assertTrue(service.compileAndRepair(data, message -> { }));

        verify(projectRepairService, never()).repair(any(), any());
    }

    @Test
    void thirdVerifiedProgressOpensOnlyOneFourthRound() {
        SoftwareStudioWorkflowData data = data();
        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(failedCompile(4), failedCompile(3), failedCompile(2), failedCompile(1),
                        success("mvn -DskipTests compile", "BUILD SUCCESS"));
        doAnswer(call -> {
            SoftwareStudioWorkflowData current = call.getArgument(0);
            current.consumeRepairBudget();
            current.repairCandidateChangedFiles = List.of("src/main/java/com/example/App.java");
            return null;
        }).when(projectRepairService).repair(any(), any());

        assertTrue(service.compileAndRepair(data, message -> { }));

        verify(projectRepairService, times(4)).repair(any(), any());
        verify(regressionGuardService, times(4)).commitCandidate(data);
    }

    @Test
    void sameFailureCountStopsAndRollsBackImmediately() {
        SoftwareStudioWorkflowData data = data();
        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(failedCompile(2), failedCompile(2));
        doAnswer(call -> {
            SoftwareStudioWorkflowData current = call.getArgument(0);
            current.consumeRepairBudget();
            current.repairCandidateChangedFiles = List.of("src/main/java/com/example/App.java");
            return null;
        }).when(projectRepairService).repair(any(), any());

        assertFalse(service.compileAndRepair(data, message -> { }));

        verify(projectRepairService, times(1)).repair(any(), any());
        verify(regressionGuardService).rollbackCandidateWithoutProgress(any(), any(), any());
        assertTrue(data.repairStopRequested);
    }

    @Test
    void testsAndContractMustBothPassForFinalSuccess() {
        SoftwareStudioWorkflowData data = data();
        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(success("mvn -DskipTests compile", "BUILD SUCCESS"));
        when(sandboxService.runTestsInSandboxWithResult(any(), any(), anyList()))
                .thenReturn(success("mvn test", "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0"));

        assertTrue(service.testAndRepair(data, message -> { }));
        assertTrue(data.success);
    }

    private SoftwareStudioWorkflowData data() {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of());
        data.projectPath = ".";
        data.repairBudget = RepairBudget.forToolDrivenProject();
        return data;
    }

    private SandboxExecutionResult failedCompile(int problems) {
        StringBuilder output = new StringBuilder("[ERROR] COMPILATION ERROR\n");
        for (int i = 0; i < problems; i++) {
            output.append("[ERROR] /app/src/main/java/A")
                    .append(i).append(".java:[1,1] cannot find symbol\n");
        }
        return new SandboxExecutionResult(
                "mvn -DskipTests compile", 1, output.toString(), 100, false, null);
    }

    private SandboxExecutionResult success(String command, String output) {
        return new SandboxExecutionResult(command, 0, output, 100, false, null);
    }
}
