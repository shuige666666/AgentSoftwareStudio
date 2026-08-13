package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

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
    private final BatchValidationNodeService batchValidationNodeService;
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
            BatchValidationNodeService batchValidationNodeService,
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
        this.batchValidationNodeService = batchValidationNodeService;
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
            data = architectureNodeService.execute(data, logger);
            data = contractNodeService.execute(data, logger);

            // 切片仅用于业务上下文、预算估算和报告，不再作为中间验收及修复边界。
            data = slicePlanNodeService.execute(data, logger);
            // 编译、测试和契约共享同一份三加一额度，不能再按切片复制重试次数。
            data.repairBudget = RepairBudget.forToolDrivenProject();
            data = batchPlanNodeService.execute(data, logger);
            data = persistenceNodeService.initializeWorkspace(data, logger);

            while (data.hasMoreBatches()) {
                batchGenerationService.generateBatch(data, logger);
                data = batchValidationNodeService.execute(data, logger);
            }

            // 所有生产文件都已存在后再做一次全项目前端一致性审查。
            data.finalVerificationStarted = true;
            data = frontendReviewNodeService.execute(data, logger);
            data = persistenceNodeService.persistWholeProject(data, logger);

            if (toolDrivenRepairService.compileAndRepair(data, logger)) {
                data = testGenerationNodeService.execute(data, logger);
                data = persistenceNodeService.persistWholeProject(data, logger);
                toolDrivenRepairService.testAndRepair(data, logger);
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
