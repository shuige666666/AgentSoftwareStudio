package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.junit.jupiter.api.Test;

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

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> {
        });

        verify(debugger, never()).analyzeAndFix(any(), any(), any());
        assertTrue(data.codes.getFirst().code().contains("fixed"));
        assertEquals(2, data.currentAttempt);
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
        assertEquals(1, data.runJournal.size());
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

        service(debugger, developer, testWriter, contextBuilder).repair(data, message -> {
        });

        verify(debugger, never()).analyzeAndFix(any(), any(), any());
        verify(developer).writeCode(any(), any(), any(), any(), any(), any(), any(), any());
        assertTrue(data.codes.getFirst().code().contains("fixed"));
        assertEquals(2, data.currentAttempt);
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
                new FailureTriageService(),
                new RunJournalService());
    }

    private SoftwareStudioWorkflowData data(FailureKind failureKind) {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);
        data.pendingFailureKind = failureKind;
        data.pendingErrorType = failureKind.name();
        data.pendingFixLog = "failure";
        data.shouldFix = true;
        return data;
    }
}
