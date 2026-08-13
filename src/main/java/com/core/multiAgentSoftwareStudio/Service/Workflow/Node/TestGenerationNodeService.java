package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceTestScopeService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Model.Workflow.TestScopeViolation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.core.multiAgentSoftwareStudio.Config.TestGenerationConfig.RECOMMENDED_MAX_TEST_METHODS_PER_SLICE;

/**
 * 负责执行测试生成节点，在生产代码生成完成后统一生成测试代码。
 */
@Service
public class TestGenerationNodeService {

    private final TestWriterAgent testWriterAgent;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;
    private final SliceTestScopeService sliceTestScopeService;
    private final SliceProductionScopeService sliceProductionScopeService;

    /**
     * 注入测试 Agent 和源码上下文辅助服务。
     */
    public TestGenerationNodeService(TestWriterAgent testWriterAgent,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService,
            SliceTestScopeService sliceTestScopeService,
            SliceProductionScopeService sliceProductionScopeService) {
        this.testWriterAgent = testWriterAgent;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
        this.sliceTestScopeService = sliceTestScopeService;
        this.sliceProductionScopeService = sliceProductionScopeService;
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
                if (testFile == null || testFile.code() == null || testFile.code().isBlank()) {
                    logger.accept("   Skipped blank generated test file.");
                    continue;
                }
                String normalizedFileName = sourceCodePathService.normalizeGeneratedFilename(testFile.filename(), testFile.code());
                sourceCodePathService.upsertSourceCode(data.codes, normalizedFileName, testFile.code());
                logger.accept("   Generated test: " + normalizedFileName);
            }
        }
        return data;
    }

    /**
     * 为当前垂直切片生成聚焦验收测试，并保存测试文件归属供后续回归选择。
     */
    public SoftwareStudioWorkflowData executeCurrentSlice(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.resumeSliceTestGeneration = false;
        var slice = data.currentSlice();
        if (slice == null) {
            return data;
        }
        List<String> futureReferences = sliceProductionScopeService.findFutureTypeReferences(data);
        if (!futureReferences.isEmpty()) {
            data.currentSliceTestFiles.clear();
            logger.accept("5. Skipping test generation because current production code references a future slice.");
            futureReferences.forEach(reference -> logger.accept("   " + reference));
            return data;
        }
        logger.accept("5. Generating acceptance tests for slice `" + slice.name() + "`.");
        TestClassesResult result = testWriterAgent.writeSliceTests(
                data.prd,
                sliceTestScopeService.activeStructure(data),
                slice.contract(),
                codeContextBuilderService.buildCodeContextForTester(data.codes),
                slice.name(),
                slice.acceptanceCriteria());
        data.currentSliceTestFiles.clear();
        if (result == null || result.testFiles() == null) {
            return data;
        }
        for (SourceCode testFile : result.testFiles()) {
            if (testFile == null || testFile.code() == null || testFile.code().isBlank()) {
                logger.accept("   Skipped blank generated slice test.");
                continue;
            }
            data.generatedTestFileCount++;
            TestScopeViolation violation = sliceTestScopeService.validate(data, testFile);
            if (violation != TestScopeViolation.NONE) {
                if (violation == TestScopeViolation.FUTURE_TYPE) {
                    data.skippedFutureTypeTestCount++;
                } else {
                    data.skippedFutureResourceTestCount++;
                }
                logger.accept("   Skipped out-of-scope test (" + violation + "): " + testFile.filename());
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(testFile.filename(), testFile.code());
            if (data.acceptedTestFiles.stream()
                    .map(path -> path.replace('\\', '/'))
                    .anyMatch(filename.replace('\\', '/')::equals)) {
                data.skippedAcceptedTestFileCount++;
                logger.accept("   Skipped accepted regression test returned by current slice: " + filename);
                continue;
            }
            sourceCodePathService.upsertSourceCode(data.codes, filename, testFile.code());
            if (!data.currentSliceTestFiles.contains(filename)) {
                data.currentSliceTestFiles.add(filename);
            }
            data.admittedTestFileCount++;
            logger.accept("   Generated slice test: " + filename);
        }
        int generatedMethods = data.currentSliceTestFiles.stream()
                .map(path -> data.codes.stream()
                        .filter(code -> code != null && path.equals(sourceCodePathService.normalizePath(code.filename())))
                        .findFirst().map(SourceCode::code).orElse(""))
                .mapToInt(this::countTestMethods)
                .sum();
        if (generatedMethods > RECOMMENDED_MAX_TEST_METHODS_PER_SLICE) {
            // 软上限只做可观测提醒，不截断完整 Java 类，避免为了数量控制制造编译错误。
            logger.accept("   Test density warning: initial slice generated " + generatedMethods
                    + " @Test methods; recommended maximum is "
                    + RECOMMENDED_MAX_TEST_METHODS_PER_SLICE + ".");
        }
        return data;
    }

    /**
     * 统计完整测试源码中的 JUnit 测试方法数量，用于记录首轮切片测试密度。
     */
    private int countTestMethods(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        return (int) Pattern.compile("(?m)^\\s*@Test(?:\\s|$)").matcher(code).results().count();
    }
}
