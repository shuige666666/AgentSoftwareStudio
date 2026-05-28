package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批次规划
 */
@Service
public class BatchPlanningService {

    private final SourceCodePathService sourceCodePathService;

    public BatchPlanningService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    private static final Map<String, Integer> LAYER_PRIORITY = Map.ofEntries(
            Map.entry("application", 10),
            Map.entry("base", 10),
            Map.entry("model", 10),
            Map.entry("entity", 10),
            Map.entry("dto", 10),
            Map.entry("vo", 10),
            Map.entry("constant", 10),
            Map.entry("common", 10),

            Map.entry("config", 15),
            Map.entry("configuration", 15),

            Map.entry("repository", 30),
            Map.entry("mapper", 30),
            Map.entry("service", 40),
            Map.entry("controller", 50),
            Map.entry("frontend", 60),
            Map.entry("test", 70)
    );

    /**
     * 根据架构蓝图生成可执行的批次计划
     */
    public GenerationPlan createPlan(ProjectStructure structure) {
        if (structure == null || structure.files() == null || structure.files().isEmpty()) {
            return new GenerationPlan(List.of());
        }

        // 先统一排序，保证同样的蓝图输入每次都能得到稳定的批次顺序。
        Map<String, List<FileBlueprint>> grouped = new LinkedHashMap<>();
        Map<String, String> groupLayers = new HashMap<>();

        List<FileBlueprint> sortedFiles = structure.files().stream()
                .sorted(Comparator
                        .comparingInt((FileBlueprint file) -> layerPriority(file.effectiveLayer()))
                        .thenComparing(FileBlueprint::targetPath))
                .toList();

        for (FileBlueprint file : sortedFiles) {
            String groupKey = (file.batchName() != null && !file.batchName().isBlank())
                    ? file.batchName().trim()
                    : file.effectiveLayer();
            grouped.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(file);
            groupLayers.putIfAbsent(groupKey, file.effectiveLayer());
        }

        // 如果架构师显式给了 batchName，就优先相信它；
        // 否则退回到按 layer 分组，这是默认的批次划分策略。
        List<GenerationBatch> batches = grouped.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, List<FileBlueprint>> entry) -> layerPriority(groupLayers.get(entry.getKey())))
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new GenerationBatch(
                        entry.getKey(),
                        groupLayers.getOrDefault(entry.getKey(), "base"),
                        sortWithinBatch(entry.getValue())))
                .toList();

        return new GenerationPlan(batches);
    }

    /**
     * 在单个批次内部按照依赖关系做稳定排序
     */
    private List<FileBlueprint> sortWithinBatch(List<FileBlueprint> files) {
        // 同一批次内部，再做一层轻量依赖排序。
        // 目的是尽量让“基础文件在前，依赖它们的文件在后”，
        // 即使最终这批文件会并发生成，也能让批次描述更合理。
        Map<String, FileBlueprint> byPath = files.stream()
                .collect(Collectors.toMap(file -> sourceCodePathService.normalizePath(file.targetPath()), file -> file, (left, right) -> left, LinkedHashMap::new));
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (FileBlueprint file : files) {
            String current = sourceCodePathService.normalizePath(file.targetPath());
            indegree.putIfAbsent(current, 0);
            for (String dependency : file.dependsOn()) {
                String normalizedDependency = sourceCodePathService.normalizePath(dependency);
                if (byPath.containsKey(normalizedDependency)) {
                    adjacency.computeIfAbsent(normalizedDependency, key -> new ArrayList<>()).add(current);
                    indegree.merge(current, 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(queue::offer);

        List<FileBlueprint> ordered = new ArrayList<>();
        // 这里用的是非常轻量的拓扑排序，只处理当前批次内部能看见的依赖关系。
        // 不追求做成完整调度器，够稳定、够便宜就行。
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ordered.add(byPath.get(current));
            for (String next : adjacency.getOrDefault(current, List.of())) {
                int updated = indegree.merge(next, -1, Integer::sum);
                if (updated == 0) {
                    queue.offer(next);
                }
            }
        }

        if (ordered.size() == files.size()) {
            return ordered;
        }

        // 如果依赖信息不完整，或者出现环，直接退回到稳定顺序。
        // 这里宁愿“保守可运行”，也不要因为规划阶段过严导致整个流程中断。
        Set<String> emitted = ordered.stream()
                .map(file -> sourceCodePathService.normalizePath(file.targetPath()))
                .collect(Collectors.toSet());
        files.stream()
                .filter(file -> !emitted.contains(sourceCodePathService.normalizePath(file.targetPath())))
                .forEach(ordered::add);
        return ordered;
    }

    /**
     * 计算层级优先级，数值越小越优先生成
     */
    private int layerPriority(String layer) {
        // 数字越小越先生成。
        return LAYER_PRIORITY.getOrDefault(layer == null ? "base" : layer.trim().toLowerCase(), 99);
    }
}
