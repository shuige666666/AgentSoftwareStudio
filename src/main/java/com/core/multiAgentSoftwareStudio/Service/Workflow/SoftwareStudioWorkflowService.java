package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionFailure;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Generation.ProjectScaffoldService;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchPlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PlanningNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.CONTRACT;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.GENERATION_PLAN;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.REQUIREMENT;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.SLICE_PLAN;

/**
 * 规划和脚手架完成后，由单个开发会话实现、测试和修复，最终以独立快照验收。
 */
@Service
public class SoftwareStudioWorkflowService {

    private final PlanningNodeService planningNodeService;
    private final ProjectScaffoldService projectScaffoldService;
    private final SlicePlanNodeService slicePlanNodeService;
    private final BatchPlanNodeService batchPlanNodeService;
    private final BatchGenerationService batchGenerationService;
    private final PersistenceNodeService persistenceNodeService;
    private final WorkspaceCheckpointService checkpointService;
    private final LlmUsageMetricsService llmUsageMetricsService;
    private final RunJournalService runJournalService;

    public SoftwareStudioWorkflowService(
            PlanningNodeService planningNodeService,
            ProjectScaffoldService projectScaffoldService,
            SlicePlanNodeService slicePlanNodeService,
            BatchPlanNodeService batchPlanNodeService,
            BatchGenerationService batchGenerationService,
            PersistenceNodeService persistenceNodeService,
            WorkspaceCheckpointService checkpointService,
            LlmUsageMetricsService llmUsageMetricsService,
            RunJournalService runJournalService) {
        this.planningNodeService = planningNodeService;
        this.projectScaffoldService = projectScaffoldService;
        this.slicePlanNodeService = slicePlanNodeService;
        this.batchPlanNodeService = batchPlanNodeService;
        this.batchGenerationService = batchGenerationService;
        this.persistenceNodeService = persistenceNodeService;
        this.checkpointService = checkpointService;
        this.llmUsageMetricsService = llmUsageMetricsService;
        this.runJournalService = runJournalService;
    }

    public List<SourceCode> generateProject(String userRequest, Consumer<String> eventListener) {
        return generateProjectWithResult(userRequest, eventListener).codes();
    }

    /**
     * 同一开发会话可交替修改实现与测试；会话停止后仍对保留候选做最终快照验收。
     */
    public WorkflowExecutionResult generateProjectWithResult(
            String userRequest,
            Consumer<String> eventListener) {
        Consumer<String> logger = eventListener == null ? message -> { } : eventListener;
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData(userRequest);
        llmUsageMetricsService.beginTask();
        try {
            data = planningNodeService.requirements(data, logger);
            data = persistenceNodeService.initializeWorkspace(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, REQUIREMENT, logger);
            data = planningNodeService.design(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, CONTRACT, logger);
            try {
                data = projectScaffoldService.bootstrap(data, logger);
            } finally {
                // 骨架编译失败时也保留规格和真实构建结果，便于定位平台或依赖问题。
                persistenceNodeService.persistPlanningArtifacts(data, CONTRACT, logger);
            }

            // 切片仅用于业务上下文、预算估算和报告，不再作为中间验收及修复边界。
            data = slicePlanNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, SLICE_PLAN, logger);
            data = batchPlanNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, GENERATION_PLAN, logger);
            // 旧外层修复预算已退出主链，运行摘要不能继续显示未使用的三加一额度。
            data.repairBudget = null;

            // 开发、测试与修复共享同一会话；final checkpoint 是唯一成功入口。
            WorkspaceAgentRunResult development = batchGenerationService.generateProject(data, logger);
            if (!development.completed()) {
                logger.accept("   Development stopped: " + development.stopReason()
                        + "; independently verifying preserved candidate.");
                checkpointService.verifyCandidate(data, logger);
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
                logger.accept("Project generation finished without a passing final checkpoint.");
            }
            persistenceNodeService.persistRunSummary(data, runJournalService.summarize(data), logger);
            return result(data);
        } catch (RuntimeException e) {
            // 候选项目已创建后即使工作流异常，也要保留路径供外部判卷和故障诊断。
            throw new WorkflowExecutionFailure(data.projectPath, e);
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
