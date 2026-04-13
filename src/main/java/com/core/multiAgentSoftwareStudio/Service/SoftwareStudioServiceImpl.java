package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFix;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class SoftwareStudioServiceImpl implements SoftwareStudioService {

    private final ProductManagerAgent pmAgent;
    private final ArchitectAgent architectAgent;
    private final DeveloperAgent developerAgent;
    private final TestWriterAgent testWriterAgent;
    private final DebuggerAgent debuggerAgent;
    private final DockerSandboxService sandboxService;
    private final WorkspaceService workspaceService;
    private final BatchPlanningService batchPlanningService;
    private final ContractValidationService contractValidationService;

    public SoftwareStudioServiceImpl(ProductManagerAgent pmAgent,
                                     ArchitectAgent architectAgent,
                                     DeveloperAgent developerAgent,
                                     TestWriterAgent testWriterAgent,
                                     DebuggerAgent debuggerAgent,
                                     DockerSandboxService sandboxService,
                                     WorkspaceService workspaceService,
                                     BatchPlanningService batchPlanningService,
                                     ContractValidationService contractValidationService) {
        this.pmAgent = pmAgent;
        this.architectAgent = architectAgent;
        this.developerAgent = developerAgent;
        this.testWriterAgent = testWriterAgent;
        this.debuggerAgent = debuggerAgent;
        this.sandboxService = sandboxService;
        this.workspaceService = workspaceService;
        this.batchPlanningService = batchPlanningService;
        this.contractValidationService = contractValidationService;
    }

    @Override
    public List<SourceCode> generateProject(String userRequest) {
        return generateProjectInternal(userRequest, System.out::println, 5);
    }

    @Override
    public void generateProjectStream(String userRequest, Consumer<String> eventListener) {
        generateProjectInternal(userRequest, eventListener, 5);
    }

    private List<SourceCode> generateProjectInternal(String userRequest, Consumer<String> eventListener, int maxRetries) {
        Consumer<String> logger = eventListener != null ? eventListener : message -> {
        };
        WorkflowData initialData = new WorkflowData(userRequest, maxRetries);
        WorkflowData finalData = runWorkflowGraph(initialData, logger);
        if (!finalData.success) {
            logger.accept("Project generation finished without a clean pass after " + maxRetries + " repair attempts.");
        }
        return finalData.codes;
    }

    private WorkflowData runWorkflowGraph(WorkflowData initialData, Consumer<String> logger) {
        // 这里刻意让每个节点都“接收完整状态、返回完整状态”。
        // 这样真正驱动流程的是 LangGraph 的状态流转，而不是方法外部的共享可变变量。
        // 后面如果你想继续扩展更多节点、增加分支条件，这个形态会更容易维护。
        NodeAction<WorkflowGraphState> pmNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executePm(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> architectNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeArchitect(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> planNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executePlanBatches(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> generateBatchNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeGenerateBatch(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> validateBatchNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeValidateBatch(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> generateTestsNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeGenerateTests(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> persistNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executePersist(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> runNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeRun(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> evaluateNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeEvaluate(state.workflowData(), logger));
        NodeAction<WorkflowGraphState> fixNode = state -> Map.of(WorkflowGraphState.DATA_KEY, executeFix(state.workflowData(), logger));

        try {
            // 整体图的职责：
            // 1. 先产出 PRD 和架构蓝图
            // 2. 再做批次规划
            // 3. 按批次生成代码，每个批次生成后只做轻量校验
            // 4. 所有批次结束后，统一生成测试、统一落盘、统一编译和修复
            var graph = new StateGraph<>(WorkflowGraphState.SCHEMA, WorkflowGraphState::new)
                    .addNode("pm", node_async(pmNode))
                    .addNode("architect", node_async(architectNode))
                    .addNode("plan_batches", node_async(planNode))
                    .addNode("generate_batch", node_async(generateBatchNode))
                    .addNode("validate_batch", node_async(validateBatchNode))
                    .addNode("generate_tests", node_async(generateTestsNode))
                    .addNode("persist", node_async(persistNode))
                    .addNode("run", node_async(runNode))
                    .addNode("evaluate", node_async(evaluateNode))
                    .addNode("fix", node_async(fixNode))
                    .addEdge(START, "pm")
                    .addEdge("pm", "architect")
                    .addEdge("architect", "plan_batches")
                    .addEdge("plan_batches", "generate_batch")
                    .addEdge("generate_batch", "validate_batch")
                    .addConditionalEdges(
                            "validate_batch",
                            state -> CompletableFuture.completedFuture(state.workflowData().hasMoreBatches() ? "NEXT_BATCH" : "GENERATE_TESTS"),
                            Map.of("NEXT_BATCH", "generate_batch", "GENERATE_TESTS", "generate_tests"))
                    .addEdge("generate_tests", "persist")
                    .addEdge("persist", "run")
                    .addEdge("run", "evaluate")
                    .addConditionalEdges(
                            "evaluate",
                            state -> CompletableFuture.completedFuture(state.workflowData().shouldFix ? "FIX" : "END"),
                            Map.of("FIX", "fix", "END", END))
                    .addEdge("fix", "run")
                    .compile();

            WorkflowData lastData = initialData;
            for (var output : graph.stream(Map.of(WorkflowGraphState.DATA_KEY, initialData))) {
                lastData = output.state().workflowData();
            }
            return lastData;
        } catch (GraphStateException e) {
            throw new IllegalStateException("LangGraph4j workflow compile failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("LangGraph4j workflow execution failed", e);
        }
    }

    private WorkflowData executePm(WorkflowData data, Consumer<String> logger) {
        logger.accept("1. Product manager is analyzing the request.");
        data.prd = pmAgent.analyzeRequirement(data.userRequest);
        logger.accept("PRD created: " + safeValue(data.prd == null ? null : data.prd.projectName(), "Unnamed Project"));
        return data;
    }

    private WorkflowData executeArchitect(WorkflowData data, Consumer<String> logger) {
        logger.accept("2. Architect is designing the project structure.");
        data.structure = architectAgent.designArchitecture(data.prd);
        int fileCount = data.structure == null || data.structure.files() == null ? 0 : data.structure.files().size();
        logger.accept("Architecture ready with " + fileCount + " planned files.");
        return data;
    }

    private WorkflowData executePlanBatches(WorkflowData data, Consumer<String> logger) {
        // 这个节点把“架构蓝图”转换成“可执行计划”。
        // 也就是把文件从静态描述，变成真正的生成批次序列。
        data.generationPlan = batchPlanningService.createPlan(data.structure);
        data.currentBatchIndex = 0;
        logger.accept("3. Batch plan created with " + data.generationPlan.batches().size() + " batches.");
        for (int i = 0; i < data.generationPlan.batches().size(); i++) {
            GenerationBatch batch = data.generationPlan.batches().get(i);
            logger.accept("   Batch " + (i + 1) + ": " + batch.name() + " [" + batch.layer() + "] -> " + batch.files().size() + " files");
        }
        return data;
    }

    private WorkflowData executeGenerateBatch(WorkflowData data, Consumer<String> logger) {
        GenerationBatch batch = data.currentBatch();
        if (batch == null) {
            return data;
        }

        logger.accept("4." + (data.currentBatchIndex + 1) + " Generating batch `" + batch.name() + "` (" + batch.layer() + ").");
        String existingCodeContext = buildCodeContextForTester(data.codes);
        String batchContext = describeBatch(batch);

        // 同一批次内的文件，默认认为依赖关系已经足够松，可以并发生成。
        // 批次之间的先后顺序，已经在 plan_batches 阶段提前处理好了。
        // 这样做的目的，是把“并发收益”放在文件生成阶段，而不是把复杂性堆到运行时调度里。
        List<FileBlueprint> batchFiles = batch.files().stream()
                .sorted(Comparator.comparing(FileBlueprint::targetPath))
                .toList();
        List<CompletableFuture<SourceCode>> futures = batchFiles.stream()
                .map(fileBlueprint -> CompletableFuture.supplyAsync(
                        () -> generateSourceFile(data.prd, data.structure, existingCodeContext, batchContext, fileBlueprint)))
                .toList();

        for (int i = 0; i < batchFiles.size(); i++) {
            FileBlueprint blueprint = batchFiles.get(i);
            SourceCode generatedFile = futures.get(i).join();
            upsertCode(data.codes, generatedFile);
            logger.accept("   Generated: " + blueprint.targetPath());
        }
        return data;
    }

    private WorkflowData executeValidateBatch(WorkflowData data, Consumer<String> logger) {
        GenerationBatch batch = data.currentBatch();
        if (batch == null) {
            return data;
        }

        // 这里故意只做“便宜检查”：
        // - 路径和 package 是否对齐
        // - public 类型名是否和文件名一致
        // - 是否还残留明显占位内容
        // - 某些层级是否缺少典型注解
        //
        // 不在这里触发完整编译/修复，是为了控制 token 成本。
        // 真正昂贵的调试循环只在所有代码生成完以后做一次。
        List<String> warnings = contractValidationService.validateBatch(batch, data.codes);
        if (warnings.isEmpty()) {
            logger.accept("   Lightweight contract validation passed for batch `" + batch.name() + "`.");
        } else {
            logger.accept("   Lightweight contract validation found " + warnings.size() + " warnings in batch `" + batch.name() + "`.");
            warnings.forEach(warning -> logger.accept("   - " + warning));
            data.validationWarnings.addAll(warnings);
        }
        data.currentBatchIndex++;
        return data;
    }

    private WorkflowData executeGenerateTests(WorkflowData data, Consumer<String> logger) {
        // 测试统一放到所有生产代码生成完之后再做。
        // 这样测试 Agent 能看到更完整的上下文，也能避免每层都重复生成测试。
        logger.accept("5. Generating tests after all production batches are complete.");
        TestClassesResult testClassesResult = testWriterAgent.writeTests(
                data.prd,
                data.structure,
                buildCodeContextForTester(data.codes));
        if (testClassesResult != null && testClassesResult.testFiles() != null) {
            for (SourceCode testFile : testClassesResult.testFiles()) {
                String normalizedFileName = normalizeGeneratedFilename(testFile.filename(), testFile.code());
                upsertCode(data.codes, new SourceCode(normalizedFileName, detectLanguageFromFilename(normalizedFileName), testFile.code()));
                logger.accept("   Generated test: " + normalizedFileName);
            }
        }
        return data;
    }

    private WorkflowData executePersist(WorkflowData data, Consumer<String> logger) {
        // 到这里才统一落盘，而不是每个批次都写一次磁盘。
        // 这样可以减少中间态文件干扰，也让最终修复更集中。
        data.projectPath = workspaceService.saveProjectToDisk(
                safeValue(data.prd == null ? null : data.prd.projectName(), "GeneratedProject"),
                data.codes).toString();
        logger.accept("6. Persisted generated project to disk.");
        return data;
    }

    private WorkflowData executeRun(WorkflowData data, Consumer<String> logger) {
        // 真正的编译/运行检查放在这里统一做。
        // 前面阶段只做轻量约束，避免每一层都进入昂贵的沙箱执行。
        logger.accept("7. Running final compile/runtime verification in the sandbox. Attempt " + data.currentAttempt + ".");
        data.executionResult = sandboxService.runCodeInSandbox(
                Path.of(data.projectPath),
                data.structure.projectType(),
                data.structure.mainClassName());

        logger.accept("Execution result:");
        logger.accept("--------------------------------------------------");
        logger.accept(data.executionResult);
        logger.accept("--------------------------------------------------");
        return data;
    }

    private WorkflowData executeEvaluate(WorkflowData data, Consumer<String> logger) {
        // 这个节点只负责“判断下一步该怎么走”：
        // - 成功：结束
        // - 失败且还能重试：进入 fix
        // - 失败且达到上限：返回当前结果
        data.shouldFix = false;
        data.pendingFixLog = null;
        data.pendingErrorType = null;

        String executionResult = data.executionResult == null ? "" : data.executionResult;
        boolean hasError = executionResult.contains("Exception")
                || executionResult.contains("Error")
                || executionResult.contains("failed")
                || executionResult.contains("error")
                || executionResult.contains("javac: file not found");

        if (!hasError) {
            logger.accept("8. Runtime/compile stage passed, running tests.");
            data.testResult = sandboxService.runTestsInSandbox(Path.of(data.projectPath), data.structure.projectType());
            logger.accept("Test result:");
            logger.accept(data.testResult);

            if (data.testResult.contains("Failures: 0") && data.testResult.contains("Errors: 0")) {
                logger.accept("All generated tests passed.");
                data.success = true;
                return data;
            }
            if (data.testResult.contains("Failures:") || data.testResult.contains("Errors:")) {
                data.pendingErrorType = "LOGIC ERROR (TEST FAILURE)";
                data.pendingFixLog = data.testResult;
            } else {
                logger.accept("No concrete test summary found. Treating the build as successful.");
                data.success = true;
                return data;
            }
        } else {
            data.pendingErrorType = determineErrorType(executionResult);
            data.pendingFixLog = executionResult;
        }

        // 修复被刻意延后到“全项目生成完成之后”。
        // 这样虽然最后一次修复看到的上下文更大，但总次数会少很多，
        // 整体 token 成本通常比“每个阶段都修”更低。
        data.shouldFix = data.currentAttempt < data.maxRetries;
        if (!data.shouldFix) {
            logger.accept("Reached the repair limit. Returning the latest generated code.");
        }
        return data;
    }

    private WorkflowData executeFix(WorkflowData data, Consumer<String> logger) {
        if (!data.shouldFix || data.pendingFixLog == null || data.pendingErrorType == null) {
            return data;
        }

        // fix 节点本身不做复杂路由，只负责把需要的上下文拼好，
        // 然后交给 debuggerAgent 给出整文件修复结果。
        handleFix(
                data.codes,
                data.pendingFixLog,
                data.pendingErrorType,
                Path.of(data.projectPath),
                data.validationWarnings,
                logger);
        data.currentAttempt++;
        return data;
    }

    private SourceCode generateSourceFile(PrdDocument prd,
                                          ProjectStructure structure,
                                          String existingCodeContext,
                                          String batchContext,
                                          FileBlueprint blueprint) {
        // 这里对 DeveloperAgent 的输入做了一层统一包装：
        // 当前文件路径、文件职责、方法要求、批次上下文、已有代码上下文都会一起给过去。
        // 这样单文件生成时，模型仍然能感知自己处在整个项目的哪一层。
        String targetPath = blueprint.targetPath();
        String methods = blueprint.keyMethods().isEmpty() ? "No specific methods provided." : blueprint.keyMethods().toString();

        SourceCode rawResult = developerAgent.writeCode(
                prd,
                structure,
                existingCodeContext,
                targetPath,
                blueprint.functionalityDescription(),
                methods,
                batchContext);

        String language = rawResult != null && rawResult.language() != null
                ? rawResult.language()
                : detectLanguageFromFilename(targetPath);
        String code = rawResult == null ? "" : rawResult.code();
        return new SourceCode(targetPath, language, code);
    }

    private String describeBatch(GenerationBatch batch) {
        // 给模型一段“当前批次说明”，帮助它理解这次为什么轮到这些文件。
        // 这对同层并行生成时维持一致性有帮助。
        StringBuilder builder = new StringBuilder();
        builder.append("Batch Name: ").append(batch.name()).append("\n");
        builder.append("Layer: ").append(batch.layer()).append("\n");
        builder.append("Files:\n");
        for (FileBlueprint file : batch.files()) {
            builder.append("- ").append(file.targetPath());
            if (!file.dependsOn().isEmpty()) {
                builder.append(" depends on ").append(file.dependsOn());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private void upsertCode(List<SourceCode> codes, SourceCode candidate) {
        // 同一路径的文件如果已经生成过，就直接覆盖内存中的旧版本。
        // 这样后续上下文、落盘、修复，看到的都是最新内容。
        String normalizedCandidate = normalizeGeneratedFilename(candidate.filename(), candidate.code());
        for (int i = 0; i < codes.size(); i++) {
            String normalizedExisting = normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (normalizedExisting.equals(normalizedCandidate)) {
                codes.set(i, new SourceCode(normalizedCandidate, detectLanguageFromFilename(normalizedCandidate), candidate.code()));
                return;
            }
        }
        codes.add(new SourceCode(normalizedCandidate, detectLanguageFromFilename(normalizedCandidate), candidate.code()));
    }

    private String determineErrorType(String executionResult) {
        // 这里不是做非常精确的错误分类，而是为了决定后续 prompt 应该偏向哪种修复思路。
        if (executionResult.contains("javac:") || executionResult.contains("Compilation failure")) {
            return "COMPILATION ERROR";
        }
        if (executionResult.contains("Tests run:") && executionResult.contains("Failures:")) {
            if (!executionResult.contains("Failures: 0") || !executionResult.contains("Errors: 0")) {
                return "LOGIC ERROR (TEST FAILURE)";
            }
        }
        return "RUNTIME ERROR";
    }

    private String buildCodeContextForTester(List<SourceCode> codes) {
        // 全量上下文主要给测试生成和某些需要“全局视角”的修复场景使用。
        StringBuilder builder = new StringBuilder();
        for (SourceCode code : codes) {
            builder.append("--- File: ").append(code.filename()).append(" ---\n");
            builder.append(code.code()).append("\n\n");
        }
        return builder.toString();
    }

    private void handleFix(List<SourceCode> codes,
                           String executionResult,
                           String errorType,
                           Path projectPath,
                           List<String> validationWarnings,
                           Consumer<String> logger) {
        // 修复阶段会把三类信息拼在一起：
        // 1. 当前代码上下文
        // 2. 编译/运行/测试日志
        // 3. 前面轻量校验阶段积累下来的结构警告
        // 这样 debuggerAgent 能同时看到“硬错误”和“软约束”。
        logger.accept("9. Invoking debugger agent for final repair.");

        String currentCodeContext = buildOptimizedCodeContext(codes, executionResult, errorType);
        String enhancedErrorLog = buildEnhancedErrorLog(errorType, executionResult, codes, validationWarnings);

        CodeFixResult fixResult = debuggerAgent.analyzeAndFix(errorType, enhancedErrorLog, currentCodeContext);
        List<CodeFix> fixes = fixResult == null ? null : fixResult.fixes();

        if (fixes == null || fixes.isEmpty()) {
            logger.accept("Debugger agent did not return a concrete fix.");
            return;
        }

        List<CodeFix> normalizedFixes = new ArrayList<>();
        logger.accept("Debugger agent returned " + fixes.size() + " fixes.");
        for (CodeFix fix : fixes) {
            String normalizedFilename = normalizeGeneratedFilename(fix.filename(), fix.newCode());
            CodeFix normalizedFix = new CodeFix(normalizedFilename, fix.explanation(), fix.newCode());
            logger.accept("   - " + safeValue(fix.explanation(), "No explanation provided"));
            updateCodesInMemory(codes, normalizedFix);
            normalizedFixes.add(normalizedFix);
        }
        workspaceService.applyFixesToDisk(projectPath, normalizedFixes);
    }

    private String buildOptimizedCodeContext(List<SourceCode> codes, String executionResult, String errorType) {
        // 优先只给“报错关联文件”的完整代码，其他文件给摘要。
        // 如果是逻辑错误/测试失败，则直接退回全量上下文，因为这类问题经常跨文件。
        List<String> relatedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            Path path = Path.of(normalizeGeneratedFilename(code.filename(), code.code()));
            String pureName = path.getFileName().toString();
            if (executionResult.contains(pureName)) {
                relatedFiles.add(code.filename());
            }
        }

        if (relatedFiles.isEmpty() || "LOGIC ERROR (TEST FAILURE)".equals(errorType)) {
            return buildCodeContextForTester(codes);
        }

        StringBuilder builder = new StringBuilder();
        for (SourceCode code : codes) {
            if (relatedFiles.contains(code.filename())) {
                builder.append("--- File: ").append(code.filename()).append(" ---\n");
                builder.append(code.code()).append("\n\n");
            } else {
                builder.append("--- File: ").append(code.filename()).append(" (Summary) ---\n");
                builder.append(extractSummary(code.code())).append("\n\n");
            }
        }
        return builder.toString();
    }

    private String extractSummary(String code) {
        // 摘要策略尽量简单：只保留 package、public 类型声明、public 方法签名。
        // 目标不是完全还原代码，而是让修复阶段知道项目大致轮廓。
        StringBuilder summary = new StringBuilder();
        String[] lines = code.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")
                    || trimmed.startsWith("public class ")
                    || trimmed.startsWith("public interface ")
                    || trimmed.startsWith("public record ")
                    || trimmed.startsWith("public enum ")) {
                summary.append(trimmed).append("\n");
            } else if (trimmed.startsWith("public ") && trimmed.contains("(") && trimmed.contains(")")) {
                summary.append("  ").append(trimmed.split("\\{")[0].trim()).append(";\n");
            }
        }
        if (summary.length() == 0) {
            return "// [No summary available]";
        }
        return summary.toString();
    }

    private void updateCodesInMemory(List<SourceCode> codes, CodeFix fix) {
        // debugger 返回修复结果后，先更新内存，再落盘。
        // 后续重跑时用到的是修复后的最新版本，而不是旧代码。
        boolean found = false;
        String normalizedFixFilename = normalizeGeneratedFilename(fix.filename(), fix.newCode());
        String fixPureName = Path.of(normalizedFixFilename).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            String existingFilename = normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (existingFilename.equals(normalizedFixFilename)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
            String existingPureName = Path.of(existingFilename).getFileName().toString();
            if (existingPureName.equals(fixPureName)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }

        if (!found) {
            codes.add(new SourceCode(normalizedFixFilename, detectLanguageFromFilename(normalizedFixFilename), fix.newCode()));
        }
    }

    private String normalizeGeneratedFilename(String filename, String code) {
        // 模型返回的文件名有时只写类名，有时写完整路径。
        // 这里统一收敛成项目内相对路径，方便去重、覆盖和落盘。
        if (filename == null || filename.isBlank()) {
            return "Unknown.java";
        }
        String normalized = filename.trim().replace("\\", "/");
        int testPathIndex = normalized.indexOf("src/test/java/");
        if (testPathIndex >= 0) {
            normalized = normalized.substring(testPathIndex);
        } else {
            int mainPathIndex = normalized.indexOf("src/main/java/");
            if (mainPathIndex >= 0) {
                normalized = normalized.substring(mainPathIndex);
            }
        }
        boolean isLikelyTest = normalized.endsWith("Test.java")
                || (code != null && (code.contains("org.junit.jupiter") || code.contains("@Test")));
        if (isLikelyTest && normalized.startsWith("src/main/java/")) {
            normalized = "src/test/java/" + normalized.substring("src/main/java/".length());
        }
        return normalized;
    }

    private String buildEnhancedErrorLog(String errorType,
                                         String executionResult,
                                         List<SourceCode> codes,
                                         List<String> validationWarnings) {
        // 编译错误除了原始日志，还会额外拼接一层“启发式提示”。
        // 同时把中间轻量校验发现的问题一起给 debugger，减少来回试错。
        StringBuilder builder = new StringBuilder(executionResult == null ? "" : executionResult);

        if ("COMPILATION ERROR".equals(errorType)) {
            String classification = classifyCompilationErrors(executionResult == null ? "" : executionResult, codes);
            if (!classification.isBlank()) {
                builder.append("\n\n=== COMPILATION ERROR CLASSIFIER ===\n").append(classification);
            }
        }

        if (validationWarnings != null && !validationWarnings.isEmpty()) {
            builder.append("\n\n=== LIGHTWEIGHT VALIDATION WARNINGS ===\n");
            validationWarnings.forEach(warning -> builder.append("- ").append(warning).append('\n'));
        }
        return builder.toString();
    }

    private String classifyCompilationErrors(String executionResult, List<SourceCode> codes) {
        // 这里不是严格的编译器分析器，只是把项目里高频出现的错误模式做成提示。
        List<String> hints = new ArrayList<>();

        boolean hasJUnitMissing = executionResult.contains("package org.junit.jupiter")
                || executionResult.contains("package org.mockito")
                || executionResult.contains("org.junit.jupiter.api does not exist")
                || executionResult.contains("org.mockito does not exist");
        if (hasJUnitMissing) {
            List<String> misplacedTests = findMisplacedTestFiles(codes);
            if (!misplacedTests.isEmpty()) {
                hints.add("Tests appear to be under src/main/java instead of src/test/java: " + String.join(", ", misplacedTests));
            } else {
                hints.add("Test dependency issue detected. Check whether test classes are under src/test/java.");
            }
        }

        boolean hasMethodBodyIssue = executionResult.contains("missing method body")
                || executionResult.contains("or declare abstract")
                || executionResult.contains("abstract methods")
                || executionResult.contains("is not abstract and does not override abstract method");
        if (hasMethodBodyIssue) {
            hints.add("A non-abstract method or constructor is declared without an implementation body.");
        }

        boolean missingList = executionResult.contains("symbol:   class List")
                || executionResult.contains("symbol: class List")
                || executionResult.contains("cannot find symbol") && (executionResult.contains(" List ") || executionResult.contains("List<"));
        if (missingList) {
            hints.add("Missing import likely: import java.util.List;");
        }

        if (executionResult.contains("symbol:   class ResponseEntity") || executionResult.contains("symbol: class ResponseEntity")) {
            hints.add("Missing import likely: import org.springframework.http.ResponseEntity;");
        }
        if (executionResult.contains("symbol:   class RequestParam") || executionResult.contains("symbol: class RequestParam")) {
            hints.add("Missing import likely: import org.springframework.web.bind.annotation.RequestParam;");
        }
        if (executionResult.contains("symbol:   class PathVariable") || executionResult.contains("symbol: class PathVariable")) {
            hints.add("Missing import likely: import org.springframework.web.bind.annotation.PathVariable;");
        }

        return String.join("\n", hints);
    }

    private List<String> findMisplacedTestFiles(List<SourceCode> codes) {
        // 一些测试文件被错误放进 src/main/java 时，会引发一串看起来很乱的依赖错误。
        // 这里提前把这种模式抽出来，方便修复 prompt 更精准。
        List<String> misplacedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            String normalizedFilename = normalizeGeneratedFilename(code.filename(), code.code()).replace("\\", "/");
            boolean looksLikeTest = normalizedFilename.endsWith("Test.java")
                    || (code.code() != null && (code.code().contains("org.junit.jupiter") || code.code().contains("@Test")));
            if (looksLikeTest && normalizedFilename.startsWith("src/main/java/")) {
                misplacedFiles.add(normalizedFilename);
            }
        }
        return misplacedFiles;
    }

    private String detectLanguageFromFilename(String filename) {
        String lower = filename == null ? "text" : filename.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".properties")) {
            return "properties";
        }
        if (lower.endsWith(".html")) {
            return "html";
        }
        if (lower.endsWith(".css")) {
            return "css";
        }
        if (lower.endsWith(".js")) {
            return "js";
        }
        return "text";
    }

    static class WorkflowGraphState extends AgentState {
        static final String DATA_KEY = "workflowData";
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                DATA_KEY, Channels.base(() -> new WorkflowData()));

        public WorkflowGraphState(Map<String, Object> initData) {
            super(initData);
        }

        public WorkflowData workflowData() {
            return this.<WorkflowData>value(DATA_KEY).orElseGet(WorkflowData::new);
        }
    }

    static class WorkflowData implements Serializable {
        private static final long serialVersionUID = 1L;

        // 这个内部状态类就是整张 LangGraph 图共享的“真状态”。
        // 你后面如果要加新节点，通常也是在这里补字段，然后让节点读写它。
        String userRequest = "";
        int maxRetries;
        List<SourceCode> codes = new ArrayList<>();
        List<String> validationWarnings = new ArrayList<>();
        PrdDocument prd;
        ProjectStructure structure;
        GenerationPlan generationPlan = new GenerationPlan(List.of());
        int currentBatchIndex;
        // 这里放进 LangGraph state 的对象都会被序列化，路径统一存字符串更稳妥。
        String projectPath;
        String executionResult;
        String testResult;
        String pendingFixLog;
        String pendingErrorType;
        boolean success;
        boolean shouldFix;
        int currentAttempt = 1;

        WorkflowData() {
        }

        WorkflowData(String userRequest, int maxRetries) {
            this.userRequest = userRequest;
            this.maxRetries = maxRetries;
        }

        boolean hasMoreBatches() {
            // 是否还有下一批待生成文件，供条件边决定要不要继续回到 generate_batch。
            return generationPlan != null
                    && generationPlan.batches() != null
                    && currentBatchIndex < generationPlan.batches().size();
        }

        GenerationBatch currentBatch() {
            // 读取当前批次时不额外推进索引，索引推进统一放在 validate_batch 后面。
            if (!hasMoreBatches()) {
                return null;
            }
            return generationPlan.batches().get(currentBatchIndex);
        }
    }

    private String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
