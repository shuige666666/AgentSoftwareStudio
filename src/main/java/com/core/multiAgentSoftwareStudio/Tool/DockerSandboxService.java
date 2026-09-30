package com.core.multiAgentSoftwareStudio.Tool;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Docker 打包运行工具
 */
@Service
public class DockerSandboxService {

    /** 一次开发会话复用的受限容器；正式验证前关闭以终止可能遗留的后台进程。 */
    public static final class WorkspaceShell {
        private final String containerId;
        private final Path projectRoot;
        private boolean closed;

        private WorkspaceShell(String containerId, Path projectRoot) {
            this.containerId = containerId;
            this.projectRoot = projectRoot;
        }

        public String containerId() { return containerId; }
        public Path projectRoot() { return projectRoot; }
        public boolean closed() { return closed; }
    }

    public record WorkspaceCommandResult(SandboxExecutionResult execution, String logPath) { }

    private final DockerClient dockerClient;

    public DockerSandboxService() {
        // 初始化 Docker 连接 (默认连接本地 Docker Daemon)
        // 1. 读取配置：自动寻找环境变量或默认路径（如 Linux 下的 /var/run/docker.sock）
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        // 2. 建立 HTTP 客户端：Docker 实际上是基于 REST API 运作的
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(java.time.Duration.ofSeconds(60)) // 连接超时设为 60秒
                .responseTimeout(java.time.Duration.ofMinutes(6)) // 依赖预取可能长时间无 Docker 日志
                .build();
        // 3. 创建操作句柄：以后所有 docker 命令都通过这个对象发出
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 轻量检查本地 Docker Daemon 是否可访问；检查失败只返回 false，不影响主应用启动。
     */
    public boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 仅挂载当前项目和只读 Maven 仓库，创建断网、非 root 的持久开发容器。
     */
    public WorkspaceShell startWorkspaceShell(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Project workspace does not exist: " + root);
        // 脚手架构建容器以 root 写出的 target 会阻止非 root Shell 编译；只清理可再生构建产物。
        clearScaffoldTarget(root);
        String image = "maven:3.8.5-openjdk-17-slim";
        ensureImageExists(image);
        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(root.toString(), new Volume("/workspace"), AccessMode.rw));
        Path metadata = root.resolve(".software-studio");
        if (Files.isDirectory(metadata)) {
            // 平台规划与验证资料不可由候选 Bash 修改。
            binds.add(new Bind(metadata.toString(), new Volume("/workspace/.software-studio"), AccessMode.ro));
        }
        Path repository = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        if (Files.isDirectory(repository)) {
            binds.add(new Bind(repository.toAbsolutePath().toString(),
                    new Volume("/usr/share/maven/ref/repository"), AccessMode.ro));
        }
        HostConfig host = HostConfig.newHostConfig()
                .withBinds(binds)
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,size=256m"))
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withCapDrop(Capability.ALL)
                .withPidsLimit(128L)
                .withMemory(1_073_741_824L)
                .withMemorySwap(1_073_741_824L)
                .withAutoRemove(false);
        String id = dockerClient.createContainerCmd(image)
                .withUser("1000:1000")
                .withWorkingDir("/workspace")
                // 容器只有 128 个进程额度，限制 JVM 可见处理器数以免测试启动过多线程。
                .withEnv("HOME=/tmp", "MAVEN_OPTS=-Djansi.mode=off -Dmaven.repo.local=/usr/share/maven/ref/repository",
                        "JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2")
                .withHostConfig(host)
                .withCmd("sleep", "infinity")
                .exec().getId();
        try {
            dockerClient.startContainerCmd(id).exec();
            return new WorkspaceShell(id, root);
        } catch (RuntimeException e) {
            stopAndRemoveContainer(id);
            throw e;
        }
    }

