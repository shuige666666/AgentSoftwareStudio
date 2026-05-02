package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 负责执行测试生成节点，在生产代码生成完成后统一生成测试代码。
 */
@Service
public class TestGenerationNodeService {

    private final TestWriterAgent testWriterAgent;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;

    /**
     * 注入测试 Agent 和源码上下文辅助服务。
     */
    public TestGenerationNodeService(TestWriterAgent testWriterAgent,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService) {
        this.testWriterAgent = testWriterAgent;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 在生产代码全部生成后统一生成测试代码
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 测试统一放到所有生产代码生成完之后再做。
        // 这样测试 Agent 能看到更完整的上下文，也能避免每层都重复生成测试。
        logger.accept("5. Generating tests after all production batches are complete.");
        TestClassesResult testClassesResult = testWriterAgent.writeTests(
                data.prd,
                data.structure,
                data.contract,
                codeContextBuilderService.buildCodeContextForTester(data.codes));
        if (testClassesResult != null && testClassesResult.testFiles() != null) {
            for (SourceCode testFile : testClassesResult.testFiles()) {
                String normalizedFileName = sourceCodePathService.normalizeGeneratedFilename(testFile.filename(), testFile.code());
                upsertCode(data.codes, new SourceCode(normalizedFileName,
                        sourceCodePathService.detectLanguageFromFilename(normalizedFileName), testFile.code()));
                logger.accept("   Generated test: " + normalizedFileName);
            }
        }
        return data;
    }

    /**
     * 将新生成代码写入内存，如已存在则覆盖
     */
    private void upsertCode(List<SourceCode> codes, SourceCode candidate) {
        // 同一路径的文件如果已经生成过，就直接覆盖内存中的旧版本。
        // 这样后续上下文、落盘、修复，看到的都是最新内容。
        String normalizedCandidate = sourceCodePathService.normalizeGeneratedFilename(candidate.filename(), candidate.code());
        for (int i = 0; i < codes.size(); i++) {
            String normalizedExisting = sourceCodePathService.normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (normalizedExisting.equals(normalizedCandidate)) {
                codes.set(i, new SourceCode(normalizedCandidate, sourceCodePathService.detectLanguageFromFilename(normalizedCandidate),
                        candidate.code()));
                return;
            }
        }
        codes.add(
                new SourceCode(normalizedCandidate, sourceCodePathService.detectLanguageFromFilename(normalizedCandidate), candidate.code()));
    }
}
