package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern TYPED_REQUEST_MAPPING_PATTERN = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)"
                    + "\\s*(?:\\(\\s*)?(?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern FRONTEND_REQUEST_PATTERN = Pattern.compile(
            "\\b(?:fetch|axios\\.(?:get|post|put|delete|patch))\\s*\\(\\s*([`'\"])(.*?)\\1",
            Pattern.DOTALL);
    private static final Pattern JSON_STRINGIFY_PATTERN = Pattern.compile(
            "JSON\\.stringify\\s*\\(\\s*\\{(.*?)\\}\\s*\\)", Pattern.DOTALL);
    private static final Pattern DTO_FIELD_PATTERN = Pattern.compile(
            "\\bprivate\\s+(?:final\\s+)?[A-Za-z0-9_$.<>?, @\\[\\]]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:;|=)");
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{([A-Za-z_$][A-Za-z0-9_$]*)}");
    private static final Pattern PATH_VARIABLE_PARAMETER_PATTERN = Pattern.compile(
            "@PathVariable(?:\\s*\\([^)]*\\))?\\s+(?:final\\s+)?[A-Za-z0-9_$.<>?,]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern THYMELEAF_NESTED_URL_PATTERN = Pattern.compile(
            "th:(?:action|href)\\s*=\\s*\"[^\"]*@\\{[^\"(]*\\$\\{");
    private static final Pattern THYMELEAF_OBJECT_PATTERN = Pattern.compile(
            "th:object\\s*=\\s*([\"'])\\s*\\$\\{([A-Za-z_$][A-Za-z0-9_$]*)}\\s*\\1",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_FORM_PATTERN = Pattern.compile(
            "<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_LINK_PATTERN = Pattern.compile(
            "<a\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_ACTION_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\b(?:th:)?action\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_HREF_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\b(?:th:)?href\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_METHOD_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\b(?:th:)?method\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TEXTAREA_PATTERN = Pattern.compile(
            "<textarea\\b([^>]*)>(.*?)</textarea>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        validateDuplicateControllerMappings(mainJavaFiles, warnings);
        validateControllerPathVariables(mainJavaFiles, warnings);
        validateSpringBeanDependencies(mainJavaFiles, warnings);
        validateKnownInvalidSpringApis(mainJavaFiles, warnings);
        validateRedirectResponses(mainJavaFiles, contract, warnings);
        validateNotFoundExceptionMappings(mainJavaFiles, warnings);
        validateStaticIndexRouteConflicts(mainJavaFiles, generatedFiles, warnings);
        validateThymeleafTemplates(generatedFiles, warnings);
        validateThymeleafModelAttributes(mainJavaFiles, generatedFiles, warnings);
        validateFrontendPayloads(contract, generatedFiles, warnings);
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
            String frontendCode = frontendFile.code() == null ? "" : frontendFile.code();
            Matcher matcher = FRONTEND_REQUEST_PATTERN.matcher(frontendCode);
            while (matcher.find()) {
                String requestPath = normalizeEndpoint(matcher.group(2));
                if (isExternalUrl(requestPath) || requestPath.contains("+")) {
                    continue;
                }
                boolean matched = backendEndpoints.stream().anyMatch(endpoint -> endpointsMatch(endpoint, requestPath)
                        || isConcatenatedDynamicPath(frontendCode, matcher, endpoint));
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
            boolean endpointExists = backendEndpoints.stream().anyMatch(endpoint -> endpointsMatch(endpoint, callPath));
            if (!endpointExists) {
                warnings.add("Contract frontend call `" + call.sourceFile() + " -> " + call.method() + " " + callPath
                        + "` has no matching backend endpoint.");
            }

            if (!call.sourceFile().isBlank() && !containsFile(generatedFiles, call.sourceFile())) {
                warnings.add("Contract frontend call source file is missing: " + call.sourceFile());
                continue;
            }

            SourceCode source = findFile(generatedFiles, call.sourceFile());
            if (source != null && !containsFrontendCall(source.code(), call.method(), callPath)) {
                warnings.add("Contract frontend call `" + call.sourceFile() + " -> " + call.method() + " " + callPath
                        + "` is declared but the source file does not issue that request.");
            }
        }
    }

    /**
     * 校验前端 JSON 请求字段是否与契约声明的请求 DTO 一致，防止 optionId/pollOptionId 一类静默错位。
     */
    private void validateFrontendPayloads(ProjectContract contract,
            List<SourceCode> generatedFiles,
            List<String> warnings) {
        if (contract == null) {
            return;
        }
        for (FrontendCallContract call : contract.frontendCalls()) {
            if ("GET".equalsIgnoreCase(call.method())) {
                continue;
            }
            ApiEndpointContract endpoint = contract.endpoints().stream()
                    .filter(candidate -> candidate.method().equalsIgnoreCase(call.method()))
                    .filter(candidate -> endpointsMatch(candidate.path(), call.path()))
                    .findFirst()
                    .orElse(null);
            if (endpoint == null || endpoint.requestDto().isBlank()) {
                continue;
            }

            SourceCode frontend = findFile(generatedFiles, call.sourceFile());
            SourceCode dto = findJavaType(generatedFiles, endpoint.requestDto());
            if (frontend == null || dto == null) {
                continue;
            }

            Set<String> payloadFields = extractPayloadFields(frontend.code(), call.path());
            Set<String> dtoFields = extractDtoFields(dto.code());
            if (payloadFields.isEmpty() || dtoFields.isEmpty()) {
                continue;
            }
            Set<String> unknown = new LinkedHashSet<>(payloadFields);
            unknown.removeAll(dtoFields);
            Set<String> missing = new LinkedHashSet<>(dtoFields);
            missing.removeAll(payloadFields);
            if (!unknown.isEmpty() || !missing.isEmpty()) {
                warnings.add("Frontend request body field mismatch for `" + call.method() + " "
                        + normalizeEndpoint(call.path()) + "`: unknown=" + unknown + ", missing=" + missing
                        + ", requestDto=" + simpleTypeName(endpoint.requestDto()) + ".");
            }
        }
    }

    /**
     * 检查 Controller 路径变量是否真正进入方法实现，避免路由中的 pollId 被接收后直接丢弃。
     */
    private void validateControllerPathVariables(List<SourceCode> mainJavaFiles, List<String> warnings) {
        for (SourceCode file : mainJavaFiles) {
            String code = file.code() == null ? "" : file.code();
            if (!code.contains("@Controller") && !code.contains("@RestController")) {
                continue;
            }
            String classPrefix = controllerClassPrefix(code);
            int classDeclarationIndex = code.indexOf(" class ");
            Matcher mapping = REQUEST_MAPPING_PATTERN.matcher(code);
            while (mapping.find()) {
                if (classDeclarationIndex < 0 || mapping.start() < classDeclarationIndex) {
                    continue;
                }
                String endpoint = joinEndpoint(classPrefix, mapping.group(1));
                Matcher pathVariable = PATH_VARIABLE_PATTERN.matcher(endpoint);
                if (!pathVariable.find()) {
                    continue;
                }
                int bodyStart = code.indexOf('{', mapping.end());
                if (bodyStart < 0 || bodyStart - mapping.end() > 800) {
                    continue;
                }
                String header = code.substring(mapping.end(), bodyStart);
                int bodyEnd = findMatchingBrace(code, bodyStart);
                if (bodyEnd < 0) {
                    continue;
                }
                String body = code.substring(bodyStart + 1, bodyEnd);
                Matcher parameter = PATH_VARIABLE_PARAMETER_PATTERN.matcher(header);
                while (parameter.find()) {
                    String parameterName = parameter.group(1);
                    if (endpoint.contains("{" + parameterName + "}")
                            && !Pattern.compile("\\b" + Pattern.quote(parameterName) + "\\b").matcher(body).find()) {
                        warnings.add("Controller path variable `" + parameterName + "` for endpoint `" + endpoint
                                + "` is declared but not used in " + file.filename() + ".");
                    }
                }
            }
        }
    }

    /**
     * 静态首页存在时，REST Controller 不应再用文本或 JSON 响应占用根路径。
     */
    private void validateStaticIndexRouteConflicts(List<SourceCode> mainJavaFiles,
            List<SourceCode> generatedFiles,
            List<String> warnings) {
        boolean hasStaticIndex = containsFile(generatedFiles, "src/main/resources/static/index.html");
        if (!hasStaticIndex) {
            return;
        }
        for (SourceCode file : mainJavaFiles) {
            String code = file.code() == null ? "" : file.code();
            if (!code.contains("@RestController")) {
                continue;
            }
            int classDeclarationIndex = code.indexOf(" class ");
            Matcher mapping = REQUEST_MAPPING_PATTERN.matcher(code);
            while (mapping.find()) {
                if (mapping.start() > classDeclarationIndex
                        && "/".equals(normalizeEndpoint(mapping.group(1)))) {
                    warnings.add("Static index route conflict: " + file.filename()
                            + " maps REST output to `/` while src/main/resources/static/index.html exists.");
                    break;
                }
            }
        }
    }

    /**
     * 捕获可确定判定的 Thymeleaf 表达式和编辑表单绑定风险，弥补只检查模板文件存在的不足。
     */
    private void validateThymeleafTemplates(List<SourceCode> generatedFiles, List<String> warnings) {
        for (SourceCode file : generatedFiles) {
            String filename = sourceCodePathService.normalizePath(file.filename());
            if (!filename.startsWith("src/main/resources/templates/") || !filename.endsWith(".html")) {
                continue;
            }
            String code = file.code() == null ? "" : file.code();
            if (THYMELEAF_NESTED_URL_PATTERN.matcher(code).find()) {
                warnings.add("Thymeleaf template risk in " + filename
                        + ": URL expression nests `${...}` inside `@{...}`; use a path-variable expression instead.");
            }
            boolean editForm = code.contains("!= null") && code.contains("th:action");
            if (!editForm) {
                continue;
            }
            Matcher textarea = TEXTAREA_PATTERN.matcher(code);
            while (textarea.find()) {
                String attributes = textarea.group(1);
                String body = textarea.group(2).trim();
                if (attributes.contains("name=") && !attributes.contains("th:text")
                        && body.isBlank()) {
                    warnings.add("Thymeleaf template risk in " + filename
                            + ": editable textarea has no `th:text` binding and will not retain existing content.");
                }
            }
        }
    }

    /**
     * 检查多个 Controller 是否声明了相同 HTTP 方法和规范化路径，避免 Spring 启动时才暴露 Ambiguous mapping。
     */
    private void validateDuplicateControllerMappings(List<SourceCode> mainJavaFiles, List<String> warnings) {
        Map<String, String> ownerByMapping = new LinkedHashMap<>();
        Set<String> reportedMappings = new LinkedHashSet<>();
        for (SourceCode file : mainJavaFiles) {
            String code = file.code() == null ? "" : file.code();
            if (!code.contains("@Controller") && !code.contains("@RestController")) {
                continue;
            }
            int classDeclarationIndex = code.indexOf(" class ");
            String classPrefix = controllerClassPrefix(code);
            Matcher mapping = TYPED_REQUEST_MAPPING_PATTERN.matcher(code);
            while (mapping.find()) {
                if (classDeclarationIndex < 0 || mapping.start() < classDeclarationIndex) {
                    continue;
                }
                String method = mappingHttpMethod(mapping.group(1));
                if (method == null) {
                    continue;
                }
                String endpoint = canonicalEndpoint(joinEndpoint(classPrefix, mapping.group(2)));
                String key = method + " " + endpoint;
                String previousOwner = ownerByMapping.putIfAbsent(key, file.filename());
                if (previousOwner != null && reportedMappings.add(key)) {
                    warnings.add("Duplicate controller mapping `" + key + "` is declared by "
                            + previousOwner + " and " + file.filename() + ".");
                }
            }
        }
    }

    private String mappingHttpMethod(String annotationName) {
        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> null;
        };
    }

    /**
     * 检查 Spring 组件构造器注入的项目内具体类是否注册为 Bean，提前发现 NoSuchBeanDefinitionException。
     */
    private void validateSpringBeanDependencies(List<SourceCode> mainJavaFiles, List<String> warnings) {
        Map<String, SourceCode> sourcesByType = new LinkedHashMap<>();
        for (SourceCode file : mainJavaFiles) {
            Matcher type = PUBLIC_TYPE_PATTERN.matcher(file.code() == null ? "" : file.code());
            if (type.find()) {
                sourcesByType.put(type.group(2), file);
            }
        }
        String allSource = mainJavaFiles.stream()
                .map(SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .reduce("", (left, right) -> left + "\n" + right);
        Set<String> reported = new LinkedHashSet<>();
        for (Map.Entry<String, SourceCode> owner : sourcesByType.entrySet()) {
            String ownerCode = owner.getValue().code() == null ? "" : owner.getValue().code();
            if (!isSpringBeanType(ownerCode)) {
                continue;
            }
            Pattern constructorPattern = Pattern.compile(
                    "(?:public|protected|private)?\\s*" + Pattern.quote(owner.getKey()) + "\\s*\\(([^)]*)\\)");
            Matcher constructor = constructorPattern.matcher(ownerCode);
            while (constructor.find()) {
                for (String parameter : constructor.group(1).split(",")) {
                    String dependencyType = constructorDependencyType(parameter);
                    SourceCode dependency = sourcesByType.get(dependencyType);
                    if (dependency == null || dependency.code() == null
                            || !dependency.code().contains(" class ")
                            || isSpringBeanType(dependency.code())
                            || hasBeanFactoryMethod(allSource, dependencyType)) {
                        continue;
                    }
                    String key = owner.getKey() + "->" + dependencyType;
                    if (reported.add(key)) {
                        warnings.add("Spring bean dependency `" + dependencyType + "` injected into `"
                                + owner.getKey() + "` is not registered as a component or @Bean.");
                    }
                }
            }
        }
    }

    private String constructorDependencyType(String parameter) {
        String normalized = parameter == null ? "" : parameter
                .replaceAll("@[A-Za-z0-9_$.]+(?:\\([^)]*\\))?", "")
                .replaceAll("\\bfinal\\b", "")
                .trim();
        String[] components = normalized.split("\\s+");
        if (components.length < 2 || components[components.length - 2].contains("<")) {
            return "";
        }
        String type = components[components.length - 2].replaceAll("\\[\\]$", "");
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
        return separator >= 0 ? type.substring(separator + 1) : type;
    }

    private boolean isSpringBeanType(String code) {
        return code.contains("@Component") || code.contains("@Service") || code.contains("@Repository")
                || code.contains("@Controller") || code.contains("@RestController")
                || code.contains("@Configuration");
    }

    private boolean hasBeanFactoryMethod(String allSource, String typeName) {
        return Pattern.compile("@Bean(?:\\s*\\([^)]*\\))?\\s+(?:public\\s+|protected\\s+|private\\s+)?"
                + Pattern.quote(typeName) + "\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(")
                .matcher(allSource).find();
    }

    /**
     * 阻断真实基准中重复出现的已知无效 Spring API，避免把确定性编译错误留给 Docker 才发现。
     */
    private void validateKnownInvalidSpringApis(List<SourceCode> mainJavaFiles, List<String> warnings) {
        for (SourceCode file : mainJavaFiles) {
            String code = file.code() == null ? "" : file.code();
            if (Pattern.compile("\\bResponseEntity\\s*\\.\\s*temporaryRedirect\\s*\\(")
                    .matcher(code).find()) {
                warnings.add("Invalid Spring API usage in " + file.filename()
                        + ": `ResponseEntity.temporaryRedirect(...)` does not exist; use `status(307)` or another explicit 3xx builder.");
            }
            Matcher uriBuilder = Pattern.compile(
                    "(?s)\\bUriComponentsBuilder\\s*\\.[^;\\n]{0,600}?\\.\\s*toUri\\s*\\(")
                    .matcher(code);
            while (uriBuilder.find()) {
                String chain = uriBuilder.group();
                if (!chain.matches("(?s).*\\.\\s*(?:build|buildAndExpand)\\s*\\(.*")) {
                    warnings.add("Invalid Spring API usage in " + file.filename()
                            + ": `UriComponentsBuilder` must call `build()` or `buildAndExpand()` before `toUri()`.");
                    break;
                }
            }
        }
    }

    /**
     * 重定向端点不能以 void 丢弃目标 URL；若直接操作 HttpServletResponse，则允许显式 sendRedirect/status 写法。
     */
    private void validateRedirectResponses(
            List<SourceCode> mainJavaFiles,
            ProjectContract contract,
            List<String> warnings) {
        for (SourceCode file : mainJavaFiles) {
            String code = file.code() == null ? "" : file.code();
            if (!code.contains("@Controller") && !code.contains("@RestController")) {
                continue;
            }
            String classPrefix = controllerClassPrefix(code);
            int classDeclarationIndex = code.indexOf(" class ");
            Matcher mapping = TYPED_REQUEST_MAPPING_PATTERN.matcher(code);
            while (mapping.find()) {
                if (classDeclarationIndex < 0 || mapping.start() < classDeclarationIndex
                        || !"GetMapping".equals(mapping.group(1))) {
                    continue;
                }
                int bodyStart = code.indexOf('{', mapping.end());
                if (bodyStart < 0 || bodyStart - mapping.end() > 1_000) {
                    continue;
                }
                String header = code.substring(mapping.end(), bodyStart);
                Matcher voidMethod = Pattern.compile("\\bvoid\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(")
                        .matcher(header);
                if (!voidMethod.find()) {
                    continue;
                }
                int bodyEnd = findMatchingBrace(code, bodyStart);
                String body = bodyEnd < 0 ? "" : code.substring(bodyStart + 1, bodyEnd);
                String endpoint = canonicalEndpoint(joinEndpoint(classPrefix, mapping.group(2)));
                boolean redirectIntent = voidMethod.group(1).toLowerCase(Locale.ROOT).contains("redirect")
                        || hasRedirectContract(contract, endpoint);
                boolean writesResponse = header.contains("HttpServletResponse")
                        && (body.contains("sendRedirect(")
                                || body.contains("setStatus(") && body.toLowerCase(Locale.ROOT).contains("location"));
                if (redirectIntent && !writesResponse) {
                    warnings.add("Redirect endpoint `GET " + endpoint + "` in " + file.filename()
                            + " returns void without writing an explicit 3xx response and Location header.");
                }
            }
        }
    }

    private boolean hasRedirectContract(ProjectContract contract, String endpoint) {
        if (contract == null) {
            return false;
        }
        return contract.endpoints().stream()
                .filter(item -> "GET".equalsIgnoreCase(item.method()))
                .filter(item -> canonicalEndpoint(item.path()).equals(endpoint))
                .map(ApiEndpointContract::description)
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("redirect") || value.contains("跳转") || value.contains("重定向"));
    }

    /**
     * 源码明确抛出 not-found 异常时必须存在 404 映射，防止 MockMvc 和真实请求得到 500 或 ServletException。
     */
    private void validateNotFoundExceptionMappings(List<SourceCode> mainJavaFiles, List<String> warnings) {
        String allSource = mainJavaFiles.stream()
                .map(SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .reduce("", (left, right) -> left + "\n" + right);
        Set<String> requiredMappings = new LinkedHashSet<>();
        Matcher thrown = Pattern.compile(
                "(?is)(?:throw\\s+new|->\\s*new)\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\(([^;]{0,400})\\)")
                .matcher(allSource);
        while (thrown.find()) {
            String type = simpleTypeName(thrown.group(1));
            String message = thrown.group(2).toLowerCase(Locale.ROOT);
            // ResponseStatusException 已携带确定的 HTTP 状态，不需要再强制生成全局异常处理器。
            if ("ResponseStatusException".equals(type) && message.contains("not_found")) {
                continue;
            }
            if (type.toLowerCase(Locale.ROOT).contains("notfound") || message.contains("not found")) {
                requiredMappings.add(type);
            }
        }
        for (String exceptionType : requiredMappings) {
            if (!hasNotFoundMapping(allSource, exceptionType)) {
                warnings.add("Not-found exception mapping missing for `" + exceptionType
                        + "`: generated code throws it for a missing resource but no HTTP 404 mapping exists.");
            }
        }
    }

    private boolean hasNotFoundMapping(String allSource, String exceptionType) {
        Pattern responseStatus = Pattern.compile(
                "(?s)@ResponseStatus\\s*\\([^)]*NOT_FOUND[^)]*\\)[^{}]{0,500}\\b(?:class|record)\\s+"
                        + Pattern.quote(exceptionType) + "\\b");
        if (responseStatus.matcher(allSource).find()) {
            return true;
        }
        Matcher handler = Pattern.compile(
                "(?s)@ExceptionHandler\\s*\\([^)]*\\b" + Pattern.quote(exceptionType)
                        + "\\s*\\.\\s*class[^)]*\\)(.{0,1200})")
                .matcher(allSource);
        if (!handler.find()) {
            return false;
        }
        String handlerCode = handler.group(1);
        return handlerCode.contains("HttpStatus.NOT_FOUND")
                || handlerCode.contains("ResponseEntity.notFound(")
                || Pattern.compile("\\bstatus\\s*\\(\\s*(?:404|HttpStatus\\.NOT_FOUND)")
                        .matcher(handlerCode).find();
    }

    /**
     * 校验 th:object 引用的表单对象是否由 MVC Controller 放入模型，提前阻断模板运行期异常。
     */
    private void validateThymeleafModelAttributes(List<SourceCode> mainJavaFiles,
            List<SourceCode> generatedFiles,
            List<String> warnings) {
        String controllerCode = mainJavaFiles.stream()
                .map(SourceCode::code)
                .filter(java.util.Objects::nonNull)
                .filter(code -> code.contains("@Controller") && !code.contains("@RestController"))
                .reduce("", (left, right) -> left + "\n" + right);
        for (SourceCode file : generatedFiles) {
            String filename = sourceCodePathService.normalizePath(file.filename());
            if (!filename.startsWith("src/main/resources/templates/") || !filename.endsWith(".html")) {
                continue;
            }
            Matcher object = THYMELEAF_OBJECT_PATTERN.matcher(file.code() == null ? "" : file.code());
            while (object.find()) {
                String attributeName = object.group(2);
                if (!providesModelAttribute(controllerCode, attributeName)) {
                    warnings.add("Thymeleaf model attribute `" + attributeName + "` used by " + filename
                            + " is not provided by any MVC Controller.");
                }
            }
        }
    }

    /**
     * 兼容显式 addAttribute、显式命名和按参数名推导的 @ModelAttribute 三种常见写法。
     */
    private boolean providesModelAttribute(String controllerCode, String attributeName) {
        String quotedName = Pattern.quote(attributeName);
        return Pattern.compile("\\.addAttribute\\s*\\(\\s*[\"']" + quotedName + "[\"']")
                .matcher(controllerCode).find()
                || Pattern.compile("@ModelAttribute\\s*\\(\\s*(?:value|name)?\\s*=?\\s*[\"']"
                        + quotedName + "[\"']\\s*\\)").matcher(controllerCode).find()
                || Pattern.compile("@ModelAttribute(?:\\s*\\(\\s*\\))?[^,;{}()]*\\b"
                        + quotedName + "\\b").matcher(controllerCode).find();
    }

    private String controllerClassPrefix(String code) {
        int classDeclarationIndex = code.indexOf(" class ");
        if (classDeclarationIndex < 0) {
            return "/";
        }
        Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(code);
        while (matcher.find()) {
            if (matcher.start() < classDeclarationIndex) {
                return normalizeEndpoint(matcher.group(1));
            }
        }
        return "/";
    }

    private int findMatchingBrace(String code, int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private SourceCode findFile(List<SourceCode> generatedFiles, String expectedPath) {
        String normalized = sourceCodePathService.normalizePath(expectedPath);
        return generatedFiles.stream()
                .filter(file -> sourceCodePathService.normalizePath(file.filename()).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private SourceCode findJavaType(List<SourceCode> generatedFiles, String requestedType) {
        String simpleName = simpleTypeName(requestedType);
        return generatedFiles.stream()
                .filter(file -> sourceCodePathService.normalizePath(file.filename()).endsWith("/" + simpleName + ".java"))
                .findFirst()
                .orElse(null);
    }

    private String simpleTypeName(String typeName) {
        String normalized = typeName == null ? "" : typeName.trim();
        int genericIndex = normalized.indexOf('<');
        if (genericIndex >= 0) {
            normalized = normalized.substring(0, genericIndex);
        }
        int separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf('$'));
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private Set<String> extractDtoFields(String code) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = DTO_FIELD_PATTERN.matcher(code == null ? "" : code);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private Set<String> extractPayloadFields(String code, String expectedPath) {
        String safeCode = code == null ? "" : code;
        Matcher request = FRONTEND_REQUEST_PATTERN.matcher(safeCode);
        while (request.find()) {
            if (!endpointsMatch(request.group(2), expectedPath)) {
                continue;
            }
            Matcher payload = JSON_STRINGIFY_PATTERN.matcher(safeCode);
            payload.region(request.end(), Math.min(safeCode.length(), request.end() + 1600));
            if (!payload.find()) {
                return Set.of();
            }
            Set<String> fields = new LinkedHashSet<>();
            for (String component : payload.group(1).split(",")) {
                String candidate = component.trim();
                if (candidate.isEmpty() || candidate.startsWith("...")) {
                    continue;
                }
                int colon = candidate.indexOf(':');
                String key = (colon >= 0 ? candidate.substring(0, colon) : candidate)
                        .trim().replaceAll("^[`'\"]|[`'\"]$", "");
                if (key.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    fields.add(key);
                }
            }
            return fields;
        }
        return Set.of();
    }

    private boolean containsFrontendCall(String code, String expectedMethod, String expectedPath) {
        String safeCode = code == null ? "" : code;
        Matcher matcher = FRONTEND_REQUEST_PATTERN.matcher(safeCode);
        while (matcher.find()) {
            if (endpointsMatch(matcher.group(2), expectedPath)
                    || isConcatenatedDynamicPath(safeCode, matcher, expectedPath)) {
                return true;
            }
        }
        return containsHtmlFrontendCall(safeCode, expectedMethod, expectedPath);
    }

    /**
     * 识别 fetch('/urls/' + shortCode) 这类字符串拼接路径，并与 /urls/{shortCode} 契约匹配。
     */
    private boolean isConcatenatedDynamicPath(String code, Matcher requestMatcher, String expectedPath) {
        String expectedCanonical = canonicalEndpoint(expectedPath);
        int parameterIndex = expectedCanonical.indexOf("{}");
        if (parameterIndex < 0) {
            return false;
        }
        String literalPrefix = requestMatcher.group(2);
        String expectedPrefix = expectedCanonical.substring(0, parameterIndex);
        if (!normalizeDynamicPrefix(literalPrefix).equals(normalizeDynamicPrefix(expectedPrefix))) {
            return false;
        }
        int suffixEnd = Math.min(code.length(), requestMatcher.end() + 120);
        String suffix = code.substring(requestMatcher.end(), suffixEnd);
        return suffix.matches("(?s)^\\s*\\+\\s*[A-Za-z_$][A-Za-z0-9_$.]*.*$");
    }

    private String normalizeDynamicPrefix(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("/{2,}", "/");
        return value.endsWith("/") ? value : value + "/";
    }

    /**
     * 识别 MVC 页面通过表单或链接发起的请求，避免只支持 fetch/axios 时误报 Thymeleaf 项目。
     */
    private boolean containsHtmlFrontendCall(String code, String expectedMethod, String expectedPath) {
        Matcher formMatcher = HTML_FORM_PATTERN.matcher(code);
        while (formMatcher.find()) {
            String attributes = formMatcher.group(1);
            String action = firstAttributeValue(HTML_ACTION_ATTRIBUTE_PATTERN, attributes);
            String method = firstAttributeValue(HTML_METHOD_ATTRIBUTE_PATTERN, attributes);
            String normalizedMethod = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
            if (normalizedMethod.equalsIgnoreCase(expectedMethod)
                    && containsEndpointExpression(action, expectedPath)) {
                return true;
            }
        }

        if (!"GET".equalsIgnoreCase(expectedMethod)) {
            return false;
        }
        Matcher linkMatcher = HTML_LINK_PATTERN.matcher(code);
        while (linkMatcher.find()) {
            String href = firstAttributeValue(HTML_HREF_ATTRIBUTE_PATTERN, linkMatcher.group(1));
            if (containsEndpointExpression(href, expectedPath)) {
                return true;
            }
        }
        return false;
    }

    private String firstAttributeValue(Pattern pattern, String attributes) {
        Matcher matcher = pattern.matcher(attributes == null ? "" : attributes);
        return matcher.find() ? matcher.group(2).trim() : null;
    }

    /**
     * 将普通 URL 或 Thymeleaf 的 @{...} 表达式还原为可与 Controller 匹配的端点。
     */
    private boolean containsEndpointExpression(String attributeValue, String expectedPath) {
        if (attributeValue == null || attributeValue.isBlank()) {
            return false;
        }
        List<String> thymeleafEndpoints = extractThymeleafEndpoints(attributeValue);
        if (!thymeleafEndpoints.isEmpty()) {
            return thymeleafEndpoints.stream().anyMatch(endpoint -> endpointsMatch(endpoint, expectedPath));
        }
        String plainValue = attributeValue.trim();
        return plainValue.startsWith("/") && endpointsMatch(plainValue, expectedPath);
    }

    /**
     * 支持标准路径参数写法 @{/articles/{id}(id=${article.id})}，并兼容一个属性中的多个表达式。
     */
    private List<String> extractThymeleafEndpoints(String attributeValue) {
        List<String> endpoints = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < attributeValue.length()) {
            int expressionStart = attributeValue.indexOf("@{", searchFrom);
            if (expressionStart < 0) {
                break;
            }
            int depth = 1;
            int cursor = expressionStart + 2;
            for (; cursor < attributeValue.length(); cursor++) {
                char current = attributeValue.charAt(cursor);
                if (current == '{') {
                    depth++;
                } else if (current == '}' && --depth == 0) {
                    break;
                }
            }
            if (depth != 0) {
                break;
            }
            String expression = attributeValue.substring(expressionStart + 2, cursor).trim();
            int parameterStart = expression.indexOf('(');
            String endpoint = parameterStart >= 0 ? expression.substring(0, parameterStart).trim() : expression;
            if (!endpoint.isBlank()) {
                endpoints.add(endpoint);
            }
            searchFrom = cursor + 1;
        }
        return endpoints;
    }

    private boolean endpointsMatch(String left, String right) {
        return canonicalEndpoint(left).equals(canonicalEndpoint(right));
    }

    private String canonicalEndpoint(String raw) {
        return normalizeEndpoint(raw)
                .replaceAll("\\$\\{[^}]+}", "{}")
                .replaceAll("\\{[^}]+}", "{}");
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
        String normalized = path.replaceAll("/{2,}", "/");
        return normalized.length() > 1 ? normalized.replaceFirst("/$", "") : normalized;
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
