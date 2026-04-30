package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFix;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    private static final String WORKSPACE_ROOT = "ai_generated_projects";

    /**
     * 将本轮生成出来的所有源码文件保存到一个带时间戳的本地项目目录中。
     */
    public Path saveProjectToDisk(String projectName, List<SourceCode> sourceCodes) {
        try {
            // 1. 创建本次生成任务独立的项目目录，避免覆盖历史生成结果。
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = safePathSegment(projectName, "GeneratedProject");
            Path projectDir = Paths.get(WORKSPACE_ROOT, safeProjectName + "_" + timestamp);
            Files.createDirectories(projectDir);

            System.out.println("💾 开始持久化代码到: " + projectDir.toAbsolutePath());

            // 2. 逐个写入生成文件；对空记录、空文件名、空代码内容做防御性处理。
            List<SourceCode> safeSourceCodes = sourceCodes == null ? List.of() : sourceCodes;
            for (int i = 0; i < safeSourceCodes.size(); i++) {
                SourceCode sourceCode = safeSourceCodes.get(i);
                if (sourceCode == null) {
                    System.out.println("   └── 跳过空文件记录: index=" + i);
                    continue;
                }

                try {
                    // 3. 规范化文件名并解析最终相对路径，必要时从 Java 代码内容推断路径。
                    String filename = normalizeFixFilename(sourceCode.filename(), sourceCode.code());
                    String code = sourceCode.code() == null ? "" : sourceCode.code();
                    Path relativePath = resolveSmartPath(filename, code);
                    Path finalPath = projectDir.resolve(relativePath).normalize();

                    // 4. 防止模型返回类似 ../xxx 的路径逃出本次生成目录。
                    if (!finalPath.startsWith(projectDir.normalize())) {
                        throw new IllegalArgumentException("Generated file path escapes project directory: " + relativePath);
                    }

                    if (finalPath.getParent() != null) {
                        Files.createDirectories(finalPath.getParent());
                    }

                    Files.writeString(finalPath, code, StandardCharsets.UTF_8);
                    System.out.println("   └── 写入文件: " + relativePath + " (原名: " + sourceCode.filename() + ")");
                } catch (Exception e) {
                    throw new RuntimeException("保存第 " + (i + 1) + " 个生成文件失败: filename="
                            + sourceCode.filename() + ", codeIsNull=" + (sourceCode.code() == null), e);
                }
            }

            return projectDir;
        } catch (IOException e) {
            throw new RuntimeException("无法保存代码到本地磁盘", e);
        }
    }

    /**
     * 将 DebuggerAgent 或 FrontendReviewAgent 返回的整文件修复结果覆盖到磁盘项目中。
     */
    public void applyFixesToDisk(Path projectDir, List<CodeFix> fixes) {
        try {
            // 1. 修复结果可能为空；这里统一转为空列表，避免外层流程因为空集合中断。
            List<CodeFix> safeFixes = fixes == null ? List.of() : fixes;
            for (int i = 0; i < safeFixes.size(); i++) {
                CodeFix fix = safeFixes.get(i);
                if (fix == null) {
                    System.out.println("   跳过空修复记录: index=" + i);
                    continue;
                }

                String normalizedFilename = normalizeFixFilename(fix.filename(), fix.newCode());
                Path relativePath = resolveSmartPath(normalizedFilename, fix.newCode());
                Path exactPath = projectDir.resolve(relativePath).normalize();
                String pureFileName = exactPath.getFileName().toString();

                Path existingFilePath = findExistingFile(projectDir, pureFileName);
                Path targetPath;

                // 2. 优先覆盖精确路径；如果模型只返回类名，则回退到项目中同名文件的位置。
                if (Files.exists(exactPath)) {
                    targetPath = exactPath;
                } else if (existingFilePath != null) {
                    targetPath = existingFilePath;
                } else {
                    targetPath = exactPath;
                }

                // 3. 修复阶段同样限制写入范围，避免错误路径覆盖项目目录外的文件。
                if (!targetPath.normalize().startsWith(projectDir.normalize())) {
                    throw new IllegalArgumentException("Fix target path escapes project directory: " + targetPath);
                }

                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }

                Files.writeString(targetPath, fix.newCode() == null ? "" : fix.newCode(), StandardCharsets.UTF_8);
                System.out.println("   🔧 已覆盖修复文件: " + targetPath + " (AI原输出名: " + fix.filename() + ")");
            }
        } catch (IOException e) {
            throw new RuntimeException("应用代码修复失败", e);
        }
    }

    /**
     * 根据模型返回的文件名和代码内容，推断文件应该落到项目内的哪个相对路径。
     */
    private Path resolveSmartPath(String filenameInput, String content) {
        String filename = normalizeFixFilename(filenameInput, content);
        String safeContent = content == null ? "" : content;

        // 1. 如果模型已经给出标准源码目录，直接截取并信任项目内路径。
        int testPathIndex = filename.indexOf("src/test/java/");
        if (testPathIndex >= 0) {
            return Paths.get(filename.substring(testPathIndex));
        }

        int mainPathIndex = filename.indexOf("src/main/java/");
        if (mainPathIndex >= 0) {
            return Paths.get(filename.substring(mainPathIndex));
        }

        if (filename.startsWith("src/")) {
            return Paths.get(filename);
        }

        if (filename.equalsIgnoreCase("pom.xml")) {
            return Paths.get("pom.xml");
        }

        // 2. Java 文件优先根据 package 语句决定 main/test 下的包路径。
        if (filename.endsWith(".java")) {
            String packageName = extractPackageName(safeContent);
            String pureFileName = Paths.get(filename).getFileName().toString();
            boolean isTestFile = isLikelyTestJavaFile(filename, safeContent);
            Path sourceRoot = isTestFile ? Paths.get("src", "test", "java") : Paths.get("src", "main", "java");
            if (packageName != null && !packageName.isEmpty()) {
                return sourceRoot.resolve(packageName.replace('.', '/')).resolve(pureFileName);
            }
            return sourceRoot.resolve(pureFileName);
        }

        // 3. 资源文件统一放到 resources；静态资源放到 static 子目录。
        if (isResourceFile(filename)) {
            if (filename.endsWith(".html") || filename.endsWith(".css") || filename.endsWith(".js")) {
                return Paths.get("src", "main", "resources", "static", filename);
            }
            return Paths.get("src", "main", "resources", filename);
        }

        if (filename.contains("/")) {
            return Paths.get(filename);
        }

        return Paths.get(filename);
    }

    /**
     * 规范化模型返回的文件名；当文件名缺失时，尝试从 Java 类型和 package 中反推出路径。
     */
    private String normalizeFixFilename(String filename, String content) {
        if (!isMissingFilename(filename)) {
            return filename.trim().replace('\\', '/');
        }

        String safeContent = content == null ? "" : content;
        String typeName = extractJavaTypeName(safeContent);
        if (typeName == null || typeName.isBlank()) {
            return "GeneratedFile.java";
        }

        String packageName = extractPackageName(safeContent);
        Path sourceRoot = isLikelyTestJavaFile(typeName + ".java", safeContent)
                ? Paths.get("src", "test", "java")
                : Paths.get("src", "main", "java");
        if (packageName == null || packageName.isBlank()) {
            return sourceRoot.resolve(typeName + ".java").toString().replace('\\', '/');
        }
        return sourceRoot.resolve(packageName.replace('.', '/')).resolve(typeName + ".java").toString()
                .replace('\\', '/');
    }

    /**
     * 判断模型返回的文件名是否缺失或属于占位文件名。
     */
    private boolean isMissingFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return true;
        }
        String pureName = Paths.get(filename.trim().replace('\\', '/')).getFileName().toString();
        return pureName.equalsIgnoreCase("Unknown.java")
                || pureName.equalsIgnoreCase("Unknown")
                || pureName.equalsIgnoreCase("GeneratedFile.java");
    }

    /**
     * 从 Java 源码中提取 package 名，用于推断文件所在包路径。
     */
    private String extractPackageName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 从 Java 源码中提取顶层类型名，用于在文件名缺失时推断文件名。
     */
    private String extractJavaTypeName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile(
                "^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)",
                Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(2) : null;
    }

    /**
     * 判断文件名是否属于 Spring Boot 项目中的资源文件。
     */
    private boolean isResourceFile(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        return lower.endsWith(".properties")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".xml")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".js");
    }

    /**
     * 根据路径和代码内容判断一个 Java 文件是否更像测试文件。
     */
    private boolean isLikelyTestJavaFile(String filename, String content) {
        String safeFilename = filename == null ? "" : filename;
        String safeContent = content == null ? "" : content;
        return safeFilename.contains("src/test/java/")
                || safeFilename.endsWith("Test.java")
                || safeContent.contains("org.junit.jupiter")
                || safeContent.contains("@Test");
    }

    /**
     * 从已经落盘的项目目录中重新读取源码文件，重建内存中的 SourceCode 列表。
     */
    public List<SourceCode> loadProjectFromDisk(Path projectDir) {
        List<SourceCode> result = new ArrayList<>();

        if (projectDir == null || !Files.exists(projectDir)) {
            System.out.println("指定的项目路径不存在: " + projectDir);
            return result;
        }

        try (Stream<Path> stream = Files.walk(projectDir)) {
            // 1. 只读取项目源码和配置文件，跳过构建产物、IDE 目录和常见二进制文件。
            stream.filter(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }

                String pathStr = path.toString().replace('\\', '/');
                if (pathStr.contains("/target/") || pathStr.contains("/.git/") || pathStr.contains("/.idea/")) {
                    return false;
                }

                return !pathStr.endsWith(".class") && !pathStr.endsWith(".jar") && !pathStr.endsWith(".png");
            }).forEach(path -> {
                try {
                    // 2. 将磁盘文件重新转换为 SourceCode，供修复流程继续使用。
                    Path relative = projectDir.relativize(path);
                    String filename = relative.toString().replace('\\', '/');
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String language = detectLanguageFromFilename(filename);
                    result.add(new SourceCode(filename, language, content));
                } catch (IOException e) {
                    System.err.println("无法读取文件: " + path + " | 错误: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("遍历项目目录失败: " + projectDir, e);
        }

        return result;
    }

    /**
     * 根据文件扩展名推断 SourceCode 的语言类型。
     */
    private String detectLanguageFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".properties")) return "properties";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "js";
        return "text";
    }

    /**
     * 在项目目录中递归查找同名文件，用于修复阶段定位模型只返回文件名的情况。
     */
    private Path findExistingFile(Path dir, String pureFileName) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(pureFileName))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 将项目名转换成适合作为目录名的安全片段。
     */
    private String safePathSegment(String value, String fallback) {
        String safeValue = value == null || value.isBlank() ? fallback : value;
        return safeValue.replaceAll("\\s+", "_").replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
