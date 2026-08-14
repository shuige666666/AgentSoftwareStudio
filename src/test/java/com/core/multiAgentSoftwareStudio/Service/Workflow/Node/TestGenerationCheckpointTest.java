package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentStopReason;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGenerationCheckpointTest {

    @Test
    void incompleteSessionKeepsGeneratedTestsAndReturnsNormally() {
        WorkspaceAgentRuntime runtime = mock(WorkspaceAgentRuntime.class);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.codes.add(new SourceCode("src/main/java/demo/App.java", "java", "class App {}"));
        when(runtime.runDetailed(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData current = invocation.getArgument(0);
            current.codes.add(new SourceCode(
                    "src/test/java/demo/AppTest.java", "java", "class AppTest {}"));
            return new WorkspaceAgentRunResult(
                    false, WorkspaceAgentStopReason.REPORTED_BLOCKER, 3, 5,
                    List.of("src/test/java/demo/AppTest.java"), "report_blocker", "{}", "handoff");
        });
        TestGenerationNodeService service = new TestGenerationNodeService(runtime);
        List<String> logs = new ArrayList<>();

        assertDoesNotThrow(() -> service.execute(data, logs::add));

        assertEquals(1, data.generatedTestFileCount);
        assertEquals(1, data.admittedTestFileCount);
        assertTrue(logs.stream().anyMatch(line -> line.contains("handed off its checkpoint")));
    }
}
