package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Repair.ProjectRepairService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ArchitectureNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ContractNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.EvaluationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.FrontendReviewNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PreflightValidationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.RequirementNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SliceAcceptanceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.TestGenerationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.VerificationNodeService;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 负责使用 LangGraph4j 编排软件工坊完整生成流程，并调度各个节点服务执行具体业务逻辑。
 */
@Service
public class SoftwareStudioWorkflowService {

    private final RequirementNodeService requirementNodeService;
    private final ArchitectureNodeService architectureNodeService;
    private final ContractNodeService contractNodeService;
    private final SlicePlanNodeService slicePlanNodeService;
    private final BatchGenerationService batchGenerationService;
    private final TestGenerationNodeService testGenerationNodeService;
    private final FrontendReviewNodeService frontendReviewNodeService;
    private final PersistenceNodeService persistenceNodeService;
    private final PreflightValidationNodeService preflightValidationNodeService;
    private final VerificationNodeService verificationNodeService;
    private final EvaluationNodeService evaluationNodeService;
    private final SliceAcceptanceNodeService sliceAcceptanceNodeService;
    private final ProjectRepairService projectRepairService;
    private final LlmUsageMetricsService llmUsageMetricsService;
    private final RunJournalService runJournalService;

    /**
     * 注入软件工坊工作流所需的节点服务。
     */
    public SoftwareStudioWorkflowService(RequirementNodeService requirementNodeService,
            ArchitectureNodeService architectureNodeService,
            ContractNodeService contractNodeService,
            SlicePlanNodeService slicePlanNodeService,
            BatchGenerationService batchGenerationService,
            TestGenerationNodeService testGenerationNodeService,
            FrontendReviewNodeService frontendReviewNodeService,
            PreflightValidationNodeService preflightValidationNodeService,
            PersistenceNodeService persistenceNodeService,
            VerificationNodeService verificationNodeService,
            EvaluationNodeService evaluationNodeService,
            SliceAcceptanceNodeService sliceAcceptanceNodeService,
            ProjectRepairService projectRepairService,
            LlmUsageMetricsService llmUsageMetricsService,
            RunJournalService runJournalService) {
        this.requirementNodeService = requirementNodeService;
        this.architectureNodeService = architectureNodeService;
        this.contractNodeService = contractNodeService;
        this.slicePlanNodeService = slicePlanNodeService;
        this.batchGenerationService = batchGenerationService;
        this.testGenerationNodeService = testGenerationNodeService;
        this.frontendReviewNodeService = frontendReviewNodeService;
        this.preflightValidationNodeService = preflightValidationNodeService;
        this.persistenceNodeService = persistenceNodeService;
        this.verificationNodeService = verificationNodeService;
        this.evaluationNodeService = evaluationNodeService;
        this.sliceAcceptanceNodeService = sliceAcceptanceNodeService;
        this.projectRepairService = projectRepairService;
        this.llmUsageMetricsService = llmUsageMetricsService;
        this.runJournalService = runJournalService;
    }

    /**
     * 启动完整的项目生成工作流
     */
    public List<SourceCode> generateProject(String userRequest, Consumer<String> eventListener) {
        return generateProjectWithResult(userRequest, eventListener).codes();
    }

    /**
     * 执行真实工作流，并返回质量基准所需的平台结论、验证证据和产物位置。
     */
    public WorkflowExecutionResult generateProjectWithResult(String userRequest, Consumer<String> eventListener) {
        Consumer<String> logger = eventListener != null ? eventListener : message -> {
        };
        SoftwareStudioWorkflowData initialData = new SoftwareStudioWorkflowData(userRequest);
        llmUsageMetricsService.beginTask();
        try {
            SoftwareStudioWorkflowData finalData = runWorkflowGraph(initialData, logger);
            if (!finalData.success) {
                int usedRepairs = finalData.repairBudget == null ? 0 : finalData.repairBudget.usedLlmRepairs();
                int repairLimit = finalData.repairBudget == null ? 0 : finalData.repairBudget.maxLlmRepairs();
                logger.accept("Project generation finished without a clean pass after "
                        + usedRepairs + "/" + repairLimit + " LLM repair calls.");
            }
            return new WorkflowExecutionResult(
                    List.copyOf(finalData.codes),
                    finalData.success,
                    finalData.projectPath,
                    finalData.executionResult,
                    finalData.testResult,
                    List.copyOf(finalData.validationWarnings),
                    finalData.pendingErrorType,
                    finalData.currentAttempt,
                    llmUsageMetricsService.snapshot(),
                    finalData.qualityPolicyResult,
                    finalData.verificationResult,
                    runJournalService.summarize(finalData));
        } finally {
            logger.accept(llmUsageMetricsService.formatSummary());
        }
    }

