package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceTestScopeService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TestGenerationNodeServiceTest {

    /**
     * 生产代码已经越界引用未来类型时不调用测试 Agent，避免为确定失败额外消耗一次 LLM。
     */
    @Test
    void skipsLlmWhenProductionReferencesFutureType() {
        TestWriterAgent testWriterAgent = mock(TestWriterAgent.class);
        SourceCodePathService pathService = new SourceCodePathService();
        FileBlueprint comment = file("src/main/java/com/example/Comment.java", "comments");
        FileBlueprint article = file("src/main/java/com/example/Article.java", "articles");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(comment, article));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("comments", "Comments", List.of(), List.of(comment), contract, List.of(), List.of()),
                new DeliverySlice("articles", "Articles", List.of(), List.of(article), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(comment.targetPath(), "java", "class Comment { private Article article; }"));
        TestGenerationNodeService service = new TestGenerationNodeService(
                testWriterAgent,
                mock(CodeContextBuilderService.class),
                pathService,
                new SliceTestScopeService(pathService),
                new SliceProductionScopeService(pathService));

        service.executeCurrentSlice(data, message -> { });

        assertTrue(data.currentSliceTestFiles.isEmpty());
        verifyNoInteractions(testWriterAgent);
    }

    /**
     * TestWriter 返回空白测试时不能把它计入当前切片验收范围。
     */
    @Test
    void skipsBlankSliceTestResult() {
        TestWriterAgent testWriterAgent = mock(TestWriterAgent.class);
        when(testWriterAgent.writeSliceTests(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TestClassesResult(List.of(new SourceCode(
                        "src/test/java/com/example/AppTest.java", "java", "  "))));
        SourceCodePathService pathService = new SourceCodePathService();
        FileBlueprint app = file("src/main/java/com/example/App.java", "app");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.prd = new com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument(
                "app", "app", List.of(), List.of("Java"));
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(app));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("app", "App", List.of(), List.of(app), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(app.targetPath(), "java", "package com.example; public class App {}"));
        TestGenerationNodeService service = new TestGenerationNodeService(
                testWriterAgent,
                mock(CodeContextBuilderService.class),
                pathService,
                new SliceTestScopeService(pathService),
                new SliceProductionScopeService(pathService));

        service.executeCurrentSlice(data, message -> { });

        assertTrue(data.currentSliceTestFiles.isEmpty());
        assertTrue(data.codes.stream().noneMatch(code -> code.filename().endsWith("AppTest.java")));
    }

    /**
     * 初始切片测试生成应保留完整的紧凑测试类，密度控制不能靠截断 Java 源码实现。
     */
    @Test
    void preservesCompleteCompactSliceAcceptanceSuite() {
        TestWriterAgent testWriterAgent = mock(TestWriterAgent.class);
        String tests = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class AppTest {
                    @Test void first() {}
                    @Test void second() {}
                }
                """;
        when(testWriterAgent.writeSliceTests(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TestClassesResult(List.of(new SourceCode(
                        "src/test/java/com/example/AppTest.java", "java", tests))));
        SourceCodePathService pathService = new SourceCodePathService();
        FileBlueprint app = file("src/main/java/com/example/App.java", "app");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(app));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("app", "App", List.of("app starts"), List.of(app), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(app.targetPath(), "java", "package com.example; public class App {}"));
        TestGenerationNodeService service = new TestGenerationNodeService(
                testWriterAgent, mock(CodeContextBuilderService.class), pathService,
                new SliceTestScopeService(pathService), new SliceProductionScopeService(pathService));

        service.executeCurrentSlice(data, message -> { });

        verify(testWriterAgent).writeSliceTests(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("App"),
                org.mockito.ArgumentMatchers.eq(List.of("app starts")));
        assertTrue(data.codes.stream().anyMatch(code -> code.filename().endsWith("AppTest.java")
                && code.code().contains("second")));
    }

    private FileBlueprint file(String path, String batch) {
        return new FileBlueprint(java.nio.file.Path.of(path).getFileName().toString(), path,
                "base", batch, "test", List.of(), List.of());
    }
}
