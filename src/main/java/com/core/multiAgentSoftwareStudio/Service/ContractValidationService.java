package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContractValidationService {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile("\\bpublic\\s+(class|interface|record|enum)\\s+([A-Za-z0-9_]+)");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MVC_VIEW_RETURN_PATTERN = Pattern.compile("\\breturn\\s+\"([A-Za-z0-9_./-]+)\"\\s*;");
    private static final Pattern REQUEST_MAPPING_PATTERN = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(?:\\(\\s*)?(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern FRONTEND_REQUEST_PATTERN = Pattern.compile("\\b(?:fetch|axios\\.(?:get|post|put|delete|patch))\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    /**
     * 对当前批次生成结果做轻量契约校验
     */
    public List<String> validateBatch(GenerationBatch batch, List<SourceCode> generatedFiles) {
        List<String> warnings = new ArrayList<>();
        if (batch == null || batch.files().isEmpty()) {
            return warnings;
        }

        // 这个校验器的定位很明确：它只是批次之间的“护栏”，不是最终裁判。
        // 这里只做局部、快速、无需启动沙箱的检查。
        Map<String, SourceCode> fileIndex = new LinkedHashMap<>();
        for (SourceCode generatedFile : generatedFiles) {
            fileIndex.put(normalize(generatedFile.filename()), generatedFile);
        }

        for (FileBlueprint blueprint : batch.files()) {
            String expectedPath = normalize(blueprint.targetPath());
            SourceCode generated = findMatchingFile(fileIndex, expectedPath);
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
     * 根据目标路径在生成结果中查找对应文件
     */
    /**
     * 对完整项目做跨文件契约校验，补上单个生成批次里看不到的问题。
     */
    public List<String> validateProject(List<SourceCode> generatedFiles) {
        List<String> warnings = new ArrayList<>();
        if (generatedFiles == null || generatedFiles.isEmpty()) {
            return warnings;
        }

        Map<String, SourceCode> javaTypes = new LinkedHashMap<>();
        List<SourceCode> mainJavaFiles = new ArrayList<>();
        List<SourceCode> frontendFiles = new ArrayList<>();
        List<String> templateNames = new ArrayList<>();
        List<String> backendEndpoints = new ArrayList<>();

        for (SourceCode file : generatedFiles) {
            String filename = normalize(file.filename());
            if (filename.startsWith("src/main/resources/templates/") && filename.endsWith(".html")) {
                templateNames.add(Path.of(filename).getFileName().toString().replaceFirst("\\.html$", ""));
            }
        }

        for (SourceCode file : generatedFiles) {
            String filename = normalize(file.filename());
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
        return warnings;
    }

    private SourceCode findMatchingFile(Map<String, SourceCode> fileIndex, String expectedPath) {
        // 先按完整路径匹配，匹配不到再退回到纯文件名。
        // 这是为了兼容模型偶尔只返回类名、不返回完整路径的情况。
        SourceCode directMatch = fileIndex.get(expectedPath);
        if (directMatch != null) {
            return directMatch;
        }

        String expectedName = Path.of(expectedPath).getFileName().toString();
        return fileIndex.values().stream()
                .filter(source -> Path.of(normalize(source.filename())).getFileName().toString().equals(expectedName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验 Java 文件的 package 声明是否与路径一致
     */
    private void validatePackage(String filename, String code, List<String> warnings) {
        // Java 文件如果 package 和目录不一致，后面编译阶段通常会直接炸。
        // 这里提前拦一下，成本比进沙箱再发现低很多。
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        if (!matcher.find()) {
            warnings.add("Missing package declaration in " + filename);
            return;
        }

        String declaredPackage = matcher.group(1).replace('.', '/');
        String normalized = normalize(filename);
        if (normalized.startsWith("src/main/java/")) {
            String expectedPath = normalized.substring("src/main/java/".length());
            if (!expectedPath.equals(declaredPackage + "/" + Path.of(normalized).getFileName())) {
                warnings.add("Package/path mismatch in " + filename + ": package " + matcher.group(1));
            }
        } else if (normalized.startsWith("src/test/java/")) {
            String expectedPath = normalized.substring("src/test/java/".length());
            if (!expectedPath.equals(declaredPackage + "/" + Path.of(normalized).getFileName())) {
                warnings.add("Package/path mismatch in " + filename + ": package " + matcher.group(1));
            }
        }
    }

    /**
     * 校验 public 类型名称是否与文件名一致
     */
    private void validatePublicType(String filename, String code, List<String> warnings) {
        // 最常见的文件级错误之一：文件名和 public 类型名不一致。
        Matcher matcher = PUBLIC_TYPE_PATTERN.matcher(code);
        if (!matcher.find()) {
            warnings.add("No public type declaration found in " + filename);
            return;
        }

        String expectedName = Path.of(normalize(filename)).getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String actualName = matcher.group(2);
        if (!expectedName.equals(actualName)) {
            warnings.add("Public type name mismatch in " + filename + ": expected " + expectedName + " but found " + actualName);
        }
    }

    /**
     * 根据层级约定检查常见注解是否缺失
     */
    private void validateLayerHints(FileBlueprint blueprint, String filename, String code, List<String> warnings) {
        // 这里放的是“层级习惯检查”，不是硬规则。
        // 目的是在真正编译之前，尽早发现一些明显偏离预期的生成结果。
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
        // 占位实现一旦漏进最终编译阶段，通常会带来更多噪音错误。
        return code.contains("TODO") || code.contains("implement logic") || code.contains("placeholder");
    }

    /**
     * 规范化路径字符串，便于统一比较
     */
    private void indexJavaType(String filename, String code, Map<String, SourceCode> javaTypes) {
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
        Matcher typeMatcher = PUBLIC_TYPE_PATTERN.matcher(code);
        if (packageMatcher.find() && typeMatcher.find()) {
            javaTypes.put(packageMatcher.group(1) + "." + typeMatcher.group(2),
                    new SourceCode(filename, "java", code));
        }
    }

    private void validateMainDoesNotDependOnTest(List<SourceCode> mainJavaFiles,
            Map<String, SourceCode> javaTypes,
            List<String> warnings) {
        for (SourceCode mainFile : mainJavaFiles) {
            String code = mainFile.code() == null ? "" : mainFile.code();
            Matcher importMatcher = IMPORT_PATTERN.matcher(code);
            while (importMatcher.find()) {
                SourceCode importedType = javaTypes.get(importMatcher.group(1));
                if (importedType != null && normalize(importedType.filename()).startsWith("src/test/java/")) {
                    warnings.add("Main source file " + mainFile.filename()
                            + " imports production-looking type from test source set: " + importedType.filename()
                            + ". Move that type to src/main/java or stop using it from main code.");
                }
            }
        }
    }

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

    private boolean isFrontendAsset(String filename) {
        String normalized = normalize(filename);
        return normalized.endsWith(".html") || normalized.endsWith(".css") || normalized.endsWith(".js");
    }

    private boolean isExternalUrl(String path) {
        return path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//");
    }

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

    private String joinEndpoint(String prefix, String suffix) {
        if ("/".equals(prefix)) {
            return normalizeEndpoint(suffix);
        }
        if ("/".equals(suffix)) {
            return normalizeEndpoint(prefix);
        }
        return normalizeEndpoint(prefix + "/" + suffix);
    }

    private String normalize(String path) {
        return path == null ? "unknown" : path.trim().replace("\\", "/");
    }
}
