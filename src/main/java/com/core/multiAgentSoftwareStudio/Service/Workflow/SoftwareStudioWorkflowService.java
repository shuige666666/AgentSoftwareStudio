package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.ARCHITECTURE;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.CONTRACT;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.GENERATION_PLAN;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.REQUIREMENT;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.SLICE_PLAN;

/**
 * 以最终产物质量为中心编排完整生成、真实编译修复、测试生成和测试修复流程。
 */
@Service
public class SoftwareStudioWorkflowService {

    private final RequirementNodeService requirementNodeService;
    private final ArchitectureNodeService architectureNodeService;
    private final ContractNodeService contractNodeService;
    private final SlicePlanNodeService slicePlanNodeService;
    private final BatchPlanNodeService batchPlanNodeService;
    private final BatchGenerationService batchGenerationService;
    private final FrontendReviewNodeService frontendReviewNodeService;
    private final PersistenceNodeService persistenceNodeService;
    private final TestGenerationNodeService testGenerationNodeService;
    private final ToolDrivenRepairService toolDrivenRepairService;
    private final LlmUsageMetricsService llmUsageMetricsService;
    private final RunJournalService runJournalService;

    public SoftwareStudioWorkflowService(
            RequirementNodeService requirementNodeService,
            ArchitectureNodeService architectureNodeService,
            ContractNodeService contractNodeService,
            SlicePlanNodeService slicePlanNodeService,
            BatchPlanNodeService batchPlanNodeService,
            BatchGenerationService batchGenerationService,
            FrontendReviewNodeService frontendReviewNodeService,
            PersistenceNodeService persistenceNodeService,
            TestGenerationNodeService testGenerationNodeService,
            ToolDrivenRepairService toolDrivenRepairService,
            LlmUsageMetricsService llmUsageMetricsService,
            RunJournalService runJournalService) {
        this.requirementNodeService = requirementNodeService;
        this.architectureNodeService = architectureNodeService;
        this.contractNodeService = contractNodeService;
        this.slicePlanNodeService = slicePlanNodeService;
        this.batchPlanNodeService = batchPlanNodeService;
        this.batchGenerationService = batchGenerationService;
        this.frontendReviewNodeService = frontendReviewNodeService;
        this.persistenceNodeService = persistenceNodeService;
        this.testGenerationNodeService = testGenerationNodeService;
        this.toolDrivenRepairService = toolDrivenRepairService;
        this.llmUsageMetricsService = llmUsageMetricsService;
        this.runJournalService = runJournalService;
    }

    public List<SourceCode> generateProject(String userRequest, Consumer<String> eventListener) {
        return generateProjectWithResult(userRequest, eventListener).codes();
    }

    /**
     * 生产代码完整生成后才开始真实编译和修复，避免在必然不完整的中间切片上浪费 LLM 调用。
     */
    public WorkflowExecutionResult generateProjectWithResult(
            String userRequest,
            Consumer<String> eventListener) {
        Consumer<String> logger = eventListener == null ? message -> { } : eventListener;
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData(userRequest);
        llmUsageMetricsService.beginTask();
        try {
            data = requirementNodeService.execute(data, logger);
            data = persistenceNodeService.initializeWorkspace(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, REQUIREMENT, logger);
            data = architectureNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, ARCHITECTURE, logger);
            data = contractNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, CONTRACT, logger);

            // 切片仅用于业务上下文、预算估算和报告，不再作为中间验收及修复边界。
            data = slicePlanNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, SLICE_PLAN, logger);
            // 编译、测试和契约共享同一份三加一额度，不能再按切片复制重试次数。
            data.repairBudget = RepairBudget.forToolDrivenProject();
            logger.accept("   Tool repair budget: " + data.repairBudget.maxLlmRepairs()
                    + " calls, with the fourth available only after verified progress.");
            data = batchPlanNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, GENERATION_PLAN, logger);

            // 生产代码共享同一真实工作区和工具会话，完整实现后再以编译结果验收。
            WorkspaceAgentRunResult development = batchGenerationService.generateProject(data, logger);
            data.finalVerificationStarted = true;

            // 即使开发工具会话没有主动 complete，只要保留了候选文件，就交给真实编译和修复接力。
            if (!data.codes.isEmpty() && toolDrivenRepairService.compileAndRepair(data, logger)) {
                data = frontendReviewNodeService.execute(data, logger);
                try {
                    data = testGenerationNodeService.execute(data, logger);
                } catch (RuntimeException testAuthorFailure) {
                    // 测试编写是增强和诊断阶段；即使 Agent 自身异常，也必须保留项目并让真实测试/修复接棒。
                    logger.accept("   Test author stopped unexpectedly; continuing with outer test/repair: "
                            + testAuthorFailure.getClass().getSimpleName() + ": "
                            + String.valueOf(testAuthorFailure.getMessage()));
                }
                toolDrivenRepairService.testAndRepair(data, logger);
            } else if (data.codes.isEmpty()) {
                logger.accept("Initial development produced no candidate files; skipping compile and repair handoff."
                        + " Stop reason=" + development.stopReason() + ".");
            }

            if (data.success && data.sliceDeliveryPlan != null) {
                data.acceptedSliceIds = data.sliceDeliveryPlan.slices().stream()
                        .map(slice -> slice.id()).distinct().toList();
                data.acceptedTestFiles = data.codes.stream()
                        .filter(code -> code != null && code.filename() != null)
                        .map(SourceCode::filename)
                        .filter(path -> path.replace('\\', '/').startsWith("src/test/"))
                        .distinct().toList();
            }
            if (!data.success) {
                int used = data.repairBudget == null ? 0 : data.repairBudget.usedLlmRepairs();
                int limit = data.repairBudget == null ? 0 : data.repairBudget.maxLlmRepairs();
                logger.accept("Project generation finished without a clean pass after "
                        + used + "/" + limit + " LLM repair calls.");
            }
            persistenceNodeService.persistRunSummary(data, runJournalService.summarize(data), logger);
            return result(data);
        } finally {
            logger.accept(llmUsageMetricsService.formatSummary());
        }
    }

    private WorkflowExecutionResult result(SoftwareStudioWorkflowData data) {
        return new WorkflowExecutionResult(
                List.copyOf(data.codes),
                data.success,
                data.projectPath,
                data.executionResult,
                data.testResult,
                List.copyOf(data.validationWarnings),
                data.pendingErrorType,
                data.currentAttempt,
                llmUsageMetricsService.snapshot(),
                data.qualityPolicyResult,
                data.verificationResult,
                runJournalService.summarize(data));
    }
}
