package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Model.Workflow.TestScopeViolation;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为切片测试提供可见架构，并阻止测试提前引用尚未交付的类型或资源。
 */
@Service
public class SliceTestScopeService {

    private static final Pattern CLASSPATH_RESOURCE = Pattern.compile(
            "(?:ClassPathResource\\s*\\(\\s*|getResource\\s*\\(\\s*)[\"'](?:classpath:)?/?([^\"']+)[\"']");
    private static final Pattern SOURCE_RESOURCE = Pattern.compile(
            "[\"'](src/main/resources/[^\"']+)[\"']");
    private static final Pattern COMMENTS_AND_LITERALS = Pattern.compile(
            "(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    private final SourceCodePathService sourceCodePathService;

    public SliceTestScopeService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 测试 Agent 只看到已经生成的生产文件蓝图，避免根据未来文件设计当前切片测试。
     */
    public ProjectStructure activeStructure(SoftwareStudioWorkflowData data) {
        if (data == null || data.structure == null || data.finalVerificationStarted || data.currentSlice() == null) {
            return data == null ? null : data.structure;
        }
        Set<String> availablePaths = availableProductionPaths(data);
        List<FileBlueprint> visibleFiles = data.structure.files().stream()
                .filter(file -> availablePaths.contains(sourceCodePathService.normalizePath(file.targetPath())))
                .toList();
        return new ProjectStructure(
                data.structure.rootPackage(), data.structure.projectType(), data.structure.mainClassName(), visibleFiles);
    }

    /**
     * 返回测试是否只引用当前切片、共享文件和已接受切片已经存在的生产产物。
     */
    public boolean isInScope(SoftwareStudioWorkflowData data, SourceCode testFile) {
        return validate(data, testFile) == TestScopeViolation.NONE;
    }

    /**
     * 返回测试越界的稳定分类；类型检查忽略注释和字符串，资源检查仍读取字符串路径。
     */
    public TestScopeViolation validate(SoftwareStudioWorkflowData data, SourceCode testFile) {
        if (data == null || data.structure == null || data.finalVerificationStarted || data.currentSlice() == null
                || testFile == null) {
            return TestScopeViolation.NONE;
        }
        String code = testFile.code() == null ? "" : testFile.code();
        String codeWithoutCommentsAndLiterals = COMMENTS_AND_LITERALS.matcher(code).replaceAll(" ");
        Set<String> availablePaths = availableProductionPaths(data);
        List<FileBlueprint> futureFiles = data.structure.files().stream()
                .filter(file -> !availablePaths.contains(sourceCodePathService.normalizePath(file.targetPath())))
                .toList();

        for (FileBlueprint future : futureFiles) {
            String path = sourceCodePathService.normalizePath(future.targetPath());
            if (path.endsWith(".java")) {
                String typeName = Path.of(path).getFileName().toString().replaceFirst("\\.java$", "");
                if (Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b")
                        .matcher(codeWithoutCommentsAndLiterals).find()) {
                    return TestScopeViolation.FUTURE_TYPE;
                }
            }
        }

        Matcher classpathMatcher = CLASSPATH_RESOURCE.matcher(code);
        while (classpathMatcher.find()) {
            String referenced = "src/main/resources/" + classpathMatcher.group(1).replace('\\', '/');
            if (declaredButUnavailable(futureFiles, referenced)) {
                return TestScopeViolation.FUTURE_RESOURCE;
            }
        }
        Matcher sourceMatcher = SOURCE_RESOURCE.matcher(code);
        while (sourceMatcher.find()) {
            if (declaredButUnavailable(futureFiles, sourceMatcher.group(1))) {
                return TestScopeViolation.FUTURE_RESOURCE;
            }
        }
        return TestScopeViolation.NONE;
    }

    private Set<String> availableProductionPaths(SoftwareStudioWorkflowData data) {
        return data.codes.stream()
                .filter(source -> source != null && source.filename() != null)
                .map(SourceCode::filename)
                .map(sourceCodePathService::normalizePath)
                .filter(path -> !path.toLowerCase(Locale.ROOT).startsWith("src/test/"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean declaredButUnavailable(List<FileBlueprint> futureFiles, String referencedPath) {
        String normalized = sourceCodePathService.normalizePath(referencedPath);
        return futureFiles.stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .anyMatch(normalized::equals);
    }
}
