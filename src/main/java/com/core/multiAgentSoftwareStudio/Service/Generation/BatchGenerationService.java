package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 负责按批次并发调用开发 Agent 生成源码，并把生成结果合并回工作流状态。
 */
@Service
public class BatchGenerationService {
    private static final int MAX_BATCH_LLM_CONCURRENCY = 2;

    private final DeveloperAgent developerAgent;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;

    /**
     * 注入批次代码生成所需的 Agent 和上下文辅助服务。
     */
    public BatchGenerationService(DeveloperAgent developerAgent,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService) {
        this.developerAgent = developerAgent;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 生成当前批次内的全部源文件
     */
    public void generateBatch(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        GenerationBatch batch = data.currentBatch();
        if (batch == null) {
            return;
        }

        logger.accept("4." + (data.currentBatchIndex + 1) + " Generating batch `" + batch.name() + "` (" + batch.layer()
                + ").");
        String batchContext = describeBatch(batch);

        // 同一批次内的文件，默认认为依赖关系已经足够松，可以并发生成。
        // 批次之间的先后顺序，已经在 plan_batches 阶段提前处理好了。
        // 这样做的目的，是把“并发收益”放在文件生成阶段，而不是把复杂性堆到运行时调度里。
        List<FileBlueprint> batchFiles = batch.files().stream()
                .sorted(Comparator.comparing(FileBlueprint::targetPath))
                .toList();
        int concurrency = Math.max(1, Math.min(MAX_BATCH_LLM_CONCURRENCY, batchFiles.size()));
        ExecutorService batchExecutor = Executors.newFixedThreadPool(concurrency);
        try {
            logger.accept("   Batch LLM concurrency: " + concurrency);
            List<SourceCode> codeSnapshot = new ArrayList<>(data.codes);
            List<CompletableFuture<SourceCode>> futures = batchFiles.stream()
                    .map(fileBlueprint -> CompletableFuture.supplyAsync(
                            () -> {
                                String relevantCodeContext = codeContextBuilderService.buildRelevantCodeContext(
                                        codeSnapshot, data.structure, fileBlueprint);
                                return generateSourceFile(data.prd, data.structure, relevantCodeContext, batchContext,
                                        fileBlueprint, data.contract);
                            },
                            batchExecutor))
                    .toList();

            List<SourceCode> generatedFiles = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            for (int i = 0; i < batchFiles.size(); i++) {
                FileBlueprint blueprint = batchFiles.get(i);
                SourceCode generatedFile = generatedFiles.get(i);
                sourceCodePathService.upsertSourceCode(data.codes, generatedFile.filename(), generatedFile.code());
                logger.accept("   Generated: " + blueprint.targetPath());
            }
        } finally {
            batchExecutor.shutdown();
        }
    }

    /**
     * 按蓝图为单个目标文件生成源码
     */
    private SourceCode generateSourceFile(PrdDocument prd,
            ProjectStructure structure,
            String existingCodeContext,
            String batchContext,
            FileBlueprint blueprint,
            ProjectContract contract) {
        // 这里对 DeveloperAgent 的输入做了一层统一包装：
        // 当前文件路径、文件职责、方法要求、批次上下文、已有代码上下文都会一起给过去。
        // 这样单文件生成时，模型仍然能感知自己处在整个项目的哪一层。
        String targetPath = blueprint.targetPath();
        String methods = blueprint.keyMethods().isEmpty() ? "No specific methods provided."
                : blueprint.keyMethods().toString();

        SourceCode rawResult = developerAgent.writeCode(
                prd,
                structure,
                contract,
                existingCodeContext,
                targetPath,
                blueprint.functionalityDescription(),
                methods,
                batchContext);

        String language = rawResult != null && rawResult.language() != null
                ? rawResult.language()
                : sourceCodePathService.detectLanguageFromFilename(targetPath);
        String code = rawResult == null || rawResult.code() == null ? "" : rawResult.code();
        return new SourceCode(targetPath, language, code);
    }

    /**
     * 构建当前批次的文字描述，供代码生成节点参考
     */
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
}
