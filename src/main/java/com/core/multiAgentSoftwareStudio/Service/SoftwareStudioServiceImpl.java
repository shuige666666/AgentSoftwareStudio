package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.TesterAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.*;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class SoftwareStudioServiceImpl implements SoftwareStudioService {

    private final ProductManagerAgent pmAgent;
    private final ArchitectAgent architectAgent;
    private final DeveloperAgent developerAgent;
    private final TesterAgent testerAgent;
    private final DockerSandboxService sandboxService;
    private final WorkspaceService workspaceService;


    // Spring 构造器注入 (需要在 Config 里通过 AiServices 创建这些 Bean)
    public SoftwareStudioServiceImpl(ProductManagerAgent pm, ArchitectAgent arch, DeveloperAgent dev, TesterAgent testerAgent, DockerSandboxService sandboxService, WorkspaceService workspaceService) {
        this.pmAgent = pm;
        this.architectAgent = arch;
        this.developerAgent = dev;
        this.testerAgent = testerAgent;
        this.sandboxService = sandboxService;
        this.workspaceService = workspaceService;
    }

    public List<SourceCode> generateProject(String userRequest){
        System.out.println("🚀 1. 产品经理正在分析需求: " + userRequest);
        PrdDocument prd = pmAgent.analyzeRequirement(userRequest);
        System.out.println("✅ PRD 生成完毕: " + prd.projectName());
        System.out.println(prd);
        System.out.println("🏗️ 2. 架构师正在设计系统结构...");
        ProjectStructure structure = architectAgent.designArchitecture(prd);
        System.out.println("✅ 架构设计完毕，共 " + structure.files().size() + " 个文件。");
        System.out.println(structure);
        // 代码文件列表
        List<SourceCode> codes = new ArrayList<>();

        // 并行或者串行生成代码
        for (FileBlueprint fileBlueprint : structure.files()) {
            System.out.println("👨‍💻 3. 工程师正在编写: " + fileBlueprint.fileName());

            // 如果大模型没有返回 keyMethods，就给个空字符串或空数组的字面量
            String methodsStr = (fileBlueprint.keyMethods() == null || fileBlueprint.keyMethods().isEmpty())
                    ? "无特定方法要求"
                    : fileBlueprint.keyMethods().toString();

            SourceCode rawResult = developerAgent.writeCode(
                    prd,
                    fileBlueprint.fileName(),
                    fileBlueprint.functionalityDescription(),
                    fileBlueprint.keyMethods().toString() // List 转 String
            );

            // 2. 强制使用蓝图中的文件名，防止 AI 幻觉
            // SourceCode 是 record，不可变，所以我们要 new 一个新的
            SourceCode finalCode = new SourceCode(
                    fileBlueprint.fileName(), // 强制使用正确的文件名
                    rawResult.language(),
                    rawResult.code()          // 只取 AI 生成的代码内容
            );
            codes.add(finalCode);

            System.out.println("✅ 文件完成: " + finalCode.filename());
        }

        // 4. 持久化到本地 ---
        Path projectPath = workspaceService.saveProjectToDisk(prd.projectName(), codes);

        // ==========================================
        // 5. 沙箱运行与自我修复循环 (The Loop)
        // ==========================================
        int maxRetries = 7; // 设置最大重试次数，防止无限死循环破产
        int currentAttempt = 1;
        boolean isSuccess = false;

        System.out.println("🐳 进入沙箱运行与测试循环...");

        while (currentAttempt <= maxRetries && !isSuccess) {
            System.out.println("\n▶️ [第 " + currentAttempt + " 次尝试] 正在启动 Docker 沙箱...");
            String executionResult = sandboxService.runCodeInSandbox(
                    projectPath,
                    structure.projectType(),
                    structure.mainClassName()
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

        return codes;

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
        // 获取 AI 返回的纯文件名
        String fixPureName = Path.of(fix.filename()).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            // 获取内存中代码的纯文件名
            String existPureName = Path.of(codes.get(i).filename()).getFileName().toString();

            // 只要文件名相同就认为是同一个文件（例如都叫 UrlService.java）
            if (existPureName.equals(fixPureName)) {
                // 替换成新代码，但保留原有的、可能更准确的 filename (包含路径)
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
