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

/**
 * 将代码保存到本地磁盘
 */
@Service
public class WorkspaceService {

    // 设定一个固定的工作区根目录
    private static final String WORKSPACE_ROOT = "ai_generated_projects";

    /**
     * 把项目文件写入磁盘文件夹里
     * @param projectName
     * @param sourceCodes
     * @return
     */
    public Path saveProjectToDisk(String projectName, List<SourceCode> sourceCodes) {
        try {
            // 1. 生成带时间戳的项目目录
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = projectName.replaceAll("\\s+", "_");
            Path projectDir = Paths.get(WORKSPACE_ROOT, safeProjectName + "_" + timestamp);

            if (!Files.exists(projectDir)) {
                Files.createDirectories(projectDir);
            }

            System.out.println("💾 开始持久化代码到: " + projectDir.toAbsolutePath());

            // 2. 遍历写入文件 (使用智能路径策略)
            for (SourceCode sc : sourceCodes) {

                // 🔥 核心修改：调用智能路径解析方法
                Path relativePath = resolveSmartPath(sc.filename(),sc.code());

                // 拼接完整路径
                Path finalPath = projectDir.resolve(relativePath);

                // 确保父目录存在
                if (finalPath.getParent() != null) {
                    Files.createDirectories(finalPath.getParent());
                }

                // 写入
                Files.writeString(finalPath, sc.code(), StandardCharsets.UTF_8);
                System.out.println("   └── 写入文件: " + relativePath + " (原名: " + sc.filename() + ")");
            }

            return projectDir;

        } catch (IOException e) {
            throw new RuntimeException("无法保存代码到本地磁盘", e);
        }
    }

    /**
     * 将修复后的代码覆盖写入本地工作区
     */
    public void applyFixesToDisk(Path projectDir, List<CodeFix> fixes) {
        try {
            for (CodeFix fix : fixes) {
                String pureFileName = Paths.get(fix.filename()).getFileName().toString();

                // 🕵️ 增强防御：先在项目目录里寻找这个文件原来的位置
                Path existingFilePath = findExistingFile(projectDir, pureFileName);
                Path targetPath;

                if (existingFilePath != null) {
                    // 如果找到了原文件，直接使用原文件的绝对路径覆盖！不管 AI 有没有写 package
                    targetPath = existingFilePath;
                } else {
                    // 只有当这是一个全新的文件时，才去根据内容智能解析路径
                    Path relativePath = resolveSmartPath(pureFileName, fix.newCode());
                    targetPath = projectDir.resolve(relativePath);
                }

                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }

                Files.writeString(targetPath, fix.newCode(), StandardCharsets.UTF_8);
                System.out.println("   🔧 已覆盖修复文件: " + targetPath + " (AI原输出名: " + fix.filename() + ")");
            }
        } catch (IOException e) {
            throw new RuntimeException("应用代码修复失败", e);
        }
    }

    /**
     * 🧠 智能路径解析：根据文件内容和类型，决定它应该放在哪里
     */
    private Path resolveSmartPath(String filenameInput, String content) {
        String filename = filenameInput.trim();

        // 1. 如果是 pom.xml，必须在根目录
        if (filename.equalsIgnoreCase("pom.xml")) {
            return Paths.get("pom.xml");
        }

        // 2. 如果是 Java 文件，通过 package 语句自动计算路径
        if (filename.endsWith(".java")) {
            // 使用正则提取 package xxx.xxx.xxx;
            String packageName = extractPackageName(content);
            if (packageName != null && !packageName.isEmpty()) {
                // 将包名转换为路径: com.game -> com/game
                String packagePath = packageName.replace('.', '/');
                // 强制加上 Maven 标准头: src/main/java/com/game/Main.java
                return Paths.get("src", "main", "java", packagePath, filename);
            }
            // 如果没找到 package，至少放到 src/main/java 根下
            return Paths.get("src", "main", "java", filename);
        }

        // 3. 如果是资源文件 (properties, yml, html, css, js)
        if (isResourceFile(filename)) {
            // 如果是静态资源 (html, css, js)，通常放在 static 目录下
            if (filename.endsWith(".html") || filename.endsWith(".css") || filename.endsWith(".js")) {
                return Paths.get("src", "main", "resources", "static", filename);
            }
            // 其他配置放在 resources 根下
            return Paths.get("src", "main", "resources", filename);
        }

        // 4. 如果 AI 已经很聪明地给了全路径 (包含 src/)，那就信它
        if (filename.startsWith("src/") || filename.contains("/")) {
            return Paths.get(filename);
        }

        // 5. 兜底：直接返回文件名（还是会放在根目录，但总比报错好）
        return Paths.get(filename);
    }

    /**
     * 从 Java 代码中提取包名
     */
    private String extractPackageName(String code) {
        // 正则匹配: package com.example.demo;
        Pattern pattern = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 判断是否为资源文件
     */
    private boolean isResourceFile(String filename) {
        return filename.endsWith(".properties") ||
                filename.endsWith(".yml") ||
                filename.endsWith(".yaml") ||
                filename.endsWith(".xml") ||
                filename.endsWith(".html") ||
                filename.endsWith(".css") ||
                filename.endsWith(".js");
    }

    /**
     * 读取已有项目文件，返回 List<SourceCode>，用于在测试中重建内存中的代码列表
     */
    public List<SourceCode> loadProjectFromDisk(Path projectDir) {
        List<SourceCode> result = new ArrayList<>();

        if (projectDir == null || !Files.exists(projectDir)) {
            System.out.println("⚠️ 指定的项目路径不存在: " + projectDir);
            return result;
        }

        try (Stream<Path> stream = Files.walk(projectDir)) {
            stream.filter(path -> {
                        // 1. 必须是普通文件
                        if (!Files.isRegularFile(path)) return false;

                        String pathStr = path.toString().replace('\\', '/');
                        // 2. 排除构建产物和隐藏目录 (例如 target, .git, .idea, bin)
                        if (pathStr.contains("/target/") || pathStr.contains("/.git/") || pathStr.contains("/.idea/")) {
                            return false;
                        }

                        // 3. 排除已知的二进制后缀 (比如 .class, .jar, .png)
                        return !pathStr.endsWith(".class") && !pathStr.endsWith(".jar") && !pathStr.endsWith(".png");
                    })
                    .forEach(path -> {
                        try {
                            Path relative = projectDir.relativize(path);
                            String filename = relative.toString().replace('\\', '/');

                            // 读取内容
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            String language = detectLanguageFromFilename(filename);

                            result.add(new SourceCode(filename, language, content));
                        } catch (IOException e) {
                            // 这里可以选择跳过单个文件而不是崩溃
                            System.err.println("❌ 无法读取文件 (可能是二进制或损坏): " + path + " | 错误: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("遍历项目目录失败: " + projectDir, e);
        }

        return result;
    }

    /**
     * 根据文件名后缀简单判断语言
     */
    private String detectLanguageFromFilename(String filename) {
        String lower = filename.toLowerCase();
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
     * 辅助方法：在指定目录下递归查找某个纯文件名对应的实际路径
     */
    private Path findExistingFile(Path dir, String pureFileName) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(pureFileName))
                    .findFirst()
                    .orElse(null);
        }
    }
}

