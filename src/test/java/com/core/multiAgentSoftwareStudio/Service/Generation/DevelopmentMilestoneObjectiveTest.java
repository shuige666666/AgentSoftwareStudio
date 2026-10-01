package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.*;
import com.core.multiAgentSoftwareStudio.Model.Generation.*;
import com.core.multiAgentSoftwareStudio.Model.Workflow.*;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DevelopmentMilestoneObjectiveTest {
    /** 同一约定只展开一次，公开错误响应、未来能力和当前任务独有规则仍完整可见。 */
    @Test
    void deduplicatesOnlyEqualContractEntriesAndKeepsUniqueSliceRules() {
        var shared = new ApiEndpointContract("POST", "/items", "Input", "Output", "Controller.create",
                "shared-create-rule", Map.of("name", "required"), Map.of("id", "generated"), Map.of("400", "invalid"), List.of("create"));
        var future = new ApiEndpointContract("DELETE", "/items/{id}", "", "", "Controller.delete", "future-delete-rule");
        var unique = new ApiEndpointContract("GET", "/items/summary", "", "", "Controller.summary", "slice-unique-rule");
        var full = new ProjectContract(List.of(shared, future), List.of(), List.of(), List.of());
        var current = new ProjectContract(List.of(shared, unique), List.of(), List.of(), List.of());
        var data = data(full, current);

        String goal = DevelopmentMilestoneObjective.render(data);

        assertEquals(1, goal.split("shared-create-rule", -1).length - 1);
        assertTrue(goal.contains("400=invalid"));
        assertTrue(goal.contains("name=required"));
        assertTrue(goal.contains("future-delete-rule"));
        assertTrue(goal.contains("slice-unique-rule"));
        assertTrue(goal.contains("endpoint: POST /items"));
        assertTrue(goal.startsWith(DevelopmentMilestoneObjective.projectContext(data)));
    }

    /** 初次续接保留历史原因；收到当前验证后可省去旧诊断，而不省去原始需求或验收条件。 */
    @Test
    void canReplacePreviousFailureWithoutRemovingTheTaskGoal() {
        var empty = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var data = data(empty, empty);
        assertTrue(DevelopmentMilestoneObjective.render(data).contains("old-stop-diagnostic"));
        String goal = DevelopmentMilestoneObjective.render(data, false);
        assertFalse(goal.contains("old-stop-diagnostic"));
        assertTrue(goal.contains("Original user request:\nrequested-capability"));
        assertTrue(goal.contains("task-criterion"));
        assertTrue(goal.contains("Current milestone: task"));
    }

    /** 构造包含当前范围和历史诊断的固定任务输入。 */
    private SoftwareStudioWorkflowData data(ProjectContract full, ProjectContract current) {
        var data = new SoftwareStudioWorkflowData("requested-capability");
        data.contract = full;
        data.milestoneState = new DevelopmentMilestoneState(2,
                List.of(new DevelopmentMilestoneState.Task("task", "task-goal", List.of("task-criterion"), false, "", "", "")),
                0, 0, "old-stop-diagnostic");
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(new DeliverySlice("task", "task-goal",
                List.of("task-criterion"), List.of(), current, List.of(), List.of())), List.of(), SliceDeliveryPlan.CURRENT_POLICY_VERSION);
        return data;
    }
}
