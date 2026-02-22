package com.core.multiAgentSoftwareStudio;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.TesterAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.*;
import com.core.multiAgentSoftwareStudio.Service.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootTest
public class CodeFixTest {

    @Autowired
    private TesterAgent testerAgent;

    @Autowired
    private DockerSandboxService sandboxService;

    @Autowired
    private WorkspaceService workspaceService;


    @Test
    public void codeFixLoopTest(){
        int maxRetries = 5; // 设置最大重试次数，防止无限死循环破产
        int currentAttempt = 1;
        boolean isSuccess = false;
        Path projectPath = Paths.get("E:/Study/AI Project Study/SoftwareStudio-5/ai_generated_projects/SimpleCalculatorAndConverter_20260222_201605");

        // 读取磁盘上的代码文件，恢复成 List<SourceCode>
        List<SourceCode> codes = workspaceService.loadProjectFromDisk(projectPath);

        System.out.println("🐳 进入沙箱运行与测试循环...");

        while (currentAttempt <= maxRetries && !isSuccess) {
            System.out.println("\n▶️ [第 " + currentAttempt + " 次尝试] 正在启动 Docker 沙箱...");
            String executionResult = sandboxService.runCodeInSandbox(
                    projectPath,
                    "SPRING_BOOT",
                    "com.calculator.converter.CalculatorApplication"
            );

            System.out.println("💡 运行结果:");
            System.out.println("--------------------------------------------------");
            // 如果日志太长，可以只打印前/后几行，这里为了演示全打出来
            System.out.println(executionResult);
            System.out.println("--------------------------------------------------");

            // 简单的成功/失败判断逻辑 (你可以根据实际情况优化这些正则或关键字)
            boolean hasError = executionResult.contains("Exception") ||
                    executionResult.contains("Error") ||
                    executionResult.contains("failed") ||
                    executionResult.contains("error") ||
                    executionResult.contains("javac: file not found"); // 编译找不到文件

            if (!hasError) {
                System.out.println("🎉 测试通过！代码成功运行。");
                isSuccess = true;
            } else {
                System.out.println("🐛 发现报错！正在呼叫 Tester Agent 进行分析与修复...");

                // 将当前所有代码拼装成文本，给 Tester 提供上下文
                String currentCodeContext = buildCodeContextForTester(codes);

                // 调用 Tester Agent 给出修复方案
                CodeFixResult fixResult = testerAgent.analyzeAndFix(executionResult, currentCodeContext);
                List<CodeFix> fixes = fixResult.fixes();

                if (fixes != null && !fixes.isEmpty()) {
                    System.out.println("🛠️ Tester Agent 给出了 " + fixes.size() + " 个修复方案：");
                    for (CodeFix fix : fixes) {
                        System.out.println("   - 原因: " + fix.explanation());

                        // 更新内存中的 codes 列表 (为了下一次循环能传给 Tester 最新的代码)
                        updateCodesInMemory(codes, fix);
                    }

                    // 将新代码覆写到本地磁盘
                    workspaceService.applyFixesToDisk(projectPath, fixes);

                } else {
                    System.out.println("⚠️ Tester Agent 未能提供修复方案，可能问题过于复杂。");
                    break; // 跳出循环
                }
                currentAttempt++;
            }
        }

        if (!isSuccess) {
            System.out.println("❌ 达到最大重试次数 (" + maxRetries + ")，项目生成失败，需要人工介入。");
        }
    }

    // ====== 辅助方法 ======

    /**
     * 将当前的 SourceCode 列表转成一个大字符串，方便喂给 Tester Agent
     */
    private String buildCodeContextForTester(List<SourceCode> codes) {
        StringBuilder sb = new StringBuilder();
        for (SourceCode code : codes) {
            sb.append("--- File: ").append(code.filename()).append(" ---\n");
            sb.append(code.code()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 用修复后的代码更新内存中的 List<SourceCode>
     */
    private void updateCodesInMemory(List<SourceCode> codes, CodeFix fix) {
        boolean found = false;
        for (int i = 0; i < codes.size(); i++) {
            if (codes.get(i).filename().equals(fix.filename())) {
                // 替换成新代码
                codes.set(i, new SourceCode(fix.filename(), codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }
        // 如果修复方案里包含了一个之前完全没有的新文件 (比如缺失的类)
        if (!found) {
            codes.add(new SourceCode(fix.filename(), "java", fix.newCode()));
        }
    }
}