    /**
     * 根据业务动作和日志监听器构建一个 LangGraph4j 节点动作。
     * 将通用的 BiFunction 风格节点逻辑适配为 NodeAction 接口，
     * 内部从工作流图状态中提取数据，执行业务动作后，将结果以 DATA_KEY 写回状态 Map。
     *
     * @param action 接收工作流数据和日志监听器，返回更新后的工作流数据
     * @param logger 用于输出节点执行过程中的日志信息
     * @return 适配后的 NodeAction，可直接注册到 StateGraph 中
     */
    private NodeAction<SoftwareStudioWorkflowGraphState> createNodeAction(
            BiFunction<SoftwareStudioWorkflowData, Consumer<String>, SoftwareStudioWorkflowData> action,
            Consumer<String> logger) {
        return state -> Map.of(SoftwareStudioWorkflowGraphState.DATA_KEY, action.apply(state.workflowData(), logger));
    }

    /**
     * 使用 LangGraph4j 编排整个多节点工作流
     */
    private SoftwareStudioWorkflowData runWorkflowGraph(SoftwareStudioWorkflowData initialData, Consumer<String> logger) {
        // 这里刻意让每个节点都“接收完整状态、返回完整状态”。
        // 这样真正驱动流程的是 LangGraph 的状态流转，而不是方法外部的共享可变变量。
        // 后面如果你想继续扩展更多节点、增加分支条件，这个形态会更容易维护。
        NodeAction<SoftwareStudioWorkflowGraphState> pmNode = createNodeAction(requirementNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> architectNode = createNodeAction(architectureNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> contractNode = createNodeAction(contractNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> planNode = createNodeAction(slicePlanNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> initializeWorkspaceNode = createNodeAction(
                persistenceNodeService::initializeWorkspace, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> generateSliceNode = createNodeAction(this::executeGenerateSlice, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> generateTestsNode = createNodeAction(
                testGenerationNodeService::executeCurrentSlice, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> frontendReviewNode = createNodeAction(frontendReviewNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> snapshotSliceNode = createNodeAction(
                this::executeSnapshotSlice, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> preflightNode = createNodeAction(preflightValidationNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> persistNode = createNodeAction(
                persistenceNodeService::persistCurrentSlice, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> runNode = createNodeAction(verificationNodeService::run, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> evaluateNode = createNodeAction(evaluationNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> fixNode = createNodeAction(this::executeFix, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> acceptSliceNode = createNodeAction(
                sliceAcceptanceNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> prepareFinalNode = createNodeAction(
                sliceAcceptanceNodeService::prepareFinalVerification, logger);

        try {
            // 整体图的职责：
            // 1. 先产出 PRD 和架构蓝图
            // 2. 将架构文件按业务能力组织成垂直切片
            // 3. 每个切片依次完成代码、测试、门禁、落盘和真实验证
            // 4. 已接受切片的测试持续参与后续回归，失败只能修改当前切片或共享文件
            // 5. 全部切片接受后再做一次完整项目门禁和全量验证
            var graph = new StateGraph<>(SoftwareStudioWorkflowGraphState.SCHEMA, SoftwareStudioWorkflowGraphState::new)
                    .addNode("pm", node_async(pmNode))
                    .addNode("architect", node_async(architectNode))
                    .addNode("contract", node_async(contractNode))
                    .addNode("plan_slices", node_async(planNode))
                    .addNode("initialize_workspace", node_async(initializeWorkspaceNode))
                    .addNode("generate_slice", node_async(generateSliceNode))
                    .addNode("generate_slice_tests", node_async(generateTestsNode))
                    .addNode("frontend_review", node_async(frontendReviewNode))
                    .addNode("snapshot_slice", node_async(snapshotSliceNode))
                    .addNode("preflight", node_async(preflightNode))
                    .addNode("persist", node_async(persistNode))
                    .addNode("run", node_async(runNode))
                    .addNode("evaluate", node_async(evaluateNode))
                    .addNode("fix", node_async(fixNode))
                    .addNode("accept_slice", node_async(acceptSliceNode))
                    .addNode("prepare_final", node_async(prepareFinalNode))
                    .addEdge(START, "pm")
                    .addEdge("pm", "architect")
                    .addEdge("architect", "contract")
                    .addEdge("contract", "plan_slices")
                    .addEdge("plan_slices", "initialize_workspace")
                    .addEdge("initialize_workspace", "generate_slice")
                    .addEdge("generate_slice", "generate_slice_tests")
                    .addEdge("generate_slice_tests", "frontend_review")
                    .addEdge("frontend_review", "snapshot_slice")
                    .addEdge("snapshot_slice", "preflight")
                    .addConditionalEdges(
                            "preflight",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(preflightRoute(state.workflowData())),
                            Map.of("PERSIST", "persist", "RUN", "run", "FIX", "fix", "END", END))
                    .addEdge("persist", "run")
                    .addEdge("run", "evaluate")
                    .addConditionalEdges(
                            "evaluate",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(
                                    evaluationRoute(state.workflowData())),
                            Map.of("ACCEPT", "accept_slice", "FIX", "fix", "END", END))
                    .addConditionalEdges(
                            "fix",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(
                                    state.workflowData().resumeSliceTestGeneration ? "GENERATE_TESTS" : "PREFLIGHT"),
                            Map.of("GENERATE_TESTS", "generate_slice_tests", "PREFLIGHT", "preflight"))
                    .addConditionalEdges(
                            "accept_slice",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(
                                    state.workflowData().hasMoreSlices() ? "NEXT_SLICE" : "FINAL_VERIFY"),
                            Map.of("NEXT_SLICE", "generate_slice", "FINAL_VERIFY", "prepare_final"))
                    .addEdge("prepare_final", "preflight")
                    // LangGraph4j 默认最大迭代数较小（25），
                    // 当前工作流包含“按切片循环 + 可选修复循环”，正常情况下也可能超过默认值。
                    // 提高最大迭代数上限以避免误判为死循环，同时仍保留兜底保护。
                    .compile(CompileConfig.builder()
                            .recursionLimit(calculateMaxIterations(initialData))
                            .build());

            SoftwareStudioWorkflowData lastData = initialData;
            for (var output : graph.stream(Map.of(SoftwareStudioWorkflowGraphState.DATA_KEY, initialData))) {
                lastData = output.state().workflowData();
            }
            return lastData;
        } catch (GraphStateException e) {
            throw new IllegalStateException("LangGraph4j workflow compile failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("LangGraph4j workflow execution failed", e);
        }
    }

    /**
     * 生成当前垂直切片拥有的全部源码文件。
     */
    private SoftwareStudioWorkflowData executeGenerateSlice(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        batchGenerationService.generateSlice(data, logger);
        return data;
    }

    /**
     * 保存门禁前诊断快照；测试替身未提供返回值时仍保持原工作流状态。
     */
    private SoftwareStudioWorkflowData executeSnapshotSlice(
            SoftwareStudioWorkflowData data,
            Consumer<String> logger) {
        SoftwareStudioWorkflowData result = persistenceNodeService.snapshotCurrentSlice(data, logger);
        return result == null ? data : result;
    }

    /**
     * 执行当前切片或最终验证的修复节点，并将结果写回共享工作区。
     */
    private SoftwareStudioWorkflowData executeFix(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        projectRepairService.repair(data, logger);
        return data;
    }

    /**
     * 估算一次完整流程的最大节点步数，避免默认 25 次迭代过早触发。
     */
    private int calculateMaxIterations(SoftwareStudioWorkflowData data) {
        // 在架构规划完成前切片数未知，给一个保守基础值。
        int estimatedSliceCount = 20;
        if (data != null && data.sliceDeliveryPlan != null && data.sliceDeliveryPlan.slices() != null
                && !data.sliceDeliveryPlan.slices().isEmpty()) {
            estimatedSliceCount = data.sliceDeliveryPlan.slices().size();
        }

        int repairs = com.core.multiAgentSoftwareStudio.Config.RepairBudgetConfig.MAX_PROJECT_LLM_REPAIRS;
        // 每个切片包含生成、测试、审查、门禁、持久化、验证、接受等节点；
        // 每次修复还会重新经过门禁和验证，最终再预留一轮全量验证。
        int estimated = 12 + estimatedSliceCount * 8 + repairs * 4;
        return Math.max(estimated, 200);
    }

    /**
     * 前置门禁失败时消耗共享修复预算；通过后根据是否已落盘选择持久化或重新验证。
     */
    private String preflightRoute(SoftwareStudioWorkflowData data) {
        if (data.repairStopRequested) {
            return "END";
        }
        if (!data.preflightPassed) {
            return data.shouldFix ? "FIX" : "END";
        }
        if (data.finalVerificationStarted) {
            return "RUN";
        }
        return data.currentSlicePersisted ? "RUN" : "PERSIST";
    }

    /**
     * 切片通过真实验证后先进入接受节点；最终验证通过才结束整个工作流。
     */
    private String evaluationRoute(SoftwareStudioWorkflowData data) {
        if (data.shouldFix) {
            return "FIX";
        }
        if (data.currentSliceVerified && !data.finalVerificationStarted) {
            return "ACCEPT";
        }
        return "END";
    }
}
