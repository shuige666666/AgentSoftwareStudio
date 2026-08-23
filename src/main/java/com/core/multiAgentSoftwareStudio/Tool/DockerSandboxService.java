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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Docker 打包运行工具
 */
@Service
public class DockerSandboxService {

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
                .responseTimeout(java.time.Duration.ofSeconds(120)) // 响应超时设为 120秒
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
