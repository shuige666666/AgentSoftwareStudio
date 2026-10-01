package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkReplayMode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionFailure;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Generation.ProjectScaffoldService;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointFeedback;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
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
    private final DevelopmentMilestoneService milestoneService;
    private final DevelopmentResumeService resumeService;
    private final BatchGenerationService batchGenerationService;
    private final PersistenceNodeService persistenceNodeService;
    private final WorkspaceCheckpointService checkpointService;
    private final LlmUsageMetricsService llmUsageMetricsService;
    private final RunJournalService runJournalService;

    public SoftwareStudioWorkflowService(
            PlanningNodeService planningNodeService,
            ProjectScaffoldService projectScaffoldService,
            SlicePlanNodeService slicePlanNodeService,
            DevelopmentMilestoneService milestoneService,
            DevelopmentResumeService resumeService,
            BatchGenerationService batchGenerationService,
            PersistenceNodeService persistenceNodeService,
            WorkspaceCheckpointService checkpointService,
            LlmUsageMetricsService llmUsageMetricsService,
            RunJournalService runJournalService) {
        this.planningNodeService = planningNodeService;
        this.projectScaffoldService = projectScaffoldService;
        this.slicePlanNodeService = slicePlanNodeService;
        this.milestoneService = milestoneService;
        this.resumeService = resumeService;
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
        return generateProjectWithResult(userRequest, null, eventListener);
    }

    /** 基准入口在开发前绑定平台公开检查，普通生产请求保持未覆盖状态。 */
    public WorkflowExecutionResult generateProjectWithResult(String userRequest, String behaviorCheckId,
                                                              Consumer<String> eventListener) {
        Consumer<String> logger = eventListener == null ? message -> { } : eventListener;
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData(userRequest);
        data.behaviorCheckId = behaviorCheckId;
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

            // 业务切片与生成计划统一排序；简单项目保持单任务开发。
            data = slicePlanNodeService.execute(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, SLICE_PLAN, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, GENERATION_PLAN, logger);
            milestoneService.initialize(data);
            return executeDevelopment(data, logger);
        } catch (RuntimeException e) {
            // 候选项目已创建后即使工作流异常，也要保留路径供外部判卷和故障诊断。
            throw new WorkflowExecutionFailure(data.projectPath, e);
        } finally {
            logger.accept(llmUsageMetricsService.formatSummary());
        }
    }

    /** 从磁盘上的当前候选和最近已接受能力继续，不重复规划或脚手架阶段。 */
    public WorkflowExecutionResult resumeProjectWithResult(String projectId,
                                                            Consumer<String> eventListener) {
        Consumer<String> logger = eventListener == null ? message -> { } : eventListener;
        SoftwareStudioWorkflowData data = resumeService.load(projectId);
        llmUsageMetricsService.beginTask();
        try {
            logger.accept("Resuming development for " + projectId + " from saved workspace state.");
            if (data.milestoneState != null
                    && data.milestoneState.schemaVersion() < com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION) {
                logger.accept("Legacy development state is migrating to advisory milestones; progress, verified versions and budget are preserved. "
                        + "Original state is backed up as " + DevelopmentMilestoneService.LEGACY_STATE_BACKUP + " before saving.");
            }
            return executeDevelopment(data, logger);
        } catch (RuntimeException e) {
            throw new WorkflowExecutionFailure(data.projectPath, e);
        } finally {
            logger.accept(llmUsageMetricsService.formatSummary());
        }
    }

    /** 基准对照复用已保存的规划，在新脚手架上从零开发，不继承旧候选或轮次。 */
    public WorkflowExecutionResult generateFromSavedPlanningWithResult(String sourceProjectId,
                                                                        String expectedRequest,
                                                                        Consumer<String> eventListener) {
        return generateFromSavedPlanningWithResult(sourceProjectId, expectedRequest,
                BenchmarkReplayMode.SAVED_PLAN, eventListener);
    }

    /** 复用上游设计，可保留原任务建议或重建当前确定性任务计划。 */
    public WorkflowExecutionResult generateFromSavedPlanningWithResult(String sourceProjectId,
                                                                        String expectedRequest,
                                                                        BenchmarkReplayMode replayMode,
                                                                        Consumer<String> eventListener) {
        return generateFromSavedPlanningWithResult(sourceProjectId, expectedRequest, replayMode, null, eventListener);
    }

    /** 保存规划重放使用调用方显式指定的公开检查，不推断项目名称或业务关键词。 */
    public WorkflowExecutionResult generateFromSavedPlanningWithResult(String sourceProjectId,
                                                                        String expectedRequest,
                                                                        BenchmarkReplayMode replayMode,
                                                                        String behaviorCheckId,
                                                                        Consumer<String> eventListener) {
        Consumer<String> logger = eventListener == null ? message -> { } : eventListener;
        SoftwareStudioWorkflowData saved = resumeService.load(sourceProjectId);
        if (expectedRequest == null || !saved.userRequest.strip().equals(expectedRequest.strip())) {
            throw new IllegalArgumentException("Saved planning requirement does not match the benchmark case");
        }
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData(saved.userRequest);
        data.behaviorCheckId = behaviorCheckId;
        data.prd = saved.prd;
        data.structure = saved.structure;
        data.contract = saved.contract;
        data.planningDesign = saved.planningDesign;
        data.sliceDeliveryPlan = saved.sliceDeliveryPlan;
        data.generationPlan = saved.generationPlan;
        llmUsageMetricsService.beginTask();
        try {
            logger.accept("Replaying saved planning from " + sourceProjectId + " in a new workspace.");
            data = persistenceNodeService.initializeWorkspace(data, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, REQUIREMENT, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, CONTRACT, logger);
            try {
                data = projectScaffoldService.bootstrap(data, logger);
            } finally {
                persistenceNodeService.persistPlanningArtifacts(data, CONTRACT, logger);
            }
            if (replayMode != BenchmarkReplayMode.SAVED_PLAN) {
                data = slicePlanNodeService.execute(data, logger);
            }
            data = persistenceNodeService.persistPlanningArtifacts(data, SLICE_PLAN, logger);
            data = persistenceNodeService.persistPlanningArtifacts(data, GENERATION_PLAN, logger);
            milestoneService.initialize(data);
            return executeDevelopment(data, logger);
        } catch (RuntimeException e) {
            throw new WorkflowExecutionFailure(data.projectPath, e);
        } finally {
            logger.accept(llmUsageMetricsService.formatSummary());
        }
    }

    /** 初次运行与中断续接共用全局预算、候选保留和最终验收路径。 */
    private WorkflowExecutionResult executeDevelopment(SoftwareStudioWorkflowData data,
                                                       Consumer<String> logger) {
        // 旧外层修复预算已退出主链，运行摘要不能继续显示未使用的三加一额度。
        data.repairBudget = null;
        milestoneService.persist(data);
        WorkspaceAgentRunResult development = batchGenerationService.generateProject(data, logger);
        if (!development.completed()) {
            milestoneService.recordFailure(data, "Development stopped: " + development.stopReason()
                    + "; " + development.message());
            logger.accept("   Development stopped: " + development.stopReason()
                    + "; independently verifying preserved candidate.");
            var finalVerification = checkpointService.verifyCandidate(data, logger);
            // 额度停止说明过程原因，不能覆盖随后独立验收发现的真实缺陷。
            if (!finalVerification.allowCompletion()) {
                milestoneService.recordFailure(data, "Development stopped: " + development.stopReason() + "\n"
                        + WorkspaceCheckpointFeedback.failureSummary(finalVerification));
            }
        }
        if (data.success && data.milestoneState != null && !data.milestoneState.active()
                && data.sliceDeliveryPlan != null) {
            milestoneService.acceptSingleFinal(data);
        }
        if (data.success) {
            data.acceptedTestFiles = data.codes.stream()
                    .filter(code -> code != null && code.filename() != null)
                    .map(SourceCode::filename)
                    .filter(path -> path.replace('\\', '/').startsWith("src/test/"))
                    .distinct().toList();
        } else {
            logger.accept("Project generation finished without a passing final checkpoint.");
        }
        persistenceNodeService.persistRunSummary(data, runJournalService.summarize(data), logger);
        return result(data);
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
