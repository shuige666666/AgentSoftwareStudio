package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Repair.ProjectRepairService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ArchitectureNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchPlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchValidationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ContractNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.EvaluationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.FrontendReviewNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.RequirementNodeService;
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
    private final BatchPlanNodeService batchPlanNodeService;
    private final BatchGenerationService batchGenerationService;
    private final BatchValidationNodeService batchValidationNodeService;
    private final TestGenerationNodeService testGenerationNodeService;
    private final FrontendReviewNodeService frontendReviewNodeService;
    private final PersistenceNodeService persistenceNodeService;
    private final VerificationNodeService verificationNodeService;
    private final EvaluationNodeService evaluationNodeService;
    private final ProjectRepairService projectRepairService;
    private final LlmUsageMetricsService llmUsageMetricsService;

    /**
     * 注入软件工坊工作流所需的节点服务。
     */
    public SoftwareStudioWorkflowService(RequirementNodeService requirementNodeService,
            ArchitectureNodeService architectureNodeService,
            ContractNodeService contractNodeService,
            BatchPlanNodeService batchPlanNodeService,
            BatchGenerationService batchGenerationService,
            BatchValidationNodeService batchValidationNodeService,
            TestGenerationNodeService testGenerationNodeService,
            FrontendReviewNodeService frontendReviewNodeService,
            PersistenceNodeService persistenceNodeService,
            VerificationNodeService verificationNodeService,
            EvaluationNodeService evaluationNodeService,
            ProjectRepairService projectRepairService,
            LlmUsageMetricsService llmUsageMetricsService) {
        this.requirementNodeService = requirementNodeService;
        this.architectureNodeService = architectureNodeService;
        this.contractNodeService = contractNodeService;
        this.batchPlanNodeService = batchPlanNodeService;
        this.batchGenerationService = batchGenerationService;
        this.batchValidationNodeService = batchValidationNodeService;
        this.testGenerationNodeService = testGenerationNodeService;
        this.frontendReviewNodeService = frontendReviewNodeService;
        this.persistenceNodeService = persistenceNodeService;
        this.verificationNodeService = verificationNodeService;
        this.evaluationNodeService = evaluationNodeService;
        this.projectRepairService = projectRepairService;
        this.llmUsageMetricsService = llmUsageMetricsService;
    }

    /**
     * 启动完整的项目生成工作流
     */
    public List<SourceCode> generateProject(String userRequest, Consumer<String> eventListener, int maxRetries) {
        Consumer<String> logger = eventListener != null ? eventListener : message -> {
        };
        SoftwareStudioWorkflowData initialData = new SoftwareStudioWorkflowData(userRequest, maxRetries);
        llmUsageMetricsService.beginTask();
        try {
            SoftwareStudioWorkflowData finalData = runWorkflowGraph(initialData, logger);
            if (!finalData.success) {
                logger.accept("Project generation finished without a clean pass after " + maxRetries + " repair attempts.");
            }
            return finalData.codes;
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
        NodeAction<SoftwareStudioWorkflowGraphState> planNode = createNodeAction(batchPlanNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> generateBatchNode = createNodeAction(this::executeGenerateBatch, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> validateBatchNode = createNodeAction(batchValidationNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> generateTestsNode = createNodeAction(testGenerationNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> frontendReviewNode = createNodeAction(frontendReviewNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> persistNode = createNodeAction(persistenceNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> runNode = createNodeAction(verificationNodeService::run, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> evaluateNode = createNodeAction(evaluationNodeService::execute, logger);
        NodeAction<SoftwareStudioWorkflowGraphState> fixNode = createNodeAction(this::executeFix, logger);

        try {
            // 整体图的职责：
            // 1. 先产出 PRD 和架构蓝图
            // 2. 再做批次规划
            // 3. 按批次生成代码，每个批次生成后只做轻量校验
            // 4. 所有批次结束后，统一生成测试、统一落盘、统一编译和修复
            var graph = new StateGraph<>(SoftwareStudioWorkflowGraphState.SCHEMA, SoftwareStudioWorkflowGraphState::new)
                    .addNode("pm", node_async(pmNode))
                    .addNode("architect", node_async(architectNode))
                    .addNode("contract", node_async(contractNode))
                    .addNode("plan_batches", node_async(planNode))
                    .addNode("generate_batch", node_async(generateBatchNode))
                    .addNode("validate_batch", node_async(validateBatchNode))
                    .addNode("generate_tests", node_async(generateTestsNode))
                    .addNode("frontend_review", node_async(frontendReviewNode))
                    .addNode("persist", node_async(persistNode))
                    .addNode("run", node_async(runNode))
                    .addNode("evaluate", node_async(evaluateNode))
                    .addNode("fix", node_async(fixNode))
                    .addEdge(START, "pm")
                    .addEdge("pm", "architect")
                    .addEdge("architect", "contract")
                    .addEdge("contract", "plan_batches")
                    .addEdge("plan_batches", "generate_batch")
                    .addEdge("generate_batch", "validate_batch")
                    .addConditionalEdges(
                            "validate_batch",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(
                                    state.workflowData().hasMoreBatches() ? "NEXT_BATCH" : "GENERATE_TESTS"),
                            Map.of("NEXT_BATCH", "generate_batch", "GENERATE_TESTS", "generate_tests"))
                    .addEdge("generate_tests", "frontend_review")
                    .addEdge("frontend_review", "persist")
                    .addEdge("persist", "run")
                    .addEdge("run", "evaluate")
                    .addConditionalEdges(
                            "evaluate",
                            state -> java.util.concurrent.CompletableFuture.completedFuture(state.workflowData().shouldFix ? "FIX" : "END"),
                            Map.of("FIX", "fix", "END", END))
                    .addEdge("fix", "run")
                    // LangGraph4j 默认最大迭代数较小（25），
                    // 我们这个工作流包含“按批次循环 +可选修复循环”，正常情况下也可能超过默认值。
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
     * 生成当前批次内的全部源文件
     */
    private SoftwareStudioWorkflowData executeGenerateBatch(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        batchGenerationService.generateBatch(data, logger);
        return data;
    }

    /**
     * 执行最终修复节点并将修复结果写回工作区
     */
    private SoftwareStudioWorkflowData executeFix(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        projectRepairService.repair(data, logger);
        return data;
    }

    /**
     * 估算一次完整流程的最大节点步数，避免默认 25 次迭代过早触发。
     */
    private int calculateMaxIterations(SoftwareStudioWorkflowData data) {
        // 在 architect 完成前批次数未知，给一个保守基础值。
        int estimatedBatchCount = 20;
        if (data != null && data.generationPlan != null && data.generationPlan.batches() != null
                && !data.generationPlan.batches().isEmpty()) {
            estimatedBatchCount = data.generationPlan.batches().size();
        }

        int retries = data == null ? 5 : Math.max(1, data.maxRetries);
        // 基础节点（pm/architect/plan/tests/persist/run/evaluate）约 8 步；
        // 每批次有 generate+validate 两步；
        // 每次修复回环有 fix+run+evaluate 三步。
        int estimated = 8 + estimatedBatchCount * 2 + retries * 3;
        return Math.max(estimated, 200);
    }
}
