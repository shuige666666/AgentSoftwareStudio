package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchGenerationServiceTest {

    /** 多任务提示只展开当前契约，避免 Agent 在首个 checkpoint 前实现未来能力。 */
    @Test
    void includesOnlyCurrentMilestoneContract() {
        WorkspaceAgentRuntime runtime = mock(WorkspaceAgentRuntime.class);
        when(runtime.runDetailed(any(), any(), any(), anyString(), any()))
                .thenReturn(new WorkspaceAgentRunResult(true, null, 1, 0, List.of(), "", "", ""));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("build an article service");
        data.milestoneState = new DevelopmentMilestoneState(1, List.of(
                new DevelopmentMilestoneState.Task("create-query", "create and query", List.of("create"),
                        false, "", "", ""),
                new DevelopmentMilestoneState.Task("comments", "submit comments", List.of("comment"),
                        false, "", "", "")), 0, 0, "");
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                slice("create-query", "/articles"), slice("comments", "/articles/{id}/comments")),
                List.of(), "test");

        new BatchGenerationService(runtime).generateProject(data, message -> { });

        ArgumentCaptor<String> objective = ArgumentCaptor.forClass(String.class);
        verify(runtime).runDetailed(eq(data), any(), any(), objective.capture(), any());
        assertTrue(objective.getValue().contains("path=/articles"));
        assertFalse(objective.getValue().contains("/articles/{id}/comments"));
        assertFalse(objective.getValue().contains("FutureVoteTest.java"));
        assertTrue(objective.getValue().contains("Defer later endpoints"));
    }

    private DeliverySlice slice(String id, String path) {
        return new DeliverySlice(id, id, List.of(), List.of(new FileBlueprint(
                "FutureVoteTest.java", "src/test/java/demo/FutureVoteTest.java", "test", "future",
                "future behavior", List.of(), List.of())),
                new ProjectContract(List.of(new ApiEndpointContract("POST", path, "", "", "", "")),
                        List.of(), List.of(), List.of()), List.of(), List.of());
    }
}
