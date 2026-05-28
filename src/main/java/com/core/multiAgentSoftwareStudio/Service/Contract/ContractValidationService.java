package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ViewContract;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验生成代码中的路径、后端接口、视图模板和前后端调用契约
 */
@Service
public class ContractValidationService {

    private final SourceCodePathService sourceCodePathService;

    public ContractValidationService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile("\\bpublic\\s+(class|interface|record|enum)\\s+([A-Za-z0-9_]+)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MVC_VIEW_RETURN_PATTERN = Pattern.compile("\\breturn\\s+\"([A-Za-z0-9_./-]+)\"\\s*;");
    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(?:\\(\\s*)?(?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern FRONTEND_REQUEST_PATTERN = Pattern.compile("\\b(?:fetch|axios\\.(?:get|post|put|delete|patch))\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    /**
     * 对当前批次的生成结果做轻量契约校验
     */
    public List<String> validateBatch(GenerationBatch batch, List<SourceCode> generatedFiles) {
        List<String> warnings = new ArrayList<>();
        if (batch == null || batch.files().isEmpty()) {
            return warnings;
        }

        Map<String, SourceCode> fileIndex = new LinkedHashMap<>();
        for (SourceCode generatedFile : generatedFiles) {
            fileIndex.put(sourceCodePathService.normalizePath(generatedFile.filename()), generatedFile);
        }

        for (FileBlueprint blueprint : batch.files()) {
            SourceCode generated = findMatchingFile(fileIndex, sourceCodePathService.normalizePath(blueprint.targetPath()));
            if (generated == null) {
                warnings.add("Missing generated file for blueprint: " + blueprint.targetPath());
                continue;
            }

            String code = generated.code() == null ? "" : generated.code();
            if (containsPlaceholder(code)) {
                warnings.add("Placeholder implementation detected in " + generated.filename());
            }

            if (generated.filename().endsWith(".java")) {
                validatePackage(generated.filename(), code, warnings);
                validatePublicType(generated.filename(), code, warnings);
                validateLayerHints(blueprint, generated.filename(), code, warnings);
            }
        }
        return warnings;
    }

    /**
     * 对完整项目做跨文件契约校验，补上单个生成批次看不到的问题
     */
    public List<String> validateProject(List<SourceCode> generatedFiles, ProjectContract contract) {
        List<String> warnings = new ArrayList<>();
        if (generatedFiles == null || generatedFiles.isEmpty()) {
            return warnings;
        }

        Map<String, SourceCode> javaTypes = new LinkedHashMap<>();
        List<SourceCode> mainJavaFiles = new ArrayList<>();
        List<SourceCode> frontendFiles = new ArrayList<>();
        List<String> templateNames = collectTemplateNames(generatedFiles);
        List<String> backendEndpoints = new ArrayList<>();

        for (SourceCode file : generatedFiles) {
            String filename = sourceCodePathService.normalizePath(file.filename());
            String code = file.code() == null ? "" : file.code();

            if (filename.endsWith(".java")) {
                validatePackage(filename, code, warnings);
                validatePublicType(filename, code, warnings);
                indexJavaType(filename, code, javaTypes);
                if (filename.startsWith("src/main/java/")) {
                    mainJavaFiles.add(file);
                    collectControllerEndpoints(code, backendEndpoints);
                    validateMissingMvcTemplates(filename, code, templateNames, warnings);
                }
            }

            if (isFrontendAsset(filename)) {
                frontendFiles.add(file);
            }
        }

        validateMainDoesNotDependOnTest(mainJavaFiles, javaTypes, warnings);
        validateFrontendRequests(frontendFiles, backendEndpoints, warnings);
        validateProjectContract(contract, generatedFiles, templateNames, backendEndpoints, warnings);
        return warnings;
    }

    /**
     * 根据目标路径在生成结果中查找对应文件
     */
    private SourceCode findMatchingFile(Map<String, SourceCode> fileIndex, String expectedPath) {
        SourceCode directMatch = fileIndex.get(expectedPath);
        if (directMatch != null) {
            return directMatch;
        }

        String expectedName = Path.of(expectedPath).getFileName().toString();
        return fileIndex.values().stream()
                .filter(source -> Path.of(sourceCodePathService.normalizePath(source.filename())).getFileName().toString().equals(expectedName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验 Java 文件的 package 声明是否与文件路径严格一致
     */
    private void validatePackage(String filename, String code, List<String> warnings) {
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        if (!matcher.find()) {
            warnings.add("Missing package declaration in " + filename);
            return;
        }

        String declaredPackagePath = matcher.group(1).replace('.', '/');
        String normalized = sourceCodePathService.normalizePath(filename);
        if (normalized.startsWith("src/main/java/")) {
            validatePackageAgainstSourceRoot(normalized, "src/main/java/", declaredPackagePath, matcher.group(1), warnings);
        } else if (normalized.startsWith("src/test/java/")) {
            validatePackageAgainstSourceRoot(normalized, "src/test/java/", declaredPackagePath, matcher.group(1), warnings);
        }
    }

    /**
     * 校验指定源码根目录下的 package 路径是否和实际目录一致
     */
    private void validatePackageAgainstSourceRoot(String filename,
            String sourceRoot,
            String declaredPackagePath,
            String declaredPackage,
            List<String> warnings) {
        String expectedPath = filename.substring(sourceRoot.length());
        String expectedFullPath = declaredPackagePath + "/" + Path.of(filename).getFileName();
        if (!expectedPath.equals(expectedFullPath)) {
            warnings.add("Package/path mismatch in " + filename + ": package " + declaredPackage);
        }
    }

    /**
     * 校验 public 类型名称是否与文件名一致
     */
    private void validatePublicType(String filename, String code, List<String> warnings) {
        Matcher matcher = PUBLIC_TYPE_PATTERN.matcher(code);
        if (!matcher.find()) {
            warnings.add("No public type declaration found in " + filename);
            return;
        }

        String expectedName = Path.of(sourceCodePathService.normalizePath(filename)).getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String actualName = matcher.group(2);
        if (!expectedName.equals(actualName)) {
            warnings.add("Public type name mismatch in " + filename + ": expected " + expectedName + " but found " + actualName);
        }
    }

    /**
     * 根据文件层级检查常见 Spring 注解是否缺失
     */
    private void validateLayerHints(FileBlueprint blueprint, String filename, String code, List<String> warnings) {
        String layer = blueprint.effectiveLayer();
        if ("controller".equals(layer) && !code.contains("@RestController") && !code.contains("@Controller")) {
            warnings.add("Controller layer file missing controller annotation: " + filename);
        }
        if ("service".equals(layer) && filename.endsWith("Service.java") && !code.contains("@Service")) {
            warnings.add("Service layer file missing @Service annotation: " + filename);
        }
    }

    /**
     * 判断代码中是否仍然包含明显的占位实现
     */
    private boolean containsPlaceholder(String code) {
        return code.contains("TODO") || code.contains("implement logic") || code.contains("placeholder");
    }

    /**
     * 收集模板目录下已有的视图名称
     */
    private List<String> collectTemplateNames(List<SourceCode> generatedFiles) {
        List<String> templateNames = new ArrayList<>();
        for (SourceCode file : generatedFiles) {
            String filename = sourceCodePathService.normalizePath(file.filename());
            if (filename.startsWith("src/main/resources/templates/") && filename.endsWith(".html")) {
                templateNames.add(Path.of(filename).getFileName().toString().replaceFirst("\\.html$", ""));
            }
        }
        return templateNames;
    }

    /**
     * 建立 Java 全限定类名到源码文件的索引
     */
    private void indexJavaType(String filename, String code, Map<String, SourceCode> javaTypes) {
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
        Matcher typeMatcher = PUBLIC_TYPE_PATTERN.matcher(code);
        if (packageMatcher.find() && typeMatcher.find()) {
            javaTypes.put(packageMatcher.group(1) + "." + typeMatcher.group(2),
                    new SourceCode(filename, "java", code));
        }
    }

    /**
     * 校验主源码是否错误依赖了测试源码中的类型
     */
    private void validateMainDoesNotDependOnTest(List<SourceCode> mainJavaFiles,
            Map<String, SourceCode> javaTypes,
            List<String> warnings) {
        for (SourceCode mainFile : mainJavaFiles) {
            Matcher importMatcher = IMPORT_PATTERN.matcher(mainFile.code() == null ? "" : mainFile.code());
            while (importMatcher.find()) {
                SourceCode importedType = javaTypes.get(importMatcher.group(1));
                if (importedType != null && sourceCodePathService.normalizePath(importedType.filename()).startsWith("src/test/java/")) {
                    warnings.add("Main source file " + mainFile.filename()
                            + " imports production-looking type from test source set: " + importedType.filename()
                            + ". Move that type to src/main/java or stop using it from main code.");
                }
            }
        }
    }

    /**
     * 校验 MVC Controller 返回的视图名是否存在对应模板
     */
    private void validateMissingMvcTemplates(String filename, String code, List<String> templateNames,
            List<String> warnings) {
        if (!code.contains("@Controller") || code.contains("@RestController")) {
            return;
        }

        Matcher matcher = MVC_VIEW_RETURN_PATTERN.matcher(code);
        while (matcher.find()) {
            String viewName = matcher.group(1);
            if (viewName.startsWith("redirect:") || viewName.startsWith("forward:") || viewName.contains("/")) {
                continue;
            }
            if (!templateNames.contains(viewName)) {
                warnings.add("MVC controller " + filename + " returns view `" + viewName
                        + "` but src/main/resources/templates/" + viewName + ".html is missing.");
            }
        }
    }

    /**
     * 从 Controller 注解中收集后端接口路径
     */
    private void collectControllerEndpoints(String code, List<String> backendEndpoints) {
        if (!code.contains("@Controller") && !code.contains("@RestController")) {
            return;
        }

        List<String> classMappings = new ArrayList<>();
        List<String> methodMappings = new ArrayList<>();
        int classDeclarationIndex = code.indexOf(" class ");
        Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(code);
        while (matcher.find()) {
            String endpoint = normalizeEndpoint(matcher.group(1));
            if (classDeclarationIndex >= 0 && matcher.start() < classDeclarationIndex) {
                classMappings.add(endpoint);
            } else {
                methodMappings.add(endpoint);
            }
        }

        if (classMappings.isEmpty()) {
            backendEndpoints.addAll(methodMappings);
            return;
        }

        String classPrefix = classMappings.get(0);
        backendEndpoints.add(classPrefix);
        for (String methodMapping : methodMappings) {
            backendEndpoints.add(joinEndpoint(classPrefix, methodMapping));
        }
    }

    /**
     * 校验前端 fetch 或 axios 调用是否能匹配到后端接口
     */
    private void validateFrontendRequests(List<SourceCode> frontendFiles, List<String> backendEndpoints,
            List<String> warnings) {
        if (frontendFiles.isEmpty() || backendEndpoints.isEmpty()) {
            return;
        }

        for (SourceCode frontendFile : frontendFiles) {
            Matcher matcher = FRONTEND_REQUEST_PATTERN.matcher(frontendFile.code() == null ? "" : frontendFile.code());
            while (matcher.find()) {
                String requestPath = normalizeEndpoint(matcher.group(1));
                if (isExternalUrl(requestPath) || requestPath.contains("${") || requestPath.contains("+")) {
                    continue;
                }
                boolean matched = backendEndpoints.stream().anyMatch(endpoint -> endpoint.equals(requestPath));
                if (!matched) {
                    warnings.add("Frontend file " + frontendFile.filename() + " calls `" + requestPath
                            + "` but no matching controller mapping was found. Known backend endpoints: "
                            + String.join(", ", backendEndpoints));
                }
            }
        }
    }

    /**
     * 根据结构化接口文档校验端点、视图模板和前端调用是否真正落到代码里
     */
    private void validateProjectContract(ProjectContract contract,
            List<SourceCode> generatedFiles,
            List<String> templateNames,
            List<String> backendEndpoints,
            List<String> warnings) {
        if (contract == null) {
            return;
        }

        validateContractEndpoints(contract.endpoints(), backendEndpoints, warnings);
        validateContractViews(contract.views(), generatedFiles, templateNames, warnings);
        validateContractFrontendCalls(contract.frontendCalls(), generatedFiles, backendEndpoints, warnings);
    }

    /**
     * 校验契约中声明的后端接口是否能在 Controller 映射中找到
     */
    private void validateContractEndpoints(List<ApiEndpointContract> endpoints,
            List<String> backendEndpoints,
            List<String> warnings) {
        for (ApiEndpointContract endpoint : endpoints) {
            String expectedPath = normalizeEndpoint(endpoint.path());
            boolean found = backendEndpoints.stream().anyMatch(actualPath -> actualPath.equals(expectedPath));
            if (!found) {
                warnings.add("Contract endpoint `" + endpoint.method() + " " + expectedPath
                        + "` is declared but no matching controller mapping was found.");
            }
        }
    }

    /**
     * 校验契约中声明的 MVC 视图是否存在对应模板文件
     */
    private void validateContractViews(List<ViewContract> views,
            List<SourceCode> generatedFiles,
            List<String> templateNames,
            List<String> warnings) {
        for (ViewContract view : views) {
            String templatePath = sourceCodePathService.normalizePath(view.templatePath());
            String viewName = view.name() == null || view.name().isBlank()
                    ? Path.of(templatePath).getFileName().toString().replaceFirst("\\.html$", "")
                    : view.name();
            boolean templateExists = generatedFiles.stream()
                    .map(file -> sourceCodePathService.normalizePath(file.filename()))
                    .anyMatch(filename -> filename.equals(templatePath));
            if (!templateExists && !templateNames.contains(viewName)) {
                warnings.add("Contract view `" + viewName + "` expects template `" + templatePath
                        + "` but the template file was not generated.");
            }
        }
    }

    /**
     * 校验契约中声明的前端调用是否能匹配到后端接口和实际前端文件
     */
    private void validateContractFrontendCalls(List<FrontendCallContract> frontendCalls,
            List<SourceCode> generatedFiles,
            List<String> backendEndpoints,
            List<String> warnings) {
        for (FrontendCallContract call : frontendCalls) {
            String callPath = normalizeEndpoint(call.path());
            boolean endpointExists = backendEndpoints.stream().anyMatch(endpoint -> endpoint.equals(callPath));
            if (!endpointExists) {
                warnings.add("Contract frontend call `" + call.sourceFile() + " -> " + call.method() + " " + callPath
                        + "` has no matching backend endpoint.");
            }

            if (!call.sourceFile().isBlank() && !containsFile(generatedFiles, call.sourceFile())) {
                warnings.add("Contract frontend call source file is missing: " + call.sourceFile());
            }
        }
    }

    /**
     * 判断生成结果中是否包含指定项目相对路径的文件
     */
    private boolean containsFile(List<SourceCode> generatedFiles, String expectedPath) {
        String normalizedExpectedPath = sourceCodePathService.normalizePath(expectedPath);
        return generatedFiles.stream()
                .map(file -> sourceCodePathService.normalizePath(file.filename()))
                .anyMatch(filename -> filename.equals(normalizedExpectedPath));
    }

    /**
     * 判断文件是否属于前端资源
     */
    private boolean isFrontendAsset(String filename) {
        return sourceCodePathService.isFrontendFile(filename);
    }

    /**
     * 判断请求路径是否为外部 URL
     */
    private boolean isExternalUrl(String path) {
        return path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//");
    }

    /**
     * 规范化接口路径，去掉查询参数和多余斜杠
     */
    private String normalizeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String path = raw.trim();
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.replaceAll("/{2,}", "/").replaceFirst("/$", "");
    }

    /**
     * 拼接 Controller 类级路径和方法级路径
     */
    private String joinEndpoint(String prefix, String suffix) {
        if ("/".equals(prefix)) {
            return normalizeEndpoint(suffix);
        }
        if ("/".equals(suffix)) {
            return normalizeEndpoint(prefix);
        }
        return normalizeEndpoint(prefix + "/" + suffix);
    }
}
