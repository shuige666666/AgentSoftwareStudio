package com.core.multiAgentSoftwareStudio.Service.Context;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 负责为测试、前端审查、单文件生成和修复阶段构建源码上下文文本。
 */
@Service
public class CodeContextBuilderService {

    private final SourceCodePathService sourceCodePathService;

    /**
     * 注入源码路径规范化服务，保证上下文中的文件路径口径一致。
     */
    public CodeContextBuilderService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 将当前全部代码拼接成完整上下文文本
     */
    public String buildCodeContextForTester(List<SourceCode> codes) {
        // 全量上下文主要给测试生成和某些需要“全局视角”的修复场景使用。
        StringBuilder builder = new StringBuilder();
        for (SourceCode code : codes) {
            builder.append("--- File: ").append(code.filename()).append(" ---\n");
            builder.append(safeCode(code)).append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 为单个开发任务构建依赖相关的已实现代码上下文，避免把无关文件全部塞进 DeveloperAgent。
     */
    public String buildRelevantCodeContext(List<SourceCode> codes, ProjectStructure structure, FileBlueprint blueprint) {
        if (codes == null || codes.isEmpty() || blueprint == null) {
            return "No implemented dependency files are available yet.";
        }

        // 第一步：根据架构蓝图递归收集当前文件的直接和间接依赖路径。
        Set<String> dependencyPaths = collectDependencyClosure(structure, blueprint);
        if (dependencyPaths.isEmpty()) {
            return "This file has no implemented dependency files yet.";
        }

        // 第二步：从已经生成的代码中挑出依赖闭包命中的文件，按依赖发现顺序拼接。
        StringBuilder builder = new StringBuilder();
        for (String dependencyPath : dependencyPaths) {
            SourceCode dependencyCode = findGeneratedCodeByPath(codes, dependencyPath);
            if (dependencyCode == null) {
                continue;
            }
            builder.append("--- File: ").append(normalizeDependencyPath(dependencyCode.filename())).append(" ---\n");
            builder.append(safeCode(dependencyCode)).append("\n\n");
        }

        if (builder.isEmpty()) {
            return "Declared dependency files exist in the blueprint, but none have been implemented yet.";
        }
        return builder.toString();
    }

    /**
     * 递归收集当前文件依赖的完整闭包，让后续文件能看到前置文件以及前置文件依赖的基础类型。
     */
    private Set<String> collectDependencyClosure(ProjectStructure structure, FileBlueprint blueprint) {
        Map<String, FileBlueprint> blueprintIndex = buildBlueprintIndex(structure);
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> pending = new ArrayDeque<>(blueprint.dependsOn());

        while (!pending.isEmpty()) {
            String dependencyPath = normalizeDependencyPath(pending.poll());
            if (dependencyPath.isBlank() || visited.contains(dependencyPath)) {
                continue;
            }
            visited.add(dependencyPath);

            // 如果依赖本身也有 dependsOn，继续向下展开，形成依赖闭包。
            FileBlueprint dependencyBlueprint = blueprintIndex.get(dependencyPath);
            if (dependencyBlueprint == null) {
                dependencyBlueprint = blueprintIndex.get(fileNameOnly(dependencyPath));
            }
            if (dependencyBlueprint != null) {
                for (String nestedDependency : dependencyBlueprint.dependsOn()) {
                    String normalizedNestedDependency = normalizeDependencyPath(nestedDependency);
                    if (!visited.contains(normalizedNestedDependency)) {
                        pending.offer(normalizedNestedDependency);
                    }
                }
            }
        }
        return visited;
    }

    /**
     * 构建文件蓝图索引，支持用完整项目路径或单独文件名匹配 dependsOn 中的依赖声明。
     */
    private Map<String, FileBlueprint> buildBlueprintIndex(ProjectStructure structure) {
        Map<String, FileBlueprint> index = new LinkedHashMap<>();
        if (structure == null || structure.files() == null) {
            return index;
        }

        for (FileBlueprint file : structure.files()) {
            String targetPath = normalizeDependencyPath(file.targetPath());
            index.putIfAbsent(targetPath, file);
            index.putIfAbsent(fileNameOnly(targetPath), file);
        }
        return index;
    }

    /**
     * 按依赖路径查找已经生成的源码，优先完整路径匹配，兜底使用文件名匹配。
     */
    private SourceCode findGeneratedCodeByPath(List<SourceCode> codes, String dependencyPath) {
        String normalizedDependency = normalizeDependencyPath(dependencyPath);
        String dependencyFileName = fileNameOnly(normalizedDependency);

        for (SourceCode code : codes) {
            String normalizedFilename = normalizeDependencyPath(sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code()));
            if (normalizedFilename.equals(normalizedDependency)) {
                return code;
            }
        }

        // 有些架构蓝图里的 dependsOn 可能只写类名或文件名，这里做一次宽松匹配。
        for (SourceCode code : codes) {
            String normalizedFilename = normalizeDependencyPath(sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code()));
            if (fileNameOnly(normalizedFilename).equals(dependencyFileName)) {
                return code;
            }
        }
        return null;
    }

    /**
     * 统一依赖路径格式，便于蓝图路径和生成结果路径之间做稳定匹配。
     */
    private String normalizeDependencyPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return sourceCodePathService.normalizePath(path);
    }

    /**
     * 提取路径中的文件名，用作 dependsOn 没有写完整路径时的兜底匹配键。
     */
    private String fileNameOnly(String path) {
        String normalized = normalizeDependencyPath(path);
        if (normalized.isBlank()) {
            return "";
        }
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    public String buildFrontendContext(List<SourceCode> codes) {
        StringBuilder builder = new StringBuilder();
        for (SourceCode code : codes) {
            if (!sourceCodePathService.isFrontendFile(code.filename())) {
                continue;
            }
            builder.append("--- File: ").append(code.filename()).append(" ---\n");
            builder.append(safeCode(code)).append("\n\n");
        }
        return builder.toString();
    }

    public String buildContractContext(ProjectContract contract) {
        if (contract == null) {
            return "";
        }
        return "=== PROJECT CONTRACT ===\n" + contract + "\n\n";
    }

    /**
     * 为修复阶段构建尽量精简但足够有效的代码上下文
     */
    public String buildOptimizedCodeContext(List<SourceCode> codes, String executionResult, String errorType) {
        // 优先只给“报错关联文件”的完整代码，其他文件给摘要。
        // 如果是逻辑错误/测试失败，则直接退回全量上下文，因为这类问题经常跨文件。
        List<String> relatedFiles = new java.util.ArrayList<>();
        for (SourceCode code : codes) {
            Path path = Path.of(sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code()));
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
                builder.append(safeCode(code)).append("\n\n");
            } else {
                builder.append("--- File: ").append(code.filename()).append(" (Summary) ---\n");
                builder.append(extractSummary(safeCode(code))).append("\n\n");
            }
        }
        return builder.toString();
    }

    /**
     * 从完整源码中提取简要摘要，压缩修复上下文
     */
    private String extractSummary(String code) {
        // 摘要策略尽量简单：只保留 package、public 类型声明、public 方法签名。
        // 目标不是完全还原代码，而是让修复阶段知道项目大致轮廓。
        if (code == null || code.isBlank()) {
            return "// [No summary available]";
        }
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

    private String safeCode(SourceCode code) {
        return code == null || code.code() == null ? "" : code.code();
    }
}
