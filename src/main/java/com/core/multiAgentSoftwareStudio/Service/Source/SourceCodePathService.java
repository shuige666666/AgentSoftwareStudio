package com.core.multiAgentSoftwareStudio.Service.Source;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责统一源码文件名、项目相对路径、测试文件识别和语言类型推断规则。
 */
@Service
public class SourceCodePathService {

    /**
     * 规范化模型返回的文件名和项目内路径
     */
    public String normalizeGeneratedFilename(String filename, String code) {
        // 模型返回的文件名有时只写类名，有时写完整路径。
        // 这里统一收敛成项目内相对路径，方便去重、覆盖和落盘。
        boolean isLikelyTest = isLikelyTestFile(filename, code);
        if (isMissingFilename(filename)) {
            String inferredPath = inferJavaPathFromCode(code, isLikelyTest);
            return inferredPath == null ? "GeneratedFile.java" : inferredPath;
        }
        String normalized = filename.trim().replace("\\", "/");
        int testPathIndex = normalized.indexOf("src/test/java/");
        if (testPathIndex >= 0) {
            normalized = normalized.substring(testPathIndex);
        } else {
            int mainPathIndex = normalized.indexOf("src/main/java/");
            if (mainPathIndex >= 0) {
                normalized = normalized.substring(mainPathIndex);
            }
        }
        if (isLikelyTest && normalized.startsWith("src/main/java/")) {
            normalized = "src/test/java/" + normalized.substring("src/main/java/".length());
        }
        return normalized;
    }

    private boolean isMissingFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return true;
        }
        String pureName = Path.of(filename.trim().replace("\\", "/")).getFileName().toString();
        return pureName.equalsIgnoreCase("Unknown.java")
                || pureName.equalsIgnoreCase("Unknown")
                || pureName.equalsIgnoreCase("GeneratedFile.java");
    }

    private boolean isLikelyTestFile(String filename, String code) {
        String normalized = filename == null ? "" : filename.replace("\\", "/");
        return normalized.endsWith("Test.java")
                || normalized.contains("src/test/java/")
                || (code != null && (code.contains("org.junit.jupiter") || code.contains("@Test")));
    }

    private String inferJavaPathFromCode(String code, boolean isLikelyTest) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Matcher typeMatcher = Pattern.compile(
                "(?m)^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
                        + "(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(code);
        if (!typeMatcher.find()) {
            return null;
        }

        String packageName = null;
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;").matcher(code);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }

        Path sourceRoot = isLikelyTest ? Path.of("src", "test", "java") : Path.of("src", "main", "java");
        Path packagePath = packageName == null || packageName.isBlank()
                ? Path.of("")
                : Path.of(packageName.replace('.', '/'));
        return sourceRoot.resolve(packagePath).resolve(typeMatcher.group(2) + ".java").toString().replace("\\", "/");
    }

    /**
     * 根据文件名后缀推断源码语言类型
     */
    public String detectLanguageFromFilename(String filename) {
        String lower = filename == null ? "text" : filename.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".properties")) {
            return "properties";
        }
        if (lower.endsWith(".html")) {
            return "html";
        }
        if (lower.endsWith(".css")) {
            return "css";
        }
        if (lower.endsWith(".js")) {
            return "js";
        }
        return "text";
    }
}
