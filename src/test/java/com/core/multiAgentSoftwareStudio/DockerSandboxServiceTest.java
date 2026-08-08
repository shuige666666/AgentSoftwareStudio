package com.core.multiAgentSoftwareStudio;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DockerSandboxService 的集成测试
 * 注意：运行此测试需要本机已安装并启动 Docker Desktop / Docker Engine
 */
class DockerSandboxServiceTest {

    private DockerSandboxService dockerSandboxService;

    @BeforeEach
    void setUp() {
        // 初始化服务
        // 这一步会尝试连接本机的 Docker Daemon，如果 Docker 没开会报错
        try {
            dockerSandboxService = new DockerSandboxService();
        } catch (Exception e) {
            System.err.println("⚠️ 初始化失败，请检查 Docker 是否已启动！");
            throw e;
        }
    }

    @Test
    void testRunSpringbootSnakeGameProject() throws IOException {
        Path projectPath = Paths.get("E:/Study/AI Project Study/SoftwareStudio-5/ai_generated_projects/Web_Snake_Game_20260214_154938");

        System.out.println("🚀 正在启动贪吃蛇项目...");
        System.out.println("⏳ 请耐心等待 Maven 下载依赖和 Spring Boot 启动...");

        // 这里我们启动一个新的线程来运行 Docker，因为 runCodeInSandbox 会阻塞
        // 如果你不开新线程，你就没办法在 main 线程里做“按任意键停止”的逻辑了
        new Thread(() -> {
            dockerSandboxService.runCodeInSandbox(projectPath,"SPRING_BOOT","com.snakegame");
        }).start();

        // --- 核心：给用户留出测试时间 ---

        System.out.println("\n\n========================================");
        System.out.println("🎉 容器正在后台运行中！");
        System.out.println("👉 请打开浏览器访问: http://localhost:8081");
        System.out.println("👉 玩够了之后，请在下方控制台按 [Enter] 键停止测试...");
        System.out.println("========================================\n\n");

        // 阻塞在这里，直到你按下回车
        System.in.read();

        System.out.println("🛑 测试结束，正在关闭容器...");
        // 实际上这里我们没有显式关闭容器的逻辑，因为 DockerClient 的回调比较难在外部打断。
        // 但只要 JUnit 进程结束，Docker 如果配置了 AutoRemove 会自动清理，或者你可以手动去 Docker Desktop 关掉。
    }

    @Test
    void testRunJavaSnakeGameProject() throws IOException {
        Path projectPath = Paths.get("E:/Study/AI Project Study/SoftwareStudio-5/ai_generated_projects/Local_Snake_Game_20260221_234356");

        System.out.println("🚀 正在启动贪吃蛇项目...");
        System.out.println("\n 正在启动 Docker 沙箱...");
        String executionResult = dockerSandboxService.runCodeInSandbox(projectPath,"PURE_JAVA_MAVEN","com.snake.game.Main");

        System.out.println("💡 运行结果:");
        System.out.println("--------------------------------------------------");
        // 如果日志太长，可以只打印前/后几行，这里为了演示全打出来
        System.out.println(executionResult);
        System.out.println("--------------------------------------------------");

    }

}