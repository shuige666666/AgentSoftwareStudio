package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentMilestoneServiceTest {
    @TempDir Path root;

    /** 接受当前能力时记录真实快照；下一能力的 progress 会连同此前契约一起复查。 */
    @Test
    void acceptsCurrentMilestoneAndCarriesPreviousContract() {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("create and vote");
        data.projectPath = root.toString();
        var create = new ApiEndpointContract("POST", "/polls", "", "", "PollController", "create");
        var vote = new ApiEndpointContract("POST", "/polls/{id}/votes", "", "", "PollController", "vote");
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                slice("create", new ProjectContract(List.of(create), List.of(), List.of(), List.of())),
                slice("vote", new ProjectContract(List.of(vote), List.of(), List.of(), List.of()))),
                List.of(), "stage3-test");
        service.initialize(data);

        assertTrue(data.milestoneState.active());
        assertEquals(1, service.contractThroughCurrent(data).endpoints().size());
        assertThrows(IllegalArgumentException.class,
                () -> service.accept(data, "vote", checkpoint("PASSED")));
        service.accept(data, "create", checkpoint("PASSED"));

        assertEquals(List.of("create"), data.acceptedSliceIds);
        assertEquals("vote", data.milestoneState.current().id());
        assertEquals(2, service.contractThroughCurrent(data).endpoints().size());
        assertEquals("hash-1", service.load(root).lastAccepted().sourceHash());
        assertFalse(data.milestoneState.allAccepted());
        service.accept(data, "vote", checkpoint("PASSED"));
        assertTrue(data.milestoneState.allAccepted());
    }

    /** 失败的验证不能使能力状态前进。 */
    @Test
    void rejectsFailedCheckpoint() {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice("first", empty()), slice("second", empty())),
                List.of(), "stage3-test");
        service.initialize(data);

        assertThrows(IllegalArgumentException.class,
                () -> service.accept(data, "first", checkpoint("FAILED")));
        assertEquals("first", data.milestoneState.current().id());
    }

    /** 单任务最终通过后也保存已接受版本，不能让状态文件仍显示待完成。 */
    @Test
    void recordsPassingSingleTaskFinalWithoutExtraProgress() {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("simple poll");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice("poll", empty())), List.of(), "stage3-test");
        service.initialize(data);
        data.success = true;
        data.lastCheckpointHash = "final-hash";
        data.lastCheckpointSnapshotPath = root.resolve("source").toString();
        data.lastCheckpointReportPath = root.resolve("report.json").toString();

        service.acceptSingleFinal(data);

        assertTrue(service.load(root).allAccepted());
        assertEquals("final-hash", service.load(root).lastAccepted().sourceHash());
        assertEquals(List.of("poll"), data.acceptedSliceIds);
    }

    private DeliverySlice slice(String id, ProjectContract contract) {
        return new DeliverySlice(id, id, List.of("required behavior"), List.of(), contract, List.of(), List.of());
    }

    private ProjectContract empty() { return new ProjectContract(List.of(), List.of(), List.of(), List.of()); }

    private WorkspaceCheckpointService.CheckpointResult checkpoint(String status) {
        return new WorkspaceCheckpointService.CheckpointResult("progress", status, "hash-1",
                root.resolve("source").toString(), root.resolve("report.json").toString(),
                null, null, true, List.of(), "", false, false);
    }
}
