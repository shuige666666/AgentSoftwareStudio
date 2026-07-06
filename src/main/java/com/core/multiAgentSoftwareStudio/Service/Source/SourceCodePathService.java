package com.core.multiAgentSoftwareStudio.Service.Source;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责统一源码文件名、项目相对路径、测试文件识别和语言类型推断规则。
 * 同时收口了全项目共享的 upsert / applyFix、路径规范化、Java 代码结构提取等工具方法，
 * 避免各服务各自重复实现。
 */
@Service
public class SourceCodePathService {

    // ==================== 文件名规范化 ====================

    /**
     * 规范化模型返回的文件名和项目内路径
     */
    public String normalizeGeneratedFilename(String filename, String code) {
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

    /**
     * 判断模型返回的文件名是否缺失或属于占位文件名
     */
    public boolean isMissingFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return true;
        }
        String pureName = Path.of(filename.trim().replace("\\", "/")).getFileName().toString();
        return pureName.equalsIgnoreCase("Unknown.java")
                || pureName.equalsIgnoreCase("Unknown")
                || pureName.equalsIgnoreCase("GeneratedFile.java");
    }

    // ==================== 内存代码列表 upsert ====================

    /**
     * 将新生成代码写入内存列表，如已存在则覆盖（精确路径匹配）。
     * 适用于 BatchGeneration、TestGeneration 等场景，生成阶段文件名已经过规范化。
     */
    public void upsertSourceCode(List<SourceCode> codes, String filename, String code) {
        String safeCode = code == null ? "" : code;
        String normalizedCandidate = normalizeGeneratedFilename(filename, safeCode);
        String language = detectLanguageFromFilename(normalizedCandidate);
        for (int i = 0; i < codes.size(); i++) {
            String normalizedExisting = normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (normalizedExisting.equals(normalizedCandidate)) {
                codes.set(i, new SourceCode(normalizedCandidate, language, safeCode));
                return;
            }
        }
        codes.add(new SourceCode(normalizedCandidate, language, safeCode));
    }

    /**
     * 将修复结果写入内存列表，如已存在则覆盖（精确路径匹配 + 纯文件名兜底匹配）。
     * 适用于 Debugger / FrontendReview 等修复场景，模型可能只返回类名而非完整路径。
     */
    public void applyCodeFix(List<SourceCode> codes, String filename, String code) {
        String safeCode = code == null ? "" : code;
        String normalizedFixFilename = normalizeGeneratedFilename(filename, safeCode);
        String fixPureName = Path.of(normalizedFixFilename).getFileName().toString();
        String language = detectLanguageFromFilename(normalizedFixFilename);

        for (int i = 0; i < codes.size(); i++) {
            String existingFilename = normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (existingFilename.equals(normalizedFixFilename)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), safeCode));
                return;
            }
            String existingPureName = Path.of(existingFilename).getFileName().toString();
            if (existingPureName.equals(fixPureName)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), safeCode));
                return;
            }
        }
        codes.add(new SourceCode(normalizedFixFilename, language, safeCode));
    }

    // ==================== 路径规范化 ====================

    /**
     * 规范化项目内文件路径，统一反斜杠为正斜杠，null/空白返回默认值。
     * 全项目统一使用此方法做路径格式化，避免各服务各自实现。
     */
    public String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        return path.trim().replace("\\", "/");
    }

    // ==================== Java 代码结构提取 ====================

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_NAME_PATTERN = Pattern.compile(
            "^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)", Pattern.MULTILINE);

    /**
     * 从 Java 源码中提取 package 名，用于推断文件所在包路径。
     */
    public String extractPackageName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 从 Java 源码中提取顶层类型名，用于在文件名缺失时推断文件名。
     */
    public String extractJavaTypeName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Matcher matcher = TYPE_NAME_PATTERN.matcher(code);
        return matcher.find() ? matcher.group(2) : null;
    }

    // ==================== 文件类型判断 ====================

    /**
     * 根据路径和代码内容判断一个 Java 文件是否更像测试文件。
     */
    public boolean isLikelyTestFile(String filename, String code) {
        String normalized = filename == null ? "" : filename.replace("\\", "/");
        return normalized.endsWith("Test.java")
                || normalized.contains("src/test/java/")
                || (code != null && (code.contains("org.junit.jupiter") || code.contains("@Test")));
    }

    /**
     * 判断文件是否属于前端资源（HTML/CSS/JS）
     */
    public boolean isFrontendFile(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".js");
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

    // ==================== 通用字符串工具 ====================

    /**
     * 在值为空时返回兜底文本。全项目统一使用此方法替代各自定义的 safeValue。
     */
    public static String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ==================== 内部方法 ====================

    private String inferJavaPathFromCode(String code, boolean isLikelyTest) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Matcher typeMatcher = TYPE_NAME_PATTERN.matcher(code);
        if (!typeMatcher.find()) {
            return null;
        }

        String packageName = extractPackageName(code);
        Path sourceRoot = isLikelyTest ? Path.of("src", "test", "java") : Path.of("src", "main", "java");
        Path packagePath = packageName == null || packageName.isBlank()
                ? Path.of("")
                : Path.of(packageName.replace('.', '/'));
        return sourceRoot.resolve(packagePath).resolve(typeMatcher.group(2) + ".java").toString().replace("\\", "/");
    }
}
