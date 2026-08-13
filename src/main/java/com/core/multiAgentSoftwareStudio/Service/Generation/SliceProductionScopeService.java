package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 限定当前切片可见的生产蓝图，并检测源码是否越界引用尚未交付的 Java 类型。
 */
@Service
public class SliceProductionScopeService {

    private static final Pattern COMMENTS_AND_LITERALS = Pattern.compile(
            "(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    private final SourceCodePathService sourceCodePathService;

    public SliceProductionScopeService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 生成当前切片时只暴露已生成文件和本切片蓝图，防止模型主动耦合未来切片。
     */
    public ProjectStructure activeStructure(SoftwareStudioWorkflowData data) {
        if (data == null || data.structure == null || data.finalVerificationStarted || data.currentSlice() == null) {
            return data == null ? null : data.structure;
        }
        Set<String> visiblePaths = availableProductionPaths(data);
        data.currentSlice().files().stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .forEach(visiblePaths::add);
        List<FileBlueprint> visibleFiles = data.structure.files().stream()
                .filter(file -> visiblePaths.contains(sourceCodePathService.normalizePath(file.targetPath())))
                .toList();
        return new ProjectStructure(
                data.structure.rootPackage(), data.structure.projectType(), data.structure.mainClassName(), visibleFiles);
    }

    /**
     * 返回当前切片生产文件中对未来 Java 类型的引用证据，供测试短路和前置门禁共用。
     */
    public List<String> findFutureTypeReferences(SoftwareStudioWorkflowData data) {
        if (data == null || data.structure == null || data.finalVerificationStarted || data.currentSlice() == null) {
            return List.of();
        }
        Set<String> availablePaths = availableProductionPaths(data);
        List<FileBlueprint> futureJavaFiles = data.structure.files().stream()
                .filter(file -> !availablePaths.contains(sourceCodePathService.normalizePath(file.targetPath())))
                .filter(file -> sourceCodePathService.normalizePath(file.targetPath()).endsWith(".java"))
                .toList();
        Set<String> currentPaths = data.currentSlice().files().stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> findings = new LinkedHashSet<>();
        for (SourceCode source : data.codes) {
            if (source == null || source.filename() == null || source.code() == null) {
                continue;
            }
            String sourcePath = sourceCodePathService.normalizePath(source.filename());
            if (!currentPaths.contains(sourcePath) || !sourcePath.endsWith(".java")) {
                continue;
            }
            String codeWithoutCommentsAndLiterals = COMMENTS_AND_LITERALS.matcher(source.code()).replaceAll(" ");
            for (FileBlueprint future : futureJavaFiles) {
                String futurePath = sourceCodePathService.normalizePath(future.targetPath());
                String typeName = Path.of(futurePath).getFileName().toString().replaceFirst("\\.java$", "");
                if (Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b")
                        .matcher(codeWithoutCommentsAndLiterals).find()) {
                    findings.add(sourcePath + " references future type " + typeName + " from " + futurePath);
                }
            }
        }
        return List.copyOf(findings);
    }

    private Set<String> availableProductionPaths(SoftwareStudioWorkflowData data) {
        Set<String> paths = new LinkedHashSet<>();
        data.codes.stream()
                .filter(source -> source != null && source.filename() != null)
                .map(SourceCode::filename)
                .map(sourceCodePathService::normalizePath)
                .filter(path -> !path.toLowerCase(Locale.ROOT).startsWith("src/test/"))
                .forEach(paths::add);
        return paths;
    }
}
