package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceTestScopeService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectRepairServiceTest {

    /**
     * 测试所有权失败应调用 TestWriter，而不是继续使用通用 Debugger。
     */
    @Test
    void routesTestOwnedFailureToTestWriter() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildCodeContextForTester(any())).thenReturn("context");
        when(testWriter.rewriteTests(any(), any(), any(), any(), any())).thenReturn(
                new TestClassesResult(List.of(new SourceCode(
                        "src/test/java/com/example/AppTest.java", "java", "class AppTest { int fixed; }"))));
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_COMPILE);
        data.codes.add(new SourceCode("src/test/java/com/example/AppTest.java", "java", "class AppTest {}"));
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "app", "App", List.of(), List.of(), emptyContract, List.of(), List.of())),
                List.of(), "test-policy");

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> {
        });

        verify(debugger, never()).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.getFirst().code().contains("fixed"));
        assertEquals(2, data.currentAttempt);
        assertEquals(List.of("src/test/java/com/example/AppTest.java"), data.currentSliceTestFiles);
        assertEquals(1, data.runJournal.size());
    }

    /**
     * 修复 Agent 没有返回文件变化时立即停止，不再进入下一次 LLM 修复。
     */
    @Test
    void stopsWhenRepairProducesNoDiff() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("");
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of()));
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE);
        data.codes.add(new SourceCode("src/main/java/com/example/App.java", "java", "class App {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> {
        });

        assertTrue(data.repairStopRequested);
        assertEquals(1, data.noChangeStopCount);
        assertEquals("NO_CHANGE", data.repairStopReason);
        assertEquals(1, data.runJournal.size());
    }

    /**
     * 测试日志能定位失败类时，只应用该文件修复，TestWriter 返回的无关绿色测试必须被忽略。
     */
    @Test
    void repairsOnlyTestFilesNamedByFailureEvidence() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildCodeContextForTester(any())).thenReturn("context");
        String failedPath = "src/test/java/com/example/SnakeMessageTest.java";
        String greenPath = "src/test/java/com/example/GameSessionTest.java";
        when(testWriter.rewriteTests(any(), any(), any(), any(), any())).thenReturn(
                new TestClassesResult(List.of(
                        new SourceCode(failedPath, "java", "class SnakeMessageTest { int fixed; }"),
                        new SourceCode(greenPath, "java", "class GameSessionTest { int rewritten; }"))));
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_CODE);
        data.pendingFixLog = "SnakeMessageTest.shouldRoundTrip <<< ERROR!";
        data.codes.add(new SourceCode(failedPath, "java", "class SnakeMessageTest {}"));
        data.codes.add(new SourceCode(greenPath, "java", "class GameSessionTest { int green; }"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.stream().filter(code -> failedPath.equals(code.filename()))
                .findFirst().orElseThrow().code().contains("fixed"));
        assertTrue(data.codes.stream().filter(code -> greenPath.equals(code.filename()))
                .findFirst().orElseThrow().code().contains("green"));
        assertEquals(List.of(failedPath), data.runJournal.getFirst().changedFiles());
    }

    /**
     * Debugger 返回空白整文件时必须保留已有源码，并按无变化停止，不能制造新的编译错误。
     */
    @Test
    void rejectsBlankDebuggerOverwrite() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String servicePath = "src/main/java/com/example/CommentService.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(servicePath, "empty model response", "   "))));
        SoftwareStudioWorkflowData data = data(FailureKind.SPRING_CONTEXT);
        data.codes.add(new SourceCode(servicePath, "java", "package com.example; class CommentService {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertEquals("package com.example; class CommentService {}", data.codes.getFirst().code());
        assertTrue(data.repairStopRequested);
        assertEquals("NO_CHANGE", data.repairStopReason);
        assertTrue(data.runJournal.getFirst().changedFiles().isEmpty());
    }

    /**
     * 编译日志明确引用生产文件时应交给 Developer 单文件修复，并保持一次调用预算。
     */
    @Test
    void routesReferencedProductionFileToDeveloper() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        when(developer.writeCode(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SourceCode(
                        "src/main/java/com/example/App.java", "java", "package com.example; class App { int fixed; }"));
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE);
        data.pendingFixLog = "src/main/java/com/example/App.java:[10,2] cannot find symbol";
        data.codes.add(new SourceCode(
                "src/main/java/com/example/App.java", "java", "package com.example; class App {}"));
        FileBlueprint app = new FileBlueprint(
                "App.java", "src/main/java/com/example/App.java", "base", "app", "app", List.of(), List.of());
        FileBlueprint future = new FileBlueprint(
                "Vote.java", "src/main/java/com/example/Vote.java", "base", "vote", "future", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(app, future));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("app", "App", List.of(), List.of(app), emptyContract, List.of(), List.of()),
                new DeliverySlice("vote", "Vote", List.of(), List.of(future), emptyContract, List.of(), List.of())),
                List.of(), "test-policy");

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> {
        });

        verify(debugger, never()).analyzeAndFix(any(), any(), any());
        ArgumentCaptor<ProjectStructure> structure = ArgumentCaptor.forClass(ProjectStructure.class);
        verify(developer).writeCode(any(), structure.capture(), any(), any(), any(), any(), any(), any());
        assertEquals(List.of(app), structure.getValue().files());
        assertTrue(data.codes.getFirst().code().contains("fixed"));
        assertEquals(2, data.currentAttempt);
        assertTrue(data.resumeSliceTestGeneration);
    }

    /**
     * Jackson 构造异常应从类名精确定位模型文件，不能误改调用反序列化方法的 DTO 包装类。
     */
    @Test
    void routesJacksonConstructionFailureToNamedModelOwner() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String pointPath = "src/main/java/com/example/model/Point.java";
        String messagePath = "src/main/java/com/example/dto/SnakeMessage.java";
        when(developer.writeCode(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SourceCode(pointPath, "java",
                        "package com.example.model; class Point { Point() {} }"));
        FileBlueprint point = new FileBlueprint(
                "Point.java", pointPath, "base", "game-core", "point", List.of(), List.of());
        FileBlueprint message = new FileBlueprint(
                "SnakeMessage.java", messagePath, "base", "game-core", "message", List.of(), List.of(pointPath));
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_CODE);
        data.pendingFixLog = "InvalidDefinitionException: Cannot construct instance of "
                + "`com.example.model.Point` (no Creators, like default constructor, exist) "
                + "through reference chain: SnakeMessage[snakeBody]";
        data.structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(point, message));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "game-core", "Game Core", List.of(), List.of(point, message), emptyContract,
                List.of(), List.of())), List.of(), "test-policy");
        data.codes.add(new SourceCode(pointPath, "java",
                "package com.example.model; class Point { Point(int x, int y) {} }"));
        data.codes.add(new SourceCode(messagePath, "java",
                "package com.example.dto; class SnakeMessage { Point point; }"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, messageText -> { });

        verify(debugger, never()).analyzeAndFix(any(), any(), any());
        verify(developer).writeCode(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(pointPath), any(), any(), any());
        assertTrue(data.codes.stream().filter(code -> pointPath.equals(code.filename()))
                .findFirst().orElseThrow().code().contains("Point()"));
    }

    /**
     * 编译日志同时点名多个生产文件时必须整体交给 Debugger，避免连续单文件修复遗漏第二个根因。
     */
    @Test
    void routesMultiFileCompileFailureToDebugger() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String servicePath = "src/main/java/com/example/ShortLinkService.java";
        String handlerPath = "src/main/java/com/example/GlobalExceptionHandler.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(servicePath, "align model API", "class ShortLinkService { int fixed; }"),
                new CodeFix(handlerPath, "fix ResponseEntity", "class GlobalExceptionHandler { int fixed; }"))));
        FileBlueprint serviceFile = new FileBlueprint(
                "ShortLinkService.java", servicePath, "service", "links", "links", List.of(), List.of());
        FileBlueprint handlerFile = new FileBlueprint(
                "GlobalExceptionHandler.java", handlerPath, "config", "links", "links", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE);
        data.pendingFixLog = servicePath + ":[35,13] cannot find symbol\n"
                + handlerPath + ":[21,41] cannot find symbol";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "links", "Links", List.of(), List.of(serviceFile, handlerFile),
                emptyContract, List.of(), List.of())), List.of(), "test-policy");
        data.codes.add(new SourceCode(servicePath, "java", "class ShortLinkService {}"));
        data.codes.add(new SourceCode(handlerPath, "java", "class GlobalExceptionHandler {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        verify(developer, never()).writeCode(any(), any(), any(), any(), any(), any(), any(), any());
        verify(debugger).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.stream().allMatch(code -> code.code().contains("fixed")));
    }

    /**
     * 调用方缺少模型成员时，即使日志只定位调用文件，也必须允许 Debugger 修改真正的类型所有者。
     */
    @Test
    void routesMissingMemberCompileFailureToDebugger() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String servicePath = "src/main/java/com/example/ShortLinkService.java";
        String modelPath = "src/main/java/com/example/ShortLink.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(modelPath, "add explicit getter", "class ShortLink { String getShortCode() { return \"x\"; } }"))));
        FileBlueprint serviceFile = new FileBlueprint(
                "ShortLinkService.java", servicePath, "service", "links", "links", List.of(), List.of());
        FileBlueprint modelFile = new FileBlueprint(
                "ShortLink.java", modelPath, "base", "links", "links", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE);
        data.pendingFixLog = servicePath + ":[35,13] cannot find symbol\n"
                + "symbol: method getShortCode()\nlocation: variable link of type ShortLink";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "links", "Links", List.of(), List.of(serviceFile, modelFile),
                emptyContract, List.of(), List.of())), List.of(), "test-policy");
        data.codes.add(new SourceCode(servicePath, "java", "class ShortLinkService { ShortLink link; }"));
        data.codes.add(new SourceCode(modelPath, "java", "class ShortLink {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        verify(developer, never()).writeCode(any(), any(), any(), any(), any(), any(), any(), any());
        verify(debugger).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.get(1).code().contains("getShortCode"));
    }

    /**
     * 切片修复必须忽略其他切片和已接受回归测试，避免一次修复破坏既有能力。
     */
    @Test
    void ignoresFixesOutsideCurrentSliceBoundary() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("");
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix("src/main/java/com/example/PollService.java", "current", "class PollService { int fixed; }"),
                new CodeFix("src/main/java/com/example/WebConfig.java", "invented", "class WebConfig { int invented; }"),
                new CodeFix("src/main/java/com/example/ResultService.java", "other", "class ResultService { int broken; }"),
                new CodeFix("src/test/java/com/example/AcceptedTest.java", "accepted", "class AcceptedTest { int changed; }"))));
        SoftwareStudioWorkflowData data = data(FailureKind.CONTRACT);
        data.codes.add(new SourceCode("src/main/java/com/example/PollService.java", "java", "class PollService {}"));
        data.codes.add(new SourceCode("src/main/java/com/example/ResultService.java", "java", "class ResultService {}"));
        data.codes.add(new SourceCode("src/test/java/com/example/AcceptedTest.java", "java", "class AcceptedTest {}"));
        data.acceptedTestFiles.add("src/test/java/com/example/AcceptedTest.java");
        data.verificationResult = new VerificationResult(null, null, true, FailureKind.NONE);
        data.testResult = "stale green tests";
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var pollFile = new com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint(
                "PollService.java", "src/main/java/com/example/PollService.java", "service", "poll",
                "poll", List.of(), List.of());
        var missingConfigFile = new com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint(
                "WebConfig.java", "src/main/java/com/example/WebConfig.java", "config", "poll",
                "poll", List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "poll", "Poll", List.of(), List.of(pollFile, missingConfigFile), emptyContract, List.of(), List.of())),
                List.of(), "test-policy");

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.get(0).code().contains("fixed"));
        assertEquals("class ResultService {}", data.codes.get(1).code());
        assertEquals("class AcceptedTest {}", data.codes.get(2).code());
        assertTrue(data.codes.stream().noneMatch(code -> code.filename().contains("WebConfig.java")));
        assertEquals(List.of("src/main/java/com/example/PollService.java"),
                data.runJournal.getFirst().changedFiles());
        assertTrue(!data.verificationResult.passed());
        assertTrue(data.testResult.isEmpty());
    }

    /**
     * 后续端点切片可扩展匹配的既有 Controller 和直接 Service，但不能修改无关生产文件。
     */
    @Test
    void allowsExistingContractControllerAndServiceIntegrationPoints() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String controllerPath = "src/main/java/com/example/PollController.java";
        String servicePath = "src/main/java/com/example/PollService.java";
        String resultPath = "src/main/java/com/example/PollResult.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(controllerPath, "add results endpoint", "class PollController { PollService service; int results; }"),
                new CodeFix(servicePath, "add results mapping", "class PollService { int results; }"))));
        ProjectContract contract = new ProjectContract(List.of(new ApiEndpointContract(
                "GET", "/api/polls/{id}/results", "", "PollResult", "", "poll results")),
                List.of(), List.of(), List.of());
        FileBlueprint result = new FileBlueprint(
                "PollResult.java", resultPath, "base", "poll-results", "results", List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_ASSERTION);
        data.pendingFixLog = "Status expected:<200> but was:<404>";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "poll-results", "Poll Results", List.of(), List.of(result), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(controllerPath, "java",
                "@RequestMapping(\"/api/polls\") class PollController { PollService service; }"));
        data.codes.add(new SourceCode(servicePath, "java", "class PollService {}"));
        data.codes.add(new SourceCode(resultPath, "java", "class PollResult {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.get(0).code().contains("results"));
        assertTrue(data.codes.get(1).code().contains("results"));
        assertEquals(List.of(controllerPath, servicePath), data.runJournal.getFirst().changedFiles());
    }

    /**
     * 重复端点告警点名旧 Controller 时，应允许当前切片删除旧处理器以确立唯一端点所有者。
     */
    @Test
    void allowsDuplicateMappingRepairAcrossSliceBoundary() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String oldController = "src/main/java/com/example/UrlShortenerController.java";
        String ownerController = "src/main/java/com/example/UrlMetadataController.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(oldController, "remove duplicate metadata handler",
                        "class UrlShortenerController { void redirect() {} }"))));
        ProjectContract contract = new ProjectContract(List.of(new ApiEndpointContract(
                "GET", "/api/metadata/{shortCode}", "", "Metadata",
                "UrlMetadataController", "metadata")), List.of(), List.of(), List.of());
        FileBlueprint owner = new FileBlueprint(
                "UrlMetadataController.java", ownerController, "controller", "metadata",
                "metadata", List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.CONTRACT);
        data.pendingFixLog = "DUPLICATE_CONTROLLER_MAPPING";
        data.validationWarnings.add("DUPLICATE_CONTROLLER_MAPPING: Duplicate controller mapping "
                + "`GET /api/metadata/{}` is declared by " + oldController + " and " + ownerController + ".");
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "metadata", "Metadata", List.of(), List.of(owner), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(oldController, "java",
                "class UrlShortenerController { void redirect() {} void metadata() {} }"));
        data.codes.add(new SourceCode(ownerController, "java",
                "class UrlMetadataController { void metadata() {} }"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertEquals("class UrlShortenerController { void redirect() {} }", data.codes.getFirst().code());
        assertEquals(List.of(oldController), data.runJournal.getFirst().changedFiles());
    }

    /**
     * 多文件未来类型越界应由一次 Debugger 调用整体修复，并清空修复前的绿色验证证据。
     */
    @Test
    void repairsAllFutureTypeReferencesAndInvalidatesOldVerification() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String controllerPath = "src/main/java/com/example/CommentController.java";
        String modelPath = "src/main/java/com/example/Comment.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(controllerPath, "remove future service", "class CommentController { long articleId; }"),
                new CodeFix(modelPath, "store primitive id", "class Comment { long articleId; }"))));
        FileBlueprint controller = new FileBlueprint(
                "CommentController.java", controllerPath, "controller", "comments", "comments", List.of(), List.of());
        FileBlueprint model = new FileBlueprint(
                "Comment.java", modelPath, "base", "comments", "comments", List.of(), List.of());
        FileBlueprint future = new FileBlueprint(
                "ArticleService.java", "src/main/java/com/example/ArticleService.java", "service", "articles",
                "future", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE);
        data.pendingFixLog = "SLICE_FUTURE_TYPE_REFERENCE: " + controllerPath
                + " references future type ArticleService; " + modelPath + " references future type Article";
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App",
                List.of(controller, model, future));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("comments", "Comments", List.of(), List.of(controller, model),
                        emptyContract, List.of(), List.of()),
                new DeliverySlice("articles", "Articles", List.of(), List.of(future),
                        emptyContract, List.of(), List.of())), List.of(), "test-policy");
        data.codes.add(new SourceCode(controllerPath, "java", "class CommentController { ArticleService service; }"));
        data.codes.add(new SourceCode(modelPath, "java", "class Comment { Article article; }"));
        data.verificationResult = new VerificationResult(null, null, true, FailureKind.NONE);
        data.executionResult = "old green build";
        data.testResult = "old green tests";

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        verify(developer, never()).writeCode(any(), any(), any(), any(), any(), any(), any(), any());
        verify(debugger).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.stream().allMatch(code -> !code.code().contains("Article")));
        assertTrue(data.resumeSliceTestGeneration);
        assertTrue(data.executionResult.isEmpty());
        assertTrue(data.testResult.isEmpty());
        assertTrue(!data.verificationResult.passed());
    }

    /**
     * 实现所有权的测试运行失败应由 Debugger 一次修复当前切片的全部耦合文件。
     */
    @Test
    void routesImplementationAssertionToMultiFileDebugger() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String servicePath = "src/main/java/com/example/PollService.java";
        String handlerPath = "src/main/java/com/example/GlobalExceptionHandler.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(servicePath, "align exception", "class PollService { static class NotFound {} }"),
                new CodeFix(handlerPath, "handle service exception",
                        "class GlobalExceptionHandler { PollService.NotFound handled; }"))));
        FileBlueprint serviceFile = new FileBlueprint(
                "PollService.java", servicePath, "service", "poll", "poll", List.of(), List.of());
        FileBlueprint handlerFile = new FileBlueprint(
                "GlobalExceptionHandler.java", handlerPath, "config", "poll", "poll", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_ASSERTION);
        data.pendingFixLog = "PollControllerTest failed at PollService.java and GlobalExceptionHandler.java";
        data.structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(serviceFile, handlerFile));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "poll", "Poll", List.of(), List.of(serviceFile, handlerFile),
                emptyContract, List.of(), List.of())), List.of(), "test-policy");
        data.codes.add(new SourceCode(servicePath, "java", "class PollService {}"));
        data.codes.add(new SourceCode(handlerPath, "java", "class GlobalExceptionHandler {}"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        verify(developer, never()).writeCode(any(), any(), any(), any(), any(), any(), any(), any());
        verify(debugger).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.get(0).code().contains("NotFound"));
        assertTrue(data.codes.get(1).code().contains("handled"));
        assertEquals(List.of(servicePath, handlerPath), data.runJournal.getFirst().changedFiles());
    }

    /**
     * 模板解析日志和 Controller 返回值一致时，允许 Debugger 精确补充唯一缺失模板。
     */
    @Test
    void allowsExplicitlyMissingControllerTemplate() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String controllerPath = "src/main/java/com/example/ArticleController.java";
        String templatePath = "src/main/resources/templates/article.html";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(templatePath, "add missing view", "<html><body>article</body></html>"))));
        FileBlueprint controller = new FileBlueprint(
                "ArticleController.java", controllerPath, "controller", "articles", "articles", List.of(), List.of());
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_ASSERTION);
        data.pendingFixLog = "TemplateInputException: Error resolving template [article], template might not exist";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "articles", "Articles", List.of(), List.of(controller), emptyContract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(controllerPath, "java",
                "class ArticleController { String view() { return \"article\"; } }"));

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.stream().anyMatch(code -> templatePath.equals(code.filename())));
        assertEquals(List.of(templatePath), data.runJournal.getFirst().changedFiles());
    }

    /**
     * 前端契约明确声明的缺失源文件应允许由 Debugger 补建，不能被修复阶段的通用新增文件限制丢弃。
     */
    @Test
    void allowsExplicitlyMissingContractFrontendSource() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String frontendPath = "src/main/resources/templates/article-list.html";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(frontendPath, "add contract frontend source",
                        "<a th:href=\"@{/articles/new}\">New</a>"))));
        ProjectContract contract = new ProjectContract(
                List.of(new ApiEndpointContract("GET", "/articles/new", "", "View",
                        "ArticleController", "new article")),
                List.of(),
                List.of(new com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract(
                        frontendPath, "GET", "/articles/new", "new article")),
                List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.CONTRACT);
        data.finalVerificationStarted = true;
        data.contract = contract;
        data.pendingFixLog = "Contract frontend call source file is missing: " + frontendPath;

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.stream().anyMatch(code -> frontendPath.equals(code.filename())));
        assertEquals(List.of(frontendPath), data.runJournal.getFirst().changedFiles());
    }

    /**
     * 契约门禁确认当前切片不完整时，应允许 Debugger 补建蓝图已声明但首次生成遗漏的生产文件。
     */
    @Test
    void allowsContractBlockedMissingProductionFileDeclaredByCurrentSlice() {
        DebuggerAgent debugger = mock(DebuggerAgent.class);
        DeveloperAgent developer = mock(DeveloperAgent.class);
        TestWriterAgent testWriter = mock(TestWriterAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        when(contextBuilder.buildContractContext(any())).thenReturn("");
        when(contextBuilder.buildOptimizedCodeContext(any(), any(), any())).thenReturn("context");
        String controllerPath = "src/main/java/com/example/SnakeMessageController.java";
        when(debugger.analyzeAndFix(any(), any(), any())).thenReturn(new CodeFixResult(List.of(
                new CodeFix(controllerPath, "implement declared websocket owner",
                        "package com.example; class SnakeMessageController { void command() {} }"))));
        FileBlueprint controller = new FileBlueprint(
                "SnakeMessageController.java", controllerPath, "controller", "gameplay",
                "handle contracted messages", List.of("command"), List.of());
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = data(FailureKind.CONTRACT);
        data.pendingFixLog = "CONTRACT_FRONTEND_CALL: websocket command has no production owner";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice(
                "gameplay", "Gameplay", List.of(), List.of(controller), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.qualityPolicyResult = new com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult(
                false, List.of(new com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding(
                        "CONTRACT_FRONTEND_CALL",
                        com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity.BLOCK,
                        FailureKind.CONTRACT, "websocket command has no production owner")), List.of());

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> { });

        assertTrue(data.codes.stream().anyMatch(code -> controllerPath.equals(code.filename())));
        assertEquals(List.of(controllerPath), data.runJournal.getFirst().changedFiles());
    }

    private ProjectRepairService service(
            DebuggerAgent debugger,
            DeveloperAgent developer,
            TestWriterAgent testWriter,
            CodeContextBuilderService contextBuilder) {
        SourceCodePathService pathService = new SourceCodePathService();
        return new ProjectRepairService(
                debugger,
                developer,
                testWriter,
                mock(WorkspaceService.class),
                contextBuilder,
                pathService,
                new SliceTestScopeService(pathService),
                new SliceProductionScopeService(pathService),
                new FailureTriageService(),
                new RunJournalService());
    }

    private SoftwareStudioWorkflowData data(FailureKind failureKind) {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.pendingFailureKind = failureKind;
        data.pendingErrorType = failureKind.name();
        data.pendingFixLog = "failure";
        data.shouldFix = true;
        return data;
    }
}
