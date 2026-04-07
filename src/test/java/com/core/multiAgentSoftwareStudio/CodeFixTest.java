package com.core.multiAgentSoftwareStudio;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.*;
import com.core.multiAgentSoftwareStudio.Service.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class CodeFixTest {

    @Autowired
    private DebuggerAgent debuggerAgent;

    @Autowired
    private DockerSandboxService sandboxService;

    @Autowired
    private WorkspaceService workspaceService;

    @Test
    public void codeFixLoopTest() {
        int maxRetries = 5; // 设置最大重试次数，防止无限死循环破产
        int currentAttempt = 1;
        boolean isSuccess = false;

        // 这里需要根据你本地实际存在的项目路径进行修改
        Path projectPath = Paths.get(
                "e:/Study/AI Project Study/前期的小项目学习/SoftwareStudio-5/ai_generated_projects/ReactionTimeTest_20260403_201130");
        String projectType = "SPRING_BOOT";
        String mainClassName = "com.example.reactiontimetest.ReactionTimeTestApplication";

        // 读取磁盘上的代码文件，恢复成 List<SourceCode>
        List<SourceCode> codes = workspaceService.loadProjectFromDisk(projectPath);

        System.out.println("🐳 进入沙箱运行与测试循环...");

        while (currentAttempt <= maxRetries && !isSuccess) {
            System.out.println("\n▶️ [第 " + currentAttempt + " 次尝试] 正在启动 Docker 沙箱进行运行测试...");
            String executionResult = sandboxService.runCodeInSandbox(
                    projectPath,
                    projectType,
                    mainClassName);

            System.out.println("💡 运行结果:");
            System.out.println("--------------------------------------------------");
            System.out.println(executionResult);
            System.out.println("--------------------------------------------------");

            boolean hasError = executionResult.contains("Exception") ||
                    executionResult.contains("Error") ||
                    executionResult.contains("failed") ||
                    executionResult.contains("error") ||
                    executionResult.contains("javac: file not found");

            if (!hasError) {
                System.out.println("✅ 代码运行成功，正在进行逻辑验证 (运行单元测试)...");
                String testResult = sandboxService.runTestsInSandbox(projectPath, projectType);
                System.out.println("💡 测试结果:");
                System.out.println(testResult);

                if (testResult.contains("Failures: 0") && testResult.contains("Errors: 0")) {
                    System.out.println("🎉 所有测试通过！逻辑验证成功。");
                    isSuccess = true;
                } else if (testResult.contains("Failures:") || testResult.contains("Errors:")) {
                    System.out.println("❌ 逻辑验证失败！发现测试未通过。");
                    handleFix(codes, testResult, "LOGIC ERROR (TEST FAILURE)", projectPath);
                } else {
                    System.out.println("⚠️ 未发现有效的测试结果，暂且认为运行成功。");
                    isSuccess = true;
                }
            } else {
                String errorType = determineErrorType(executionResult);
                handleFix(codes, executionResult, errorType, projectPath);
            }
            if (!isSuccess)
                currentAttempt++;
        }

        if (!isSuccess) {
            System.out.println("❌ 达到最大重试次数 (" + maxRetries + ")，项目生成失败，需要人工介入。");
        }
    }

    // ====== 辅助方法 (与 SoftwareStudioServiceImpl 逻辑保持一致) ======

    private void handleFix(List<SourceCode> codes, String executionResult, String errorType, Path projectPath) {
        System.out.println("🐛 发现 " + errorType + "！正在呼叫 Debugger Agent 进行分析与修复...");

        // 优化上下文：根据错误类型选择性提供文件
        String currentCodeContext = buildOptimizedCodeContext(codes, executionResult, errorType);

        // 调用 Debugger Agent 给出修复方案
        CodeFixResult fixResult = debuggerAgent.analyzeAndFix(errorType, executionResult, currentCodeContext);
        List<CodeFix> fixes = fixResult.fixes();

        if (fixes != null && !fixes.isEmpty()) {
            System.out.println("🛠️ Debugger Agent 给出了 " + fixes.size() + " 个修复方案：");
            for (CodeFix fix : fixes) {
                System.out.println("   - 原因: " + fix.explanation());
                // 更新内存中的 codes 列表
                updateCodesInMemory(codes, fix);
            }
            // 将新代码覆写到本地磁盘
            workspaceService.applyFixesToDisk(projectPath, fixes);
        } else {
            System.out.println("⚠️ Debugger Agent 未能提供修复方案，可能问题过于复杂。");
        }
    }

    private String determineErrorType(String executionResult) {
        if (executionResult.contains("javac:") || executionResult.contains("Compilation failure")) {
            return "COMPILATION ERROR";
        } else if (executionResult.contains("Tests run:") && executionResult.contains("Failures: ")) {
            if (!executionResult.contains("Failures: 0") || !executionResult.contains("Errors: 0")) {
                return "LOGIC ERROR (TEST FAILURE)";
            }
        }
        return "RUNTIME ERROR";
    }

    private String buildOptimizedCodeContext(List<SourceCode> codes, String executionResult, String errorType) {
        StringBuilder sb = new StringBuilder();
        List<String> relatedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            String pureName = Path.of(code.filename()).getFileName().toString();
            if (executionResult.contains(pureName)) {
                relatedFiles.add(code.filename());
            }
        }

        if (relatedFiles.isEmpty() || "LOGIC ERROR (TEST FAILURE)".equals(errorType)) {
            return buildCodeContextForTester(codes);
        }

        for (SourceCode code : codes) {
            if (relatedFiles.contains(code.filename())) {
                sb.append("--- File: ").append(code.filename()).append(" ---\n");
                sb.append(code.code()).append("\n\n");
            } else {
                sb.append("--- File: ").append(code.filename()).append(" (Summary) ---\n");
                sb.append(extractSummary(code.code())).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String extractSummary(String code) {
        StringBuilder summary = new StringBuilder();
        String[] lines = code.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("public class ") ||
                    trimmed.startsWith("public interface ") || trimmed.startsWith("public record ")) {
                summary.append(trimmed).append("\n");
            } else if (trimmed.startsWith("public ") && (trimmed.contains("(") && trimmed.contains(")"))) {
                summary.append("  ").append(trimmed.split("\\{")[0].trim()).append(";\n");
            }
        }
        if (summary.length() == 0)
            return "// [No summary available]";
        return summary.toString();
    }

    private String buildCodeContextForTester(List<SourceCode> codes) {
        StringBuilder sb = new StringBuilder();
        for (SourceCode code : codes) {
            sb.append("--- File: ").append(code.filename()).append(" ---\n");
            sb.append(code.code()).append("\n\n");
        }
        return sb.toString();
    }

    private void updateCodesInMemory(List<SourceCode> codes, CodeFix fix) {
        boolean found = false;
        String fixPureName = Path.of(fix.filename()).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            String existPureName = Path.of(codes.get(i).filename()).getFileName().toString();
            if (existPureName.equals(fixPureName)) {
                codes.set(i, new SourceCode(codes.get(i).filename(), codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }

        if (!found) {
            codes.add(new SourceCode(fix.filename(), "java", fix.newCode()));
        }
    }
}
