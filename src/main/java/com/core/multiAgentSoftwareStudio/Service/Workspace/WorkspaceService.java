package com.core.multiAgentSoftwareStudio.Service.Workspace;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 文件持久化以及文件名、文件路径服务
 */
@Service
public class WorkspaceService {

    private static final String WORKSPACE_ROOT = "ai_generated_projects";

    private final SourceCodePathService sourceCodePathService;

    public WorkspaceService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 将本轮生成出来的所有源码文件保存到一个带时间戳的本地项目目录中。
     */
    public Path saveProjectToDisk(String projectName, List<SourceCode> sourceCodes) {
        return saveProjectToDisk(projectName, sourceCodes, System.out::println);
    }

    /**
     * 将本轮生成出来的所有源码文件保存到一个带时间戳的本地项目目录中，并通过统一日志回调输出过程信息。
     */
    public Path saveProjectToDisk(String projectName, List<SourceCode> sourceCodes, Consumer<String> logger) {
        Path projectDir = initializeProjectWorkspace(projectName, logger);
        writeSourceFilesToDisk(projectDir, sourceCodes, logger);
        return projectDir;
    }

    /**
     * 为一次垂直切片运行只创建一个稳定工作区，后续切片都向该目录增量写入。
     */
    public Path initializeProjectWorkspace(String projectName, Consumer<String> logger) {
        Consumer<String> safeLogger = logger == null ? message -> {
        } : logger;
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = safePathSegment(projectName, "GeneratedProject");
            Path projectDir = Paths.get(WORKSPACE_ROOT, safeProjectName + "_" + timestamp);
            Files.createDirectories(projectDir);
            safeLogger.accept("💾 已初始化切片交付工作区: " + projectDir.toAbsolutePath());
            return projectDir;
        } catch (IOException e) {
            throw new RuntimeException("无法创建本地项目工作区", e);
        }
    }

    /**
     * 将一个切片产生的源码增量写入既有工作区，不创建新的时间戳目录。
     */
    public void writeSourceFilesToDisk(Path projectDir, List<SourceCode> sourceCodes, Consumer<String> logger) {
        Consumer<String> safeLogger = logger == null ? message -> {
        } : logger;
        try {
            if (projectDir == null) {
                throw new IllegalArgumentException("Project workspace path is required");
            }
            Files.createDirectories(projectDir);
            safeLogger.accept("💾 开始增量持久化代码到: " + projectDir.toAbsolutePath());
            List<SourceCode> safeSourceCodes = sourceCodes == null ? List.of() : sourceCodes;
            for (int i = 0; i < safeSourceCodes.size(); i++) {
                SourceCode sourceCode = safeSourceCodes.get(i);
                if (sourceCode == null) {
                    safeLogger.accept("   └── 跳过空文件记录: index=" + i);
                    continue;
                }

                try {
                    // 3. 规范化文件名并解析最终相对路径，必要时从 Java 代码内容推断路径。
                    String filename = sourceCodePathService.normalizeGeneratedFilename(sourceCode.filename(), sourceCode.code());
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
                    safeLogger.accept("   └── 写入文件: " + relativePath + " (原名: " + sourceCode.filename() + ")");
                } catch (Exception e) {
                    throw new RuntimeException("保存第 " + (i + 1) + " 个生成文件失败: filename="
                            + sourceCode.filename() + ", codeIsNull=" + (sourceCode.code() == null), e);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("无法保存代码到本地磁盘", e);
        }
    }

    /**
     * 将候选修复涉及的文件恢复到内存快照版本；候选新增文件则按精确路径删除。
     */
    public void restoreRepairSnapshot(Path projectDir,
            Map<String, String> baseline,
            List<String> changedFiles,
            Consumer<String> logger) {
        Consumer<String> safeLogger = logger == null ? message -> { } : logger;
        if (projectDir == null || baseline == null || changedFiles == null) {
            return;
        }
        Path normalizedRoot = projectDir.toAbsolutePath().normalize();
        try {
            for (String filename : changedFiles) {
                String baselineCode = baseline.get(filename);
                String pathCode = baselineCode == null ? "" : baselineCode;
                Path target = normalizedRoot.resolve(resolveSmartPath(filename, pathCode)).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IllegalArgumentException("Repair rollback path escapes project directory: " + target);
                }
                if (baseline.containsKey(filename)) {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, baselineCode == null ? "" : baselineCode, StandardCharsets.UTF_8);
                    safeLogger.accept("   ↩ 已恢复修复前文件: " + filename);
                } else {
                    // 只删除本次候选修复新建且快照中不存在的精确文件，绝不扩大删除范围。
                    Files.deleteIfExists(target);
                    safeLogger.accept("   ↩ 已移除失败候选新增文件: " + filename);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("恢复候选修复快照失败", e);
        }
    }

    /**
     * 根据模型返回的文件名和代码内容，推断文件应该落到项目内的哪个相对路径。
     */
    private Path resolveSmartPath(String filenameInput, String content) {
        String filename = sourceCodePathService.normalizeGeneratedFilename(filenameInput, content);
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
            String packageName = sourceCodePathService.extractPackageName(safeContent);
            String pureFileName = Paths.get(filename).getFileName().toString();
            boolean isTestFile = sourceCodePathService.isLikelyTestFile(filename, safeContent);
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
     * 从已经落盘的项目目录中重新读取源码文件，重建内存中的 SourceCode 列表。
     */
    public List<SourceCode> loadProjectFromDisk(Path projectDir) {
        return loadProjectFromDisk(projectDir, System.out::println);
    }

    /**
     * 从已经落盘的项目目录中重新读取源码文件，重建内存中的 SourceCode 列表，并通过统一日志回调输出异常信息。
     */
    public List<SourceCode> loadProjectFromDisk(Path projectDir, Consumer<String> logger) {
        Consumer<String> safeLogger = logger == null ? message -> {
        } : logger;
        List<SourceCode> result = new ArrayList<>();

        if (projectDir == null || !Files.exists(projectDir)) {
            safeLogger.accept("指定的项目路径不存在: " + projectDir);
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
                    String language = sourceCodePathService.detectLanguageFromFilename(filename);
                    result.add(new SourceCode(filename, language, content));
                } catch (IOException e) {
                    safeLogger.accept("无法读取文件: " + path + " | 错误: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("遍历项目目录失败: " + projectDir, e);
        }

        return result;
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
