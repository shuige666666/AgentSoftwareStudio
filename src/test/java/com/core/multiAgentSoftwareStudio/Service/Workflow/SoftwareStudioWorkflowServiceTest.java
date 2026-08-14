package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentStopReason;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Repair.ToolDrivenRepairService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ArchitectureNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchPlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ContractNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.FrontendReviewNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.RequirementNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.TestGenerationNodeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoftwareStudioWorkflowServiceTest {

    /**
     * 主流程必须先形成生产候选并通过真实编译，之后才允许生成和执行测试。
     */
    @Test
    void compilesCompleteProductionProjectBeforeGeneratingTests() {
        Fixture fixture = fixture();
        when(fixture.repair.compileAndRepair(any(), any())).thenReturn(true);
        when(fixture.repair.testAndRepair(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.success = true;
            return true;
        });

        var result = fixture.service.generateProjectWithResult("test", message -> { });

        assertTrue(result.platformSuccess());
        verify(fixture.generation, times(1)).generateProject(any(), any());
        verify(fixture.testGeneration, times(1)).execute(any(), any());
        verify(fixture.repair).compileAndRepair(any(), any());
        verify(fixture.repair).testAndRepair(any(), any());
    }

    /**
     * 生产候选无法修到可编译时必须停止，不能继续生成会掩盖根因的测试代码。
     */
    @Test
    void skipsTestGenerationWhenProductionCompileCannotBeRepaired() {
        Fixture fixture = fixture();
        when(fixture.repair.compileAndRepair(any(), any())).thenReturn(false);

        fixture.service.generateProjectWithResult("test", message -> { });

        verify(fixture.testGeneration, never()).execute(any(), any());
        verify(fixture.repair, never()).testAndRepair(any(), any());
    }

    /**
     * 开发工具会话未主动完成时，只要候选文件存在，就必须交给外层编译与修复继续处理。
     */
    @Test
    void handsIncompleteDevelopmentCandidateToOuterRepair() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.codes.add(new SourceCode("pom.xml", "xml", "<project/>"));
            return new WorkspaceAgentRunResult(
                    false, WorkspaceAgentStopReason.REPORTED_BLOCKER, 10, 18,
                    List.of("pom.xml"), "compile_main", "{\"passed\":false}", "handoff");
        }).when(fixture.generation).generateProject(any(), any());
        when(fixture.repair.compileAndRepair(any(), any())).thenReturn(true);
        when(fixture.repair.testAndRepair(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.success = true;
            return true;
        });

        var result = fixture.service.generateProjectWithResult("test", message -> { });

        assertTrue(result.platformSuccess());
        verify(fixture.repair).compileAndRepair(any(), any());
        verify(fixture.testGeneration).execute(any(), any());
    }

    @Test
    void testAuthorExceptionStillHandsProjectToOuterTestRepair() {
        Fixture fixture = fixture();
        when(fixture.repair.compileAndRepair(any(), any())).thenReturn(true);
        when(fixture.testGeneration.execute(any(), any()))
                .thenThrow(new IllegalStateException("test author stopped"));
        when(fixture.repair.testAndRepair(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.success = false;
            data.testResult = "Tests run: 1, Failures: 0, Errors: 1, Skipped: 0";
            return false;
        });

        var result = fixture.service.generateProjectWithResult("test", message -> { });

        verify(fixture.repair).testAndRepair(any(), any());
        assertTrue(result.projectPath().contains("quality-first-workflow-test"));
        assertTrue(result.testResult().contains("Errors: 1"));
    }

    private Fixture fixture() {
        RequirementNodeService requirement = passthrough(RequirementNodeService.class);
        ArchitectureNodeService architecture = passthrough(ArchitectureNodeService.class);
        ContractNodeService contract = passthrough(ContractNodeService.class);
        SlicePlanNodeService slicePlan = passthrough(SlicePlanNodeService.class);
        BatchPlanNodeService batchPlan = mock(BatchPlanNodeService.class);
        BatchGenerationService generation = mock(BatchGenerationService.class);
        FrontendReviewNodeService frontend = passthrough(FrontendReviewNodeService.class);
        PersistenceNodeService persistence = mock(PersistenceNodeService.class);
        TestGenerationNodeService testGeneration = passthrough(TestGenerationNodeService.class);
        ToolDrivenRepairService repair = mock(ToolDrivenRepairService.class);
        LlmUsageMetricsService metrics = mock(LlmUsageMetricsService.class);
        RunJournalService journal = new RunJournalService();

        when(batchPlan.execute(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.generationPlan = new GenerationPlan(List.of(
                    new GenerationBatch("base", "base", List.of()),
                    new GenerationBatch("api", "controller", List.of())));
            return data;
        });
        when(persistence.initializeWorkspace(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.projectPath = "target/quality-first-workflow-test";
            return data;
        });
        when(generation.generateProject(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.codes.add(new SourceCode("pom.xml", "xml", "<project/>"));
            return new WorkspaceAgentRunResult(
                    true, WorkspaceAgentStopReason.COMPLETED, 2, 4,
                    List.of("pom.xml"), "complete_stage", "{\"accepted\":true}", "done");
        });

        SoftwareStudioWorkflowService service = new SoftwareStudioWorkflowService(
                requirement, architecture, contract, slicePlan, batchPlan, generation,
                frontend, persistence, testGeneration, repair, metrics, journal);
        return new Fixture(service, generation, testGeneration, repair);
    }

    private <T> T passthrough(Class<T> type) {
        return mock(type, invocation -> invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof SoftwareStudioWorkflowData data ? data : null);
    }

    private record Fixture(
            SoftwareStudioWorkflowService service,
            BatchGenerationService generation,
            TestGenerationNodeService testGeneration,
            ToolDrivenRepairService repair) {
    }
}
