package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将代码保存到本地磁盘
 */
@Service
public class WorkspaceService {

    // 设定一个固定的工作区根目录
    private static final String WORKSPACE_ROOT = "ai_generated_projects";

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
                Path relativePath = resolveSmartPath(sc);

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
     * 🧠 智能路径解析：根据文件内容和类型，决定它应该放在哪里
     */
    private Path resolveSmartPath(SourceCode sc) {
        String filename = sc.filename().trim();
        String content = sc.code();

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
}