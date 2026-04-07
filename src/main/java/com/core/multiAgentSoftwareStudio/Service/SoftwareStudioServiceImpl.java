package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.*;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class SoftwareStudioServiceImpl implements SoftwareStudioService {

    private final ProductManagerAgent pmAgent;
    private final ArchitectAgent architectAgent;
    private final DeveloperAgent developerAgent;
    private final TestWriterAgent testWriterAgent;
    private final DebuggerAgent debuggerAgent;
    private final DockerSandboxService sandboxService;
    private final WorkspaceService workspaceService;

    // Spring 构造器注入 (需要在 Config 里通过 AiServices 创建这些 Bean)
    public SoftwareStudioServiceImpl(ProductManagerAgent pm, ArchitectAgent arch, DeveloperAgent dev,
            TestWriterAgent testWriterAgent, DebuggerAgent debuggerAgent, DockerSandboxService sandboxService,
            WorkspaceService workspaceService) {
        this.pmAgent = pm;
        this.architectAgent = arch;
        this.developerAgent = dev;
        this.testWriterAgent = testWriterAgent;
        this.debuggerAgent = debuggerAgent;
        this.sandboxService = sandboxService;
        this.workspaceService = workspaceService;
    }

    /**
     * 程序生成方法
     * 
     * @param userRequest
     * @return
     */
    public List<SourceCode> generateProject(String userRequest) {
        return generateProjectInternal(userRequest, System.out::println, 5);
    }

    /**
     * generateProject 的"流式版本"
     * 
     * @param userRequest
     * @param eventListener
     */
    @Override
    public void generateProjectStream(String userRequest, Consumer<String> eventListener) {
        generateProjectInternal(userRequest, eventListener, 5);
    }

    /**
     * 程序生成核心逻辑
     * 
     * @param userRequest
     * @param eventListener
     * @param maxRetries
     * @return
     */
    private List<SourceCode> generateProjectInternal(String userRequest, Consumer<String> eventListener,
            int maxRetries) {
        Consumer<String> logger = eventListener != null ? eventListener : message -> {
        };

        logger.accept("🚀 1. 产品经理正在分析需求: " + userRequest);
        PrdDocument prd = pmAgent.analyzeRequirement(userRequest);
        logger.accept("✅ PRD 生成完毕: " + prd.projectName());
        logger.accept(prd.toString());
        logger.accept("🏗️ 2. 架构师正在设计系统结构...");
        ProjectStructure structure = architectAgent.designArchitecture(prd);
        logger.accept("✅ 架构设计完毕，共 " + structure.files().size() + " 个文件。");
        logger.accept(structure.toString());
        // 代码文件列表
        List<SourceCode> codes = new ArrayList<>();
        StringBuilder existingCode = new StringBuilder();

        // 并行或者串行生成代码
        for (FileBlueprint fileBlueprint : structure.files()) {
            logger.accept("👨‍💻 3. 工程师正在编写: " + fileBlueprint.fileName());

            // 如果大模型没有返回 keyMethods，就给个空字符串或空数组的字面量
            String methodsStr = (fileBlueprint.keyMethods() == null || fileBlueprint.keyMethods().isEmpty())
                    ? "无特定方法要求"
                    : fileBlueprint.keyMethods().toString();

            SourceCode rawResult = developerAgent.writeCode(
                    prd,
                    structure,
                    existingCode.toString(),
                    fileBlueprint.fileName(),
                    fileBlueprint.functionalityDescription(),
                    methodsStr);

            // 2. 强制使用蓝图中的文件名，防止 AI 幻觉
            SourceCode finalCode = new SourceCode(
                    fileBlueprint.fileName(),
                    rawResult.language(),
                    rawResult.code());
            codes.add(finalCode);

            // 将当前生成的文件加入到后续文件的上下文
            existingCode.append("--- File: ").append(finalCode.filename()).append(" ---\n");
            existingCode.append(finalCode.code()).append("\n\n");

            logger.accept("✅ 文件完成: " + finalCode.filename());
        }

        logger.accept("🧪 4. 测试工程师正在编写 JUnit 测试类...");
        TestClassesResult testClassesResult = testWriterAgent.writeTests(prd, structure, existingCode.toString());
        if (testClassesResult != null && testClassesResult.testFiles() != null) {
            for (SourceCode testFile : testClassesResult.testFiles()) {
                String normalizedFileName = normalizeGeneratedFilename(testFile.filename(), testFile.code());
                logger.accept("✅ 生成测试文件: " + normalizedFileName);
                codes.add(new SourceCode(normalizedFileName, testFile.language(), testFile.code()));
            }
        }

        // 4. 持久化到本地 ---
        Path projectPath = workspaceService.saveProjectToDisk(prd.projectName(), codes);

        // ==========================================
        // 5. 沙箱运行与自我修复循环 (The Loop)
        // ==========================================
        int currentAttempt = 1;
        boolean isSuccess = false;

        logger.accept("🐳 进入沙箱运行与测试循环...");

        while (currentAttempt <= maxRetries && !isSuccess) {
            logger.accept("\n▶️ [第 " + currentAttempt + " 次尝试] 正在启动 Docker 沙箱进行运行测试...");
            String executionResult = sandboxService.runCodeInSandbox(
                    projectPath,
                    structure.projectType(),
                    structure.mainClassName());

            logger.accept("💡 运行结果:");
            logger.accept("--------------------------------------------------");
            logger.accept(executionResult);
            logger.accept("--------------------------------------------------");

            boolean hasError = executionResult.contains("Exception") ||
                    executionResult.contains("Error") ||
                    executionResult.contains("failed") ||
                    executionResult.contains("error") ||
                    executionResult.contains("javac: file not found");

            if (!hasError) {
                logger.accept("✅ 代码运行成功，正在进行逻辑验证 (运行单元测试)...");
                String testResult = sandboxService.runTestsInSandbox(projectPath, structure.projectType());
                logger.accept("💡 测试结果:");
                logger.accept(testResult);

                if (testResult.contains("Failures: 0") && testResult.contains("Errors: 0")) {
                    logger.accept("🎉 所有测试通过！逻辑验证成功。");
                    isSuccess = true;
                } else if (testResult.contains("Failures:") || testResult.contains("Errors:")) {
                    logger.accept("❌ 逻辑验证失败！发现测试未通过。");
                    handleFix(codes, testResult, "LOGIC ERROR (TEST FAILURE)", projectPath, logger);
                } else {
                    logger.accept("⚠️ 未发现有效的测试结果，暂且认为运行成功。");
                    isSuccess = true;
                }
            } else {
                String errorType = determineErrorType(executionResult);
                handleFix(codes, executionResult, errorType, projectPath, logger);
            }
            if (!isSuccess)
                currentAttempt++;
        }

        if (!isSuccess) {
            logger.accept("❌ 达到最大重试次数 (" + maxRetries + ")，项目生成失败，需要人工介入。");
        }

        return codes;
    }

    // ====== 辅助方法 ======

    private String determineErrorType(String executionResult) {
        if (executionResult.contains("javac:") || executionResult.contains("Compilation failure")) {
            return "COMPILATION ERROR";
        } else if (executionResult.contains("Tests run:") && executionResult.contains("Failures: ")) {
            // 如果日志包含 Maven Test 结果且有失败
            if (!executionResult.contains("Failures: 0") || !executionResult.contains("Errors: 0")) {
                return "LOGIC ERROR (TEST FAILURE)";
            }
        }
        return "RUNTIME ERROR";
    }

    /**
     * 优化上下文：只提供最相关的代码，解决上下文过长的问题
     */
    private String buildOptimizedCodeContext(List<SourceCode> codes, String executionResult, String errorType) {
        StringBuilder sb = new StringBuilder();

        // 1. 总是包含 PRD 关键信息（如果需要可以从成员变量拿，这里假设 codes 已经够用）

        // 2. 根据错误日志寻找文件名
        List<String> relatedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            String pureName = Path.of(code.filename()).getFileName().toString();
            if (executionResult.contains(pureName)) {
                relatedFiles.add(code.filename());
            }
        }

        // 3. 如果没找到具体文件，或者错误类型是逻辑错误，则提供所有代码（但限制长度）
        if (relatedFiles.isEmpty() || "LOGIC ERROR (TEST FAILURE)".equals(errorType)) {
            return buildCodeContextForTester(codes); // 逻辑错误通常需要全局视野
        }

        // 4. 只提供相关文件的内容
        for (SourceCode code : codes) {
            if (relatedFiles.contains(code.filename())) {
                sb.append("--- File: ").append(code.filename()).append(" ---\n");
                sb.append(code.code()).append("\n\n");
            } else {
                // 仅提取类名和核心签名（伪代码摘要），节省 token
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
            // 简单提取包名、类名、方法名签名
            if (trimmed.startsWith("package ") || trimmed.startsWith("public class ") ||
                    trimmed.startsWith("public interface ") || trimmed.startsWith("public record ")) {
                summary.append(trimmed).append("\n");
            } else if (trimmed.startsWith("public ") && (trimmed.contains("(") && trimmed.contains(")"))) {
                // 仅保留方法签名，不包含方法体
                summary.append("  ").append(trimmed.split("\\{")[0].trim()).append(";\n");
            }
        }
        if (summary.length() == 0)
            return "// [No summary available]";
        return summary.toString();
    }

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
        String normalizedFixFilename = normalizeGeneratedFilename(fix.filename(), fix.newCode());
        // 获取 AI 返回的纯文件名
        String fixPureName = Path.of(normalizedFixFilename).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            String existingFilename = normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (existingFilename.equals(normalizedFixFilename)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
            // 获取内存中代码的纯文件名
            String existPureName = Path.of(codes.get(i).filename()).getFileName().toString();

            // 只要文件名相同就认为是同一个文件（例如都叫 UrlService.java）
            if (existPureName.equals(fixPureName)) {
                // 替换成新代码，但保留原有的、可能更准确的 filename (包含路径)
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }

        if (!found) {
            codes.add(new SourceCode(normalizedFixFilename, "java", fix.newCode()));
        }
    }

    private String normalizeGeneratedFilename(String filename, String code) {
        if (filename == null || filename.isBlank()) {
            return "Unknown.java";
        }
        String normalized = filename.trim().replace("\\", "/");
        int testPathIndex = normalized.indexOf("src/test/java/");
        if (testPathIndex >= 0) {
            normalized = normalized.substring(testPathIndex);
        } else {
            int mainPathIndex = normalized.indexOf("src/main/java/");
            if (mainPathIndex >= 0) {
                normalized = normalized.substring(mainPathIndex);
            }
        }
        boolean isLikelyTest = normalized.endsWith("Test.java")
                || (code != null && (code.contains("org.junit.jupiter") || code.contains("@Test")));
        if (isLikelyTest && normalized.startsWith("src/main/java/")) {
            normalized = "src/test/java/" + normalized.substring("src/main/java/".length());
        }
        return normalized;
    }

    /**
     * 编译错误分类器
     * 
     * @param errorType
     * @param executionResult
     * @param codes
     * @return
     */
    private String buildEnhancedErrorLog(String errorType, String executionResult, List<SourceCode> codes) {
        if (!"COMPILATION ERROR".equals(errorType)) {
            return executionResult;
        }
        String classification = classifyCompilationErrors(executionResult, codes);
        if (classification.isBlank()) {
            return executionResult;
        }
        return executionResult + "\n\n=== COMPILATION ERROR CLASSIFIER ===\n" + classification;
    }

    private String classifyCompilationErrors(String executionResult, List<SourceCode> codes) {
        List<String> hints = new ArrayList<>();

        boolean hasJUnitMissing = executionResult.contains("package org.junit.jupiter")
                || executionResult.contains("package org.mockito")
                || executionResult.contains("org.junit.jupiter.api does not exist")
                || executionResult.contains("org.mockito does not exist");
        if (hasJUnitMissing) {
            List<String> misplacedTests = findMisplacedTestFiles(codes);
            if (!misplacedTests.isEmpty()) {
                hints.add("检测到测试依赖缺失，且测试文件可能被放在 src/main/java。请把这些文件移动到 src/test/java: "
                        + String.join(", ", misplacedTests));
            } else {
                hints.add("检测到测试依赖缺失。请确认测试类位于 src/test/java，避免主源码目录编译 test-scope 依赖。");
            }
        }

        boolean hasMethodBodyIssue = executionResult.contains("missing method body")
                || executionResult.contains("or declare abstract")
                || executionResult.contains("abstract methods")
                || executionResult.contains("is not abstract and does not override abstract method");
        if (hasMethodBodyIssue) {
            hints.add("检测到方法/构造器只有声明无实现。普通 class 中的方法和构造器必须提供方法体，不能以分号结尾。");
        }

        boolean missingList = executionResult.contains("symbol:   class List")
                || executionResult.contains("symbol: class List")
                || executionResult.contains("cannot find symbol")
                        && (executionResult.contains(" List ") || executionResult.contains("List<"));
        if (missingList) {
            hints.add("检测到 List 符号缺失，优先检查并补充 import java.util.List;");
        }

        if (executionResult.contains("symbol:   class ResponseEntity")
                || executionResult.contains("symbol: class ResponseEntity")) {
            hints.add("检测到 ResponseEntity 符号缺失，补充 import org.springframework.http.ResponseEntity;");
        }
        if (executionResult.contains("symbol:   class RequestParam")
                || executionResult.contains("symbol: class RequestParam")) {
            hints.add("检测到 RequestParam 符号缺失，补充 import org.springframework.web.bind.annotation.RequestParam;");
        }
        if (executionResult.contains("symbol:   class PathVariable")
                || executionResult.contains("symbol: class PathVariable")) {
            hints.add("检测到 PathVariable 符号缺失，补充 import org.springframework.web.bind.annotation.PathVariable;");
        }

        if (hints.isEmpty()) {
            return "";
        }
        return String.join("\n", hints);
    }

    private List<String> findMisplacedTestFiles(List<SourceCode> codes) {
        List<String> misplacedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            String normalizedFilename = normalizeGeneratedFilename(code.filename(), code.code()).replace("\\", "/");
            boolean looksLikeTest = normalizedFilename.endsWith("Test.java")
                    || (code.code() != null
                            && (code.code().contains("org.junit.jupiter") || code.code().contains("@Test")));
            if (looksLikeTest && normalizedFilename.startsWith("src/main/java/")) {
                misplacedFiles.add(normalizedFilename);
            }
        }
        return misplacedFiles;
    }

    /**
     * 统一处理修复逻辑
     */
    private void handleFix(List<SourceCode> codes, String executionResult, String errorType, Path projectPath,
            Consumer<String> eventListener) {
        eventListener.accept("🐛 发现 " + errorType + "！正在呼叫 Debugger Agent 进行分析与修复...");

        // 优化上下文：根据错误类型选择性提供文件
        String currentCodeContext = buildOptimizedCodeContext(codes, executionResult, errorType);
        String enhancedErrorLog = buildEnhancedErrorLog(errorType, executionResult, codes);

        // 调用 Debugger Agent 给出修复方案
        CodeFixResult fixResult = debuggerAgent.analyzeAndFix(errorType, enhancedErrorLog, currentCodeContext);
        List<CodeFix> fixes = fixResult == null ? null : fixResult.fixes();

        if (fixes != null && !fixes.isEmpty()) {
            eventListener.accept("🛠️ Debugger Agent 给出了 " + fixes.size() + " 个修复方案：");
            List<CodeFix> normalizedFixes = new ArrayList<>();
            for (CodeFix fix : fixes) {
                String normalizedFilename = normalizeGeneratedFilename(fix.filename(), fix.newCode());
                CodeFix normalizedFix = new CodeFix(normalizedFilename, fix.explanation(), fix.newCode());
                eventListener.accept("   - 原因: " + fix.explanation());
                // 更新内存中的 codes 列表
                updateCodesInMemory(codes, normalizedFix);
                normalizedFixes.add(normalizedFix);
            }
            // 将新代码覆写到本地磁盘
            workspaceService.applyFixesToDisk(projectPath, normalizedFixes);
        } else {
            eventListener.accept("⚠️ Debugger Agent 未能提供修复方案，可能问题过于复杂。");
        }
    }
}
