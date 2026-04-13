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
            if (!expectedPath.startsWith(declaredPackage)) {
                warnings.add("Package/path mismatch in " + filename + ": package " + matcher.group(1));
            }
        } else if (normalized.startsWith("src/test/java/")) {
            String expectedPath = normalized.substring("src/test/java/".length());
            if (!expectedPath.startsWith(declaredPackage)) {
                warnings.add("Package/path mismatch in " + filename + ": package " + matcher.group(1));
            }
        }
    }

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

    private boolean containsPlaceholder(String code) {
        // 占位实现一旦漏进最终编译阶段，通常会带来更多噪音错误。
        return code.contains("TODO") || code.contains("implement logic") || code.contains("placeholder");
    }

    private String normalize(String path) {
        return path == null ? "unknown" : path.trim().replace("\\", "/");
    }
}