    /** 仅在已核对的候选目录内删除脚手架 target，避免容器用户权限不一致。 */
    private void clearScaffoldTarget(Path root) {
        Path target = root.resolve("target").toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("Unsafe scaffold build directory: " + target);
        }
        if (!Files.exists(target)) return;
        try {
            Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory,
                        IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to clear scaffold target for non-root shell", e);
        }
    }

    /**
     * 每条命令显式指定工作目录和超时；完整输出另存平台日志，返回值只承载有界摘要。
     */
    public WorkspaceCommandResult runWorkspaceCommand(
            WorkspaceShell shell, String command, String cwd, long timeoutMillis) {
        long started = System.nanoTime();
        if (shell == null || shell.closed()) throw new IllegalStateException("Workspace shell is closed");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Shell command is required");
        String requestedCwd = cwd == null ? "" : cwd.replace('\\', '/');
        // 模型常用容器内绝对路径；将 /workspace 映射到候选根目录后仍做宿主路径边界检查。
        if (requestedCwd.equals("/workspace")) requestedCwd = "";
        else if (requestedCwd.startsWith("/workspace/")) requestedCwd = requestedCwd.substring(11);
        Path relative = requestedCwd.isBlank() ? Path.of("") : Path.of(requestedCwd).normalize();
        Path directory = shell.projectRoot().resolve(relative).normalize();
        if (relative.isAbsolute() || !directory.startsWith(shell.projectRoot()) || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Shell cwd must be an existing project directory: " + cwd);
        }
        String containerCwd = "/workspace" + (relative.toString().isBlank()
                ? "" : "/" + relative.toString().replace('\\', '/'));
        Path logPath = Paths.get("studio-run-logs", shell.projectRoot().getFileName().toString(),
                "shell", UUID.randomUUID() + ".log").toAbsolutePath().normalize();
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "", StandardCharsets.UTF_8);
            String execId = dockerClient.execCreateCmd(shell.containerId())
                    .withAttachStdout(true).withAttachStderr(true)
                    .withWorkingDir(containerCwd)
                    // 非登录 shell 保留镜像的 Java PATH；仍用 pipefail 防止 tail 掩盖构建失败。
                    .withCmd("bash", "-o", "pipefail", "-c", command)
                    .exec().getId();
            WorkspaceOutputPreview preview = new WorkspaceOutputPreview();
            var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                @Override public void onNext(Frame frame) {
                    String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
                    try {
                        Files.writeString(logPath, chunk, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to save shell output", e);
                    }
                    preview.append(chunk);
                }
            };
            dockerClient.execStartCmd(execId).exec(callback);
            long boundedTimeout = Math.max(1_000, Math.min(timeoutMillis, 300_000));
            boolean finished = callback.awaitCompletion(boundedTimeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时后销毁整个会话容器，避免后台子进程继续修改宿主挂载目录。
                closeWorkspaceShell(shell);
                return new WorkspaceCommandResult(new SandboxExecutionResult(command, null,
                        preview.snapshot(), elapsedMillis(started), true, null), logPath.toString());
            }
            Long code = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new WorkspaceCommandResult(new SandboxExecutionResult(command,
                    code == null ? null : code.intValue(), preview.snapshot(), elapsedMillis(started),
                    false, null), logPath.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeWorkspaceShell(shell);
            return new WorkspaceCommandResult(new SandboxExecutionResult(command, null, "Interrupted",
                    elapsedMillis(started), true, "Shell command interrupted"), logPath.toString());
        } catch (Exception e) {
            closeWorkspaceShell(shell);
            return new WorkspaceCommandResult(new SandboxExecutionResult(command, null,
                    String.valueOf(e.getMessage()), elapsedMillis(started), false,
                    "Shell execution failed: " + e.getMessage()), logPath.toString());
        }
    }

    /** 限制模型可见输出大小，同时保留 Maven 等长命令结束时的真实失败清单。 */
    static final class WorkspaceOutputPreview {
        private static final int MAX_CHARS = 32_000;
        private static final int HEAD_CHARS = 8_000;
        private static final int TAIL_CHARS = 23_000;
        private final StringBuilder head = new StringBuilder();
        private StringBuilder tail;

        void append(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            if (tail == null) {
                head.append(chunk);
                if (head.length() <= MAX_CHARS) return;
                // 超过预览上限后切换到首尾窗口，完整输出仍保存在独立日志。
                tail = new StringBuilder(head.substring(head.length() - TAIL_CHARS));
                head.setLength(HEAD_CHARS);
            } else {
                tail.append(chunk);
                if (tail.length() > TAIL_CHARS) {
                    tail.delete(0, tail.length() - TAIL_CHARS);
                }
            }
        }

        String snapshot() {
            return tail == null ? head.toString()
                    : head + "\n...[middle truncated; full output in shell log]...\n" + tail;
        }
    }

    /** 关闭会话并清理全部在途命令及后台进程。 */
    public void closeWorkspaceShell(WorkspaceShell shell) {
        if (shell == null || shell.closed()) return;
        shell.closed = true;
        stopAndRemoveContainer(shell.containerId());
    }

    /** 在断网开发容器中执行正式 Maven 命令，并返回完整日志供测试结果解析。 */
    public SandboxExecutionResult runCheckpointMaven(Path source, String command) {
        WorkspaceShell shell = null;
        try {
            shell = startWorkspaceShell(source);
            WorkspaceCommandResult result = runWorkspaceCommand(shell, command, "/workspace", 300_000);
            String output = Files.readString(Path.of(result.logPath()), StandardCharsets.UTF_8);
            SandboxExecutionResult execution = result.execution();
            return new SandboxExecutionResult(command, execution.exitCode(), output,
                    execution.durationMillis(), execution.timedOut(), execution.infrastructureError());
        } catch (IOException | RuntimeException e) {
            return new SandboxExecutionResult(command, null, String.valueOf(e.getMessage()), 0,
                    false, "Isolated checkpoint execution failed: " + e.getMessage());
        } finally {
            closeWorkspaceShell(shell);
        }
    }

    /**
     * 缺依赖时仅由平台执行 Maven 预取：候选只读、无个人配置或凭据，所有仓库请求镜像到固定来源。
     */
    public SandboxExecutionResult prefetchCheckpointDependencies(Path source) {
        long started = System.nanoTime();
        String command = "mvn -B -ntp -s /platform/settings.xml "
                + "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:go-offline";
        String image = "maven:3.8.5-openjdk-17-slim";
        String containerId = null;
        Path settings = null;
        StringBuilder output = new StringBuilder();
        try {
            ensureImageExists(image);
            Path repository = Paths.get(System.getProperty("user.home"), ".m2", "repository")
                    .toAbsolutePath().normalize();
            Files.createDirectories(repository);
            settings = Files.createTempFile("studio-maven-settings-", ".xml");
            Files.writeString(settings, """
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                      <mirrors><mirror><id>studio-central</id><mirrorOf>*</mirrorOf>
                        <url>https://repo.maven.apache.org/maven2</url>
                      </mirror></mirrors>
                    </settings>
                    """, StandardCharsets.UTF_8);
            List<Bind> binds = List.of(
                    new Bind(source.toAbsolutePath().normalize().toString(), new Volume("/workspace"), AccessMode.ro),
                    new Bind(repository.toString(), new Volume("/maven-repository"), AccessMode.rw),
                    new Bind(settings.toString(), new Volume("/platform/settings.xml"), AccessMode.ro));
            HostConfig host = HostConfig.newHostConfig().withBinds(binds)
                    .withReadonlyRootfs(true).withTmpFs(Map.of("/tmp", "rw,nosuid,size=256m"))
                    .withSecurityOpts(List.of("no-new-privileges:true"))
                    .withCapDrop(Capability.ALL).withPidsLimit(128L)
                    .withMemory(1_073_741_824L).withMemorySwap(1_073_741_824L)
                    .withAutoRemove(false);
            containerId = dockerClient.createContainerCmd(image)
                    .withWorkingDir("/workspace")
                    .withEnv("HOME=/tmp", "MAVEN_OPTS=-Djansi.mode=off -Dmaven.repo.local=/maven-repository "
                            + "-Dmaven.wagon.http.connectionTimeout=10000 -Dmaven.wagon.http.readTimeout=30000")
                    .withHostConfig(host).withCmd("sh", "-c", command).exec().getId();
            dockerClient.startContainerCmd(containerId).exec();
            var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                @Override public void onNext(Frame frame) {
                    output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            };
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withFollowStream(true).exec(callback);
            WaitContainerResultCallback wait = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(wait);
            Integer exit = wait.awaitStatusCode(5, TimeUnit.MINUTES);
            boolean timedOut = exit == null;
            if (timedOut) stopAndRemoveContainer(containerId);
            callback.awaitCompletion(30, TimeUnit.SECONDS);
            String log = output.toString();
            String lower = log.toLowerCase(java.util.Locale.ROOT);
            // 仓库连接故障属于环境阻断，缺失坐标仍作为构建反馈交给开发会话。
            String infrastructureError = lower.contains("could not transfer artifact")
                    || lower.contains("unknown host") || lower.contains("connection timed out")
                    ? "Trusted Maven repository unavailable" : null;
            return new SandboxExecutionResult(command, exit, log, elapsedMillis(started),
                    timedOut, infrastructureError);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new SandboxExecutionResult(command, null, output + "\n" + e.getMessage(),
                    elapsedMillis(started), false, "Maven dependency prefetch failed: " + e.getMessage());
        } finally {
            if (containerId != null) stopAndRemoveContainer(containerId);
            if (settings != null) {
                try { Files.deleteIfExists(settings); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * 运行指定路径下的项目代码
     * 
     * @param projectPath 已经持久化到本地的项目根路径
     * @param projectType 项目类型 (例如: "SPRING_BOOT", "PURE_JAVA_MAVEN",
     *                    "PURE_JAVA_NATIVE")
     * @param mainClass   主类全限定名 (纯Java项目必须提供，如 "com.example.Main")
     * @return 运行日志
     */
    public String runCodeInSandbox(Path projectPath, String projectType, String mainClass) {
        return runCodeInSandboxWithResult(projectPath, projectType, mainClass).output();
    }

    /**
     * 在沙箱中构建生成项目，并同时返回退出码、耗时和基础设施错误。
     */
    public SandboxExecutionResult runCodeInSandboxWithResult(Path projectPath, String projectType, String mainClass) {
        String cmd;
        boolean needsPortBinding = false;

        if ("SPRING_BOOT".equals(projectType)) {
            // spring-boot:run 启动成功后进程会常驻，验证节点会一直等待日志流结束。
            // 这里用 package 做最终编译检查，让沙箱命令在成功后主动退出。
            cmd = "mvn -DskipTests package";
        } else if ("PURE_JAVA_MAVEN".equals(projectType)) {
            cmd = "apt-get update && apt-get install -y xvfb && xvfb-run mvn compile exec:java -Dexec.mainClass=\""
                    + mainClass + "\"";
        } else {
            cmd = String.format(
                    "find . -name \"*.java\" > sources.txt && javac -d . @sources.txt && java -cp . %s",
                    mainClass);
        }
        return executeInDockerWithResult(projectPath, projectType, cmd, needsPortBinding);
    }

    /**
     * 在修复会话中执行最小生产源码编译，快速把最新编译器证据反馈给修复器。
     */
    public SandboxExecutionResult runCompileInSandboxWithResult(Path projectPath, String projectType) {
        String cmd = "SPRING_BOOT".equals(projectType) || "PURE_JAVA_MAVEN".equals(projectType)
                ? "mvn -DskipTests compile"
                : "find . -name \"*.java\" > sources.txt && javac -d . @sources.txt";
        return executeInDockerWithResult(projectPath, projectType, cmd, false);
    }

    /**
     * 新增：在沙箱中运行测试
     */
    public String runTestsInSandbox(Path projectPath, String projectType) {
        return runTestsInSandboxWithResult(projectPath, projectType).output();
    }

    /**
     * 在沙箱中执行测试，并保留测试命令的真实退出码和耗时。Y
     *
     */
    public SandboxExecutionResult runTestsInSandboxWithResult(Path projectPath, String projectType) {
        return runTestsInSandboxWithResult(projectPath, projectType, List.of());
    }

    /**
     * 执行当前切片及已验收切片的聚焦回归测试；空集合仍表示最终全量测试。
     */
    public SandboxExecutionResult runTestsInSandboxWithResult(
            Path projectPath,
            String projectType,
            List<String> selectedTestFiles) {
        String cmd;
        if ("SPRING_BOOT".equals(projectType) || "PURE_JAVA_MAVEN".equals(projectType)) {
            String selectors = selectedTestFiles == null ? "" : selectedTestFiles.stream()
                    .map(path -> Path.of(path.replace('\\', '/')).getFileName().toString())
                    .map(name -> name.replaceFirst("\\.java$", ""))
                    .filter(name -> name.matches("[A-Za-z0-9_$]+"))
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            cmd = selectors.isBlank() ? "mvn test" : "mvn -Dtest=" + selectors + " test";
        } else {
            // PURE_JAVA_NATIVE 的测试比较复杂，暂且尝试运行所有带 Test 结尾的类
            cmd = "find . -name \"*.java\" > sources.txt && javac -d . @sources.txt && java -cp . org.junit.runner.JUnitCore $(find . -name \"*Test.class\" | sed 's/\\.\\///;s/\\.class//;s/\\//./g')";
        }
        return executeInDockerWithResult(projectPath, projectType, cmd, false);
    }

    /**
     * 启动一个常驻的 Spring Boot 预览容器，只负责创建和启动，不等待应用进程退出。
     */
    public String startSpringBootPreview(Path projectPath, int hostPort) {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("项目路径不存在：" + projectPath);
        }

        String imageName = "maven:3.8.5-openjdk-17-slim";
        ensureImageExists(imageName);

        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(projectPath.toAbsolutePath().toString(), new Volume("/app")));
        Path hostMavenRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        binds.add(new Bind(hostMavenRepo.toAbsolutePath().toString(), new Volume("/root/.m2/repository")));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(binds)
                .withPortBindings(PortBinding.parse(hostPort + ":8080"))
                .withAutoRemove(false);

        CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
                .withWorkingDir("/app")
                .withHostConfig(hostConfig)
                .withExposedPorts(ExposedPort.tcp(8080))
                .withCmd("sh", "-c", "mvn spring-boot:run")
                .exec();
        try {
            dockerClient.startContainerCmd(container.getId()).exec();
            return container.getId();
        } catch (RuntimeException e) {
            dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
            throw e;
        }
    }

    /**
     * 获取预览容器截至当前的完整日志，用于判断 Spring Boot 是否已经就绪。
     */
    public String getContainerLogs(String containerId) {
        StringBuilder logs = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame item) {
                            logs.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(5, TimeUnit.SECONDS);
            return logs.toString();
        } catch (Exception e) {
            return "无法读取容器日志：" + e.getMessage();
        }
    }

    /**
     * 检查容器是否仍在运行；容器不存在时同样返回 false。
     */
    public boolean isContainerRunning(String containerId) {
        try {
            return Boolean.TRUE.equals(dockerClient.inspectContainerCmd(containerId).exec().getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 停止并强制删除预览容器，确保异常启动也能释放端口。
     */
    public void stopAndRemoveContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            if (isContainerRunning(containerId)) {
                dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
            }
        } catch (Exception ignored) {
            // 容器可能已自行退出，仍继续尝试删除。
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception ignored) {
            // 重复停止保持幂等，容器不存在时无需继续抛错。
        }
    }

    private SandboxExecutionResult executeInDockerWithResult(
            Path projectPath,
            String projectType,
            String cmd,
            boolean needsPortBinding) {
        long startedNanos = System.nanoTime();
        // 1. 校验路径
        if (projectPath == null || !Files.exists(projectPath)) {
            String error = "项目路径不存在: " + projectPath;
            return new SandboxExecutionResult(cmd, null, error, elapsedMillis(startedNanos), false, error);
        }

        String imageName = ("SPRING_BOOT".equals(projectType) || "PURE_JAVA_MAVEN".equals(projectType))
                ? "maven:3.8.5-openjdk-17-slim"
                : "eclipse-temurin:17-jdk-alpine";

        String containerId = null;
        try {
            // 镜像检查和拉取也属于 Docker 基础设施阶段，失败时必须进入结构化结果。
            ensureImageExists(imageName);
            System.out.println("🐳 准备挂载目录: " + projectPath.toAbsolutePath());

            // 3.1 准备基础挂载配置 (代码挂载)
            List<Bind> binds = new ArrayList<>();
            binds.add(new Bind(projectPath.toAbsolutePath().toString(), new Volume("/app")));

            // 如果用到了 Maven 镜像，才挂载本地 Maven 仓库
            if (imageName.startsWith("maven")) {
                String userHome = System.getProperty("user.home");
                Path hostMavenRepo = Paths.get(userHome, ".m2", "repository");
                binds.add(new Bind(hostMavenRepo.toAbsolutePath().toString(), new Volume("/root/.m2/repository")));
            }

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(binds)
                    // 先保留已退出容器，确保能够稳定读取退出码；finally 中再统一清理。
                    .withAutoRemove(false);

            // 如果是 Spring Boot 项目，才映射端口
            if (needsPortBinding) {
                hostConfig.withPortBindings(PortBinding.parse("8081:8080"));
            }

            // 3.2. 创建容器
            var createCmd = dockerClient.createContainerCmd(imageName)
                    .withWorkingDir("/app")
                    .withHostConfig(hostConfig)
                    .withCmd("sh", "-c", cmd);

            // 只有 Spring Boot 才暴露端口
            if (needsPortBinding) {
                createCmd.withExposedPorts(ExposedPort.tcp(8080));
            }

            CreateContainerResponse container = createCmd.exec();
            containerId = container.getId();

            // 4. 启动容器
            dockerClient.startContainerCmd(containerId).exec();

            // 5. 等待执行结束并获取日志
            // 这里我们使用一个简单的 StringBuilder 来收集日志
            StringBuilder logs = new StringBuilder();
            var logCallback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame item) {
                    String logLine = new String(item.getPayload(), StandardCharsets.UTF_8);
                    logs.append(logLine);
                    System.out.print(logLine);
                }
            };
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true) // 捕获标准输出
                    .withStdErr(true) // 捕获错误输出（如编译错误）
                    .withFollowStream(true) // 实时跟随日志流
                    .exec(logCallback);

            // 容器退出码是验证成功的唯一硬依据；日志只用于分类和诊断。
            WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(waitCallback);
            Integer exitCode = waitCallback.awaitStatusCode(10, TimeUnit.MINUTES);
            boolean timedOut = exitCode == null;
            if (timedOut) {
                dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
            }
            logCallback.awaitCompletion(30, TimeUnit.SECONDS);

            return new SandboxExecutionResult(
                    cmd, exitCode, logs.toString(), elapsedMillis(startedNanos), timedOut, null);

        } catch (Exception e) {
            e.printStackTrace();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String error = "Docker Execution Error: " + e.getMessage();
            return new SandboxExecutionResult(
                    cmd, null, error, elapsedMillis(startedNanos), false, error);
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignored) {
                    // 容器可能已被 Docker 清理；验证结果已经保留，不覆盖原始失败。
                }
            }
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 新增：确保镜像存在的工具方法
     */
    private void ensureImageExists(String imageName) {
        try {
            // 1. 先尝试检查本地是否有该镜像
            dockerClient.inspectImageCmd(imageName).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // 2. 如果没有 (404)，则开始下载
            System.out.println("⚠️ 本地未找到镜像 " + imageName + "，正在拉取中 (这可能需要几分钟)...");
            try {
                dockerClient.pullImageCmd(imageName)
                        .start()
                        .awaitCompletion(60, TimeUnit.SECONDS); // 阻塞等待下载完成
                System.out.println("✅ 镜像拉取完成: " + imageName);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("镜像拉取被中断", ie);
            }
        }
    }

    /**
     * 递归删除目录及其下所有文件
     * （之前用于删除临时目录的，现在持久化项目代码了那就暂时用不上了）
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        // 检查路径是否存在
        if (Files.notExists(path)) {
            return;
        }

        // 使用 walkFileTree 遍历文件树
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<Path>() {
            // 先删除文件
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            // 后删除目录（当目录里的文件都删空了之后）
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
