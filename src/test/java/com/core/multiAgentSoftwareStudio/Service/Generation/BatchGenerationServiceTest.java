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

    /** 真实单任务续接曾漏掉失败摘要，必须从开发入口送入模型目标，不能只更新共享状态。 */
    @Test
    void includesSavedFailureForSingleTaskResume() {
        var runtime = mock(WorkspaceAgentRuntime.class);
        when(runtime.runDetailed(any(), any(), any(), anyString(), any()))
                .thenReturn(new WorkspaceAgentRunResult(true, null, 37, 37, List.of(), "", "", ""));
        var data = new SoftwareStudioWorkflowData("preserve current public behavior");
        data.milestoneState = new DevelopmentMilestoneState(2, List.of(
                new DevelopmentMilestoneState.Task("single", "single", List.of(), false, "", "", "")),
                36, 36, "Observed cause: NoSuchBeanDefinitionException: demo.Store");
        new BatchGenerationService(runtime).generateProject(data, ignored -> { });
        var objective = ArgumentCaptor.forClass(String.class);
        verify(runtime).runDetailed(eq(data), any(), any(), objective.capture(), any());
        assertTrue(objective.getValue().contains("NoSuchBeanDefinitionException: demo.Store"));
        assertTrue(objective.getValue().contains("submit_checkpoint(scope=final)"));
        assertFalse(objective.getValue().contains("ordered business milestones"));
    }

    /** 顺序模式也提供完整未来契约，信息量不再成为策略对照中的额外变量。 */
    @Test
    void includesFullProjectAndEveryMilestoneContract() {
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
        data.contract = new ProjectContract(data.sliceDeliveryPlan.slices().stream()
                .flatMap(slice -> slice.contract().endpoints().stream()).toList(), List.of(), List.of(), List.of());

        new BatchGenerationService(runtime).generateProject(data, message -> { });

        ArgumentCaptor<String> objective = ArgumentCaptor.forClass(String.class);
        verify(runtime).runDetailed(eq(data), any(), any(), objective.capture(), any());
        assertTrue(objective.getValue().contains("path=/articles"));
        assertTrue(objective.getValue().contains("/articles/{id}/comments"));
        assertTrue(objective.getValue().startsWith(DevelopmentMilestoneObjective.projectContext(data)));
        assertFalse(objective.getValue().contains("FutureVoteTest.java"));
        assertTrue(objective.getValue().contains("Defer later endpoints"));
    }

    /** 建议式保留同一完整目标，压缩重建也不能恢复顺序门禁。 */
    @Test
    void advisoryUsesSameGoalAndAllowsMergedTasksAndDirectFinal() {
        var data = new SoftwareStudioWorkflowData("complete project");
        var tasks = List.of(new DevelopmentMilestoneState.Task("first", "first", List.of(), false, "", "", ""),
                new DevelopmentMilestoneState.Task("second", "second", List.of(), false, "", "", ""));
        data.milestoneState = new DevelopmentMilestoneState(2, tasks, 0, 0, "",
                com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY, "");
        String objective = DevelopmentMilestoneObjective.render(data);
        assertTrue(objective.startsWith(DevelopmentMilestoneObjective.projectContext(data)));
        assertTrue(objective.contains("combine tasks"));
        assertTrue(objective.contains("milestoneIds"));
        assertTrue(objective.contains("even if individual"));
        assertFalse(objective.contains("Defer later endpoints"));
        assertFalse(objective.contains("Current milestone:"));
    }

    private DeliverySlice slice(String id, String path) {
        return new DeliverySlice(id, id, List.of(), List.of(new FileBlueprint(
                "FutureVoteTest.java", "src/test/java/demo/FutureVoteTest.java", "test", "future",
                "future behavior", List.of(), List.of())),
                new ProjectContract(List.of(new ApiEndpointContract("POST", path, "", "", "", "")),
                        List.of(), List.of(), List.of()), List.of(), List.of());
    }
}
