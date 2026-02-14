package com.core.multiAgentSoftwareStudio.Tool;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public DockerSandboxService(){
        // 初始化 Docker 连接 (默认连接本地 Docker Daemon)
        // 1. 读取配置：自动寻找环境变量或默认路径（如 Linux 下的 /var/run/docker.sock）
        DefaultDockerClientConfig config= DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        // 2. 建立 HTTP 客户端：Docker 实际上是基于 REST API 运作的
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(java.time.Duration.ofSeconds(60)) // 连接超时设为 60秒
                .responseTimeout(java.time.Duration.ofSeconds(120))  // 响应超时设为 120秒
                .build();
        // 3. 创建操作句柄：以后所有 docker 命令都通过这个对象发出
        this.dockerClient= DockerClientImpl.getInstance(config, httpClient);
    }
    /**
     * 运行指定路径下的项目代码
     * @param projectPath 已经持久化到本地的项目根路径
     * @return 运行日志
     */
    public String runCodeInSandbox(Path projectPath){
        // 1. 校验路径
        if (projectPath == null || !Files.exists(projectPath)) {
            return "❌ Error: 项目路径不存在: " + projectPath;
        }
        // 把镜像提取为常量
        String imageName = "maven:3.8.5-openjdk-17-slim";

        // 2.确保镜像存在（若不存在则拉取镜像）
        ensureImageExists(imageName);

        String containerId = null;
        try {
            System.out.println("🐳 准备挂载目录: " + projectPath.toAbsolutePath());
            // 3. 准备 Docker 容器

            // 3.1 准备挂载配置 (HostConfig)
            HostConfig hostConfig = HostConfig.newHostConfig()
                    // Bind(宿主机路径, 容器内路径)
                    // 这行代码打通了两个世界：宿主机的项目路径 和容器内的 /app
                    .withBinds(new Bind(projectPath.toAbsolutePath().toString(), new Volume("/app")))
                    // 🔥【关键修改】端口映射：宿主机 8081 -> 容器 8080
                    .withPortBindings(PortBinding.parse("8081:8080"))
                    .withAutoRemove(true); // 容器停止后自动删除容器本身

            // 3.2. 创建容器
            CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
                    .withWorkingDir("/app")
                    .withHostConfig(hostConfig)
                    .withExposedPorts(ExposedPort.tcp(8080)) // 🔥【关键修改】声明容器内部暴露 8080
                    // 这里假设这是个 Spring Boot 项目，直接跑 mvn 命令
                    // 注意：这会在你的本地 projectPath 下生成 /target 目录
                    .withCmd("sh", "-c", "mvn clean spring-boot:run")
                    .exec();

            containerId = container.getId();

            // 4. 启动容器
            dockerClient.startContainerCmd(containerId).exec();

            // 5. 等待执行结束并获取日志
            // 这里我们使用一个简单的 StringBuilder 来收集日志
            StringBuilder logs = new StringBuilder();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)  // 捕获标准输出
                    .withStdErr(true)  // 捕获错误输出（如编译错误）
                    .withFollowStream(true) // 实时跟随日志流
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                        // 这是一个回调函数，每当容器打印一行字，这里就会被触发一次
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame item) {
                            // logs.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                            String logLine = new String(item.getPayload(), StandardCharsets.UTF_8);
                            logs.append(logLine);
                            // 🔥🔥🔥 关键点：直接打印到 IDEA 控制台，不再闷在 StringBuilder 里
                            System.out.print(logLine);
                        }
                    }).awaitCompletion(10, TimeUnit.MINUTES);

            return logs.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "Docker Execution Error: " + e.getMessage();
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
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
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
}
