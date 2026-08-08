package com.core.multiAgentSoftwareStudio.Service.Preview;

import com.core.multiAgentSoftwareStudio.Model.Preview.PreviewStatus;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 管理生成项目的可选预览容器，使常驻预览与代码生成、验证和自动修复流程彼此独立。
 */
@Service
public class ProjectPreviewService {

    private static final int PREVIEW_PORT = 8081;
    private static final String PREVIEW_URL = "http://localhost:" + PREVIEW_PORT;
    private static final long STARTUP_TIMEOUT_MILLIS = 120_000;
    private static final Pattern SPRING_BOOT_READY = Pattern.compile(
            "(?m)^.*\\bStarted\\s+[A-Za-z0-9_.$]+\\s+in\\s+\\d.*seconds.*$");

    private final DockerSandboxService dockerSandboxService;
    private final Map<String, PreviewProject> projects = new ConcurrentHashMap<>();
    private final ExecutorService previewExecutor = Executors.newCachedThreadPool();

    public ProjectPreviewService(DockerSandboxService dockerSandboxService) {
        this.dockerSandboxService = dockerSandboxService;
    }

    /**
     * 登记已经完成工作流质量门禁的项目，并返回前端后续操作使用的安全项目标识。
     */
    public String registerSuccessfulProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("生成项目路径为空，无法登记预览。");
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path) || !Files.isRegularFile(path.resolve("pom.xml"))) {
            throw new IllegalArgumentException("生成项目不存在或不是 Maven 项目：" + path);
        }
        try {
            String pom = Files.readString(path.resolve("pom.xml"));
            if (!pom.contains("spring-boot")) {
                throw new IllegalArgumentException("当前生成项目不是 Spring Boot 项目，暂不支持容器预览。");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取生成项目 pom.xml：" + e.getMessage(), e);
        }

        String projectId = path.getFileName().toString();
        projects.put(projectId, new PreviewProject(projectId, path));
        return projectId;
    }

    /**
     * 异步启动项目预览；调用方会立即拿到 STARTING，不等待 Maven 和 Spring Boot 完成启动。
     */
    public PreviewStatus startPreview(String projectId) {
        PreviewProject project = requireProject(projectId);
        synchronized (project) {
            if ("STARTING".equals(project.state) || "RUNNING".equals(project.state)) {
                return project.snapshot();
            }
            project.state = "STARTING";
            project.message = "正在启动预览容器...";
            project.containerId = null;
            project.stopRequested = false;
            previewExecutor.execute(() -> startAndMonitor(project));
            return project.snapshot();
        }
    }

    /**
     * 查询预览状态；如果容器已经意外退出，会同步修正为 FAILED。
     */
    public PreviewStatus getStatus(String projectId) {
        PreviewProject project = requireProject(projectId);
        synchronized (project) {
            if ("RUNNING".equals(project.state)
                    && project.containerId != null
                    && !dockerSandboxService.isContainerRunning(project.containerId)) {
                project.state = "FAILED";
                project.message = "预览容器已意外退出。";
            }
            return project.snapshot();
        }
    }

    /**
     * 停止并删除指定项目的预览容器，释放固定的 8081 端口。
     */
    public PreviewStatus stopPreview(String projectId) {
        PreviewProject project = requireProject(projectId);
        synchronized (project) {
            if (project.containerId != null) {
                dockerSandboxService.stopAndRemoveContainer(project.containerId);
                project.containerId = null;
            }
            project.stopRequested = true;
            project.state = "STOPPED";
            project.message = "预览服务已停止。";
            return project.snapshot();
        }
    }

    private void startAndMonitor(PreviewProject project) {
        String containerId = null;
        try {
            containerId = dockerSandboxService.startSpringBootPreview(project.path, PREVIEW_PORT);
            synchronized (project) {
                // 用户可能在 Docker 创建期间点击停止，容器创建完成后必须兑现取消请求。
                if (project.stopRequested) {
                    dockerSandboxService.stopAndRemoveContainer(containerId);
                    return;
                }
                project.containerId = containerId;
            }

            long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                if (!dockerSandboxService.isContainerRunning(containerId)) {
                    throw new IllegalStateException("预览容器在 Spring Boot 就绪前退出。\n"
                            + dockerSandboxService.getContainerLogs(containerId));
                }
                synchronized (project) {
                    if (project.stopRequested) {
                        dockerSandboxService.stopAndRemoveContainer(containerId);
                        project.containerId = null;
                        return;
                    }
                }
                String logs = dockerSandboxService.getContainerLogs(containerId);
                if (SPRING_BOOT_READY.matcher(logs).find()) {
                    synchronized (project) {
                        project.state = "RUNNING";
                        project.message = "预览服务已启动：" + PREVIEW_URL;
                    }
                    return;
                }
                Thread.sleep(1_000);
            }
            throw new IllegalStateException("等待 Spring Boot 启动超时。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(project, "预览启动被中断。", containerId);
        } catch (Exception e) {
            markFailed(project, conciseMessage(e), containerId);
        }
    }

    private void markFailed(PreviewProject project, String message, String containerId) {
        if (containerId != null) {
            dockerSandboxService.stopAndRemoveContainer(containerId);
        }
        synchronized (project) {
            project.containerId = null;
            project.state = "FAILED";
            project.message = message;
        }
    }

    private PreviewProject requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空。");
        }
        PreviewProject project = projects.get(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目尚未通过生成工作流，不能启动预览：" + projectId);
        }
        return project;
    }

    private String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "预览容器启动失败。";
        }
        int lineBreak = message.indexOf('\n');
        return lineBreak >= 0 ? message.substring(0, lineBreak) : message;
    }

    /**
     * 应用退出时清理仍在运行的预览容器，避免遗留端口和 Docker 资源。
     */
    @PreDestroy
    public void shutdown() {
        projects.values().forEach(project -> {
            synchronized (project) {
                if (project.containerId != null) {
                    dockerSandboxService.stopAndRemoveContainer(project.containerId);
                    project.containerId = null;
                }
            }
        });
        previewExecutor.shutdownNow();
    }

    private static final class PreviewProject {
        private final String projectId;
        private final Path path;
        private String state = "READY";
        private String message = "项目已通过基础验证，可以启动预览。";
        private String containerId;
        private boolean stopRequested;

        private PreviewProject(String projectId, Path path) {
            this.projectId = projectId;
            this.path = path;
        }

        private PreviewStatus snapshot() {
            String url = "RUNNING".equals(state) ? PREVIEW_URL : null;
            return new PreviewStatus(projectId, state, url, message);
        }
    }
}
