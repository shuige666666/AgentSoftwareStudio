package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Repair.ToolDrivenRepairService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ArchitectureNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchPlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchValidationNodeService;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoftwareStudioWorkflowServiceTest {

    /**
     * 主流程必须先生成全部生产批次并通过真实编译，之后才允许生成和执行测试。
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
        verify(fixture.generation, times(2)).generateBatch(any(), any());
        verify(fixture.testGeneration, times(1)).execute(any(), any());
        verify(fixture.repair).compileAndRepair(any(), any());
        verify(fixture.repair).testAndRepair(any(), any());
    }

    /**
     * 完整生产项目无法修到可编译时必须停止，不能继续生成会掩盖根因的测试代码。
     */
    @Test
    void skipsTestGenerationWhenProductionCompileCannotBeRepaired() {
        Fixture fixture = fixture();
        when(fixture.repair.compileAndRepair(any(), any())).thenReturn(false);

        fixture.service.generateProjectWithResult("test", message -> { });

        verify(fixture.testGeneration, never()).execute(any(), any());
        verify(fixture.repair, never()).testAndRepair(any(), any());
    }

    private Fixture fixture() {
        RequirementNodeService requirement = passthrough(RequirementNodeService.class);
        ArchitectureNodeService architecture = passthrough(ArchitectureNodeService.class);
        ContractNodeService contract = passthrough(ContractNodeService.class);
        SlicePlanNodeService slicePlan = passthrough(SlicePlanNodeService.class);
        BatchPlanNodeService batchPlan = mock(BatchPlanNodeService.class);
        BatchGenerationService generation = mock(BatchGenerationService.class);
        BatchValidationNodeService batchValidation = mock(BatchValidationNodeService.class);
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
            data.currentBatchIndex = 0;
            return data;
        });
        doAnswer(invocation -> null).when(generation).generateBatch(any(), any());
        when(batchValidation.execute(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.currentBatchIndex++;
            return data;
        });
        when(persistence.initializeWorkspace(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.projectPath = "target/quality-first-workflow-test";
            return data;
        });
        when(persistence.persistWholeProject(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        SoftwareStudioWorkflowService service = new SoftwareStudioWorkflowService(
                requirement, architecture, contract, slicePlan, batchPlan, generation, batchValidation,
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
