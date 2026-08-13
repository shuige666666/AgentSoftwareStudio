package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final SliceProductionScopeService sliceProductionScopeService;

    /**
     * 注入批次代码生成所需的 Agent 和上下文辅助服务。
     */
    public BatchGenerationService(DeveloperAgent developerAgent,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService,
            SliceProductionScopeService sliceProductionScopeService) {
        this.developerAgent = developerAgent;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
        this.sliceProductionScopeService = sliceProductionScopeService;
    }

    /**
     * 生成当前批次内的全部源文件
     */
    public void generateBatch(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        GenerationBatch batch = data.currentBatch();
        if (batch == null) {
            return;
        }

        generate(data, batch, data.contract, data.structure, logger);
    }

    /**
     * 生成当前垂直切片内的生产文件；已验收切片代码作为只读依赖上下文继续可见。
     */
    public void generateSlice(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        DeliverySlice slice = data.currentSlice();
        if (slice == null) {
            return;
        }
        generate(data, slice.asGenerationBatch(), slice.contract(), sliceProductionScopeService.activeStructure(data), logger);
    }

    private void generate(
            SoftwareStudioWorkflowData data,
            GenerationBatch batch,
            ProjectContract activeContract,
            ProjectStructure activeStructure,
            Consumer<String> logger) {

        logger.accept("4." + (data.currentSlice() == null ? data.currentBatchIndex + 1 : data.currentSliceIndex + 1)
                + " Generating delivery unit `" + batch.name() + "` (" + batch.layer() + ").");
        String batchContext = describeBatch(batch);

        // 同一交付单元先按显式依赖拆成波次，波次内再做受限并发。
        // 这样既保留并发收益，也能让下游文件看到上游已经生成的真实代码。
        List<FileBlueprint> batchFiles = batch.files().stream()
                .sorted(Comparator.comparing(FileBlueprint::targetPath))
                .toList();
        int concurrency = Math.max(1, Math.min(MAX_BATCH_LLM_CONCURRENCY, batchFiles.size()));
        ExecutorService batchExecutor = Executors.newFixedThreadPool(concurrency);
        try {
            logger.accept("   Batch LLM concurrency: " + concurrency);
            List<List<FileBlueprint>> waves = dependencyWaves(batchFiles);
            for (int waveIndex = 0; waveIndex < waves.size(); waveIndex++) {
                List<FileBlueprint> wave = waves.get(waveIndex);
                logger.accept("   Dependency wave " + (waveIndex + 1) + ": " + wave.size() + " file(s).");
                // 每一波都重新快照，确保上游实体、服务等真实代码会进入下游文件 Prompt。
                List<SourceCode> codeSnapshot = new ArrayList<>(data.codes);
                List<CompletableFuture<SourceCode>> futures = wave.stream()
                        .map(fileBlueprint -> CompletableFuture.supplyAsync(
                                () -> {
                                    String relevantCodeContext = codeContextBuilderService.buildRelevantCodeContext(
                                            codeSnapshot, activeStructure, fileBlueprint);
                                    return generateSourceFile(
                                            data.prd, activeStructure, relevantCodeContext, batchContext,
                                            fileBlueprint, activeContract);
                                },
                                batchExecutor))
                        .toList();
                List<SourceCode> generatedFiles = futures.stream().map(CompletableFuture::join).toList();
                for (int i = 0; i < wave.size(); i++) {
                    FileBlueprint blueprint = wave.get(i);
                    SourceCode generatedFile = generatedFiles.get(i);
                    sourceCodePathService.upsertSourceCode(data.codes, generatedFile.filename(), generatedFile.code());
                    logger.accept("   Generated: " + blueprint.targetPath());
                }
            }
        } finally {
            batchExecutor.shutdown();
        }
    }

    /**
     * 将同一垂直切片按显式文件依赖拆成拓扑波次；循环依赖安全退化为最后一波。
     */
    private List<List<FileBlueprint>> dependencyWaves(List<FileBlueprint> files) {
        List<FileBlueprint> remaining = new ArrayList<>(files);
        Set<String> slicePaths = files.stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> completed = new LinkedHashSet<>();
        List<List<FileBlueprint>> waves = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<FileBlueprint> dependencyReady = remaining.stream()
                    .filter(file -> file.dependsOn().stream()
                            .map(sourceCodePathService::normalizePath)
                            .filter(slicePaths::contains)
                            .allMatch(completed::contains))
                    .toList();
            if (dependencyReady.isEmpty()) {
                dependencyReady = List.copyOf(remaining);
            }
            // 架构师未填写 dependsOn 时，用稳定的层级顺序补足最基本的依赖关系。
            int nextLayer = dependencyReady.stream().mapToInt(this::layerOrder).min().orElse(0);
            List<FileBlueprint> ready = dependencyReady.stream()
                    .filter(file -> layerOrder(file) == nextLayer)
                    .toList();
            waves.add(ready);
            ready.forEach(file -> completed.add(sourceCodePathService.normalizePath(file.targetPath())));
            remaining.removeAll(ready);
        }
        return List.copyOf(waves);
    }

    private int layerOrder(FileBlueprint file) {
        return switch (file.effectiveLayer()) {
            case "repository", "persistence" -> 1;
            case "service" -> 2;
            case "controller", "api" -> 3;
            case "frontend", "view", "template" -> 4;
            default -> 0;
        };
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
