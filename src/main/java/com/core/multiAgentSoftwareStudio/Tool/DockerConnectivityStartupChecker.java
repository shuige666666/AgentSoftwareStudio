package com.core.multiAgentSoftwareStudio.Tool;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 在主应用运行时异步检查 Docker 连通性，提前向用户提示沙箱和预览能力是否可用。
 */
@Component
public class DockerConnectivityStartupChecker implements ApplicationRunner {

    private final DockerSandboxService dockerSandboxService;

    public DockerConnectivityStartupChecker(DockerSandboxService dockerSandboxService) {
        this.dockerSandboxService = dockerSandboxService;
    }

    /**
     * 使用虚拟线程执行检查，避免 Docker Desktop 未启动时阻塞 Spring Boot 主启动流程。
     */
    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual()
                .name("docker-connectivity-check")
                .start(this::checkConnectivity);
    }

    /**
     * 执行一次 Docker ping，并在控制台输出面向用户的可操作提示。
     */
    public void checkConnectivity() {
        if (dockerSandboxService.isDockerAvailable()) {
            System.out.println("✅ Docker 连接正常，项目验证与预览功能可用。");
            return;
        }

        System.err.println("⚠️ 无法连接到本地 Docker，请确认 Docker Desktop 已经启动。"
                + "项目生成仍可进入系统，但容器验证与预览功能暂不可用。");
    }
}
