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
        assertEquals(1, service.contractForSelection(data, List.of("create")).endpoints().size());
        service.accept(data, "create", checkpoint("PASSED"));

        assertEquals(List.of("create"), data.acceptedSliceIds);
        assertEquals("vote", data.milestoneState.current().id());
        assertEquals(2, service.contractForSelection(data, List.of("vote")).endpoints().size());
        assertEquals("hash-1", service.load(root).lastAccepted().sourceHash());
        assertFalse(data.milestoneState.allAccepted());
        service.accept(data, "vote", checkpoint("PASSED"));
        assertTrue(data.milestoneState.allAccepted());
    }

    /** 新任务允许合并、调整顺序，状态不再包含推进策略字段。 */
    @Test
    void newProjectAllowsMergedTasksWithoutPolicyField() throws Exception {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("two related capabilities");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice("a", empty()), slice("b", empty())), List.of(), "test");
        service.initialize(data);

        assertFalse(new ObjectMapper().readTree(root.resolve(".software-studio")
                .resolve(DevelopmentMilestoneService.STATE_FILE).toFile()).has("policy"));
        service.validateSelection(data, List.of("b", "a"));
        service.acceptSelected(data, List.of("b", "a"), checkpoint("PASSED"));
        assertTrue(service.load(root).allAccepted());
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

    /** 乱序与合并接受后，失败记录及预算保存不能把恢复版本退回清单末尾。 */
    @Test
    void advisoryPreservesLatestVerifiedVersionAcrossPersistence() {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("three capabilities");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice("a", empty()), slice("b", empty()),
                slice("c", empty())), List.of(), "test");
        service.initialize(data);
        service.accept(data, "c", checkpoint("PASSED"));
        service.acceptSelected(data, List.of("a", "b"), checkpoint("PASSED"));
        data.developmentModelTurnsUsed = 9;
        service.recordFailure(data, "timeout");
        var loaded = service.load(root);
        assertTrue(loaded.allAccepted());
        assertEquals("b", loaded.lastAccepted().id());
        assertEquals(9, loaded.modelTurnsUsed());
        assertEquals(List.of("a", "b", "c"), loaded.tasks().stream().map(task -> task.id()).toList());
    }

    /** 乱序选择仍复查已接受接口，不能因为自由排序而遗漏旧能力回归。 */
    @Test
    void selectedContractIncludesAcceptedCapabilitiesButNotUnselectedFuture() {
        var service = new DevelopmentMilestoneService(new ObjectMapper());
        var data = new SoftwareStudioWorkflowData("three endpoints");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of("a", "b", "c").stream().map(id ->
                slice(id, new ProjectContract(List.of(new ApiEndpointContract("GET", "/" + id, "", "", "", "")),
                        List.of(), List.of(), List.of()))).toList(), List.of(), "test");
        service.initialize(data);
        assertEquals(List.of("/b"), service.contractForSelection(data, List.of("b")).endpoints().stream()
                .map(ApiEndpointContract::path).toList());
        service.accept(data, "b", checkpoint("PASSED"));
        assertEquals(List.of("/a", "/b"), service.contractForSelection(data, List.of("a")).endpoints().stream()
                .map(ApiEndpointContract::path).toList());
    }

    /** 旧 JSON 仍能恢复最近接受版本，读取本身不改写历史证据。 */
    @Test
    void loadsLegacyStateWithoutChangingItsVerifiedVersion() throws Exception {
        var metadata = root.resolve(".software-studio");
        java.nio.file.Files.createDirectories(metadata);
        java.nio.file.Files.writeString(metadata.resolve(DevelopmentMilestoneService.STATE_FILE), """
                {"schemaVersion":1,"tasks":[{"id":"a","goal":"a","accepted":true,"sourceHash":"old"}],
                "modelTurnsUsed":3,"toolCallsUsed":4,"lastFailure":""}
                """);
        var loaded = new DevelopmentMilestoneService(new ObjectMapper()).load(root);
        assertEquals(1, loaded.schemaVersion());
        assertEquals("old", loaded.lastAccepted().sourceHash());
    }

    private ProjectContract empty() { return new ProjectContract(List.of(), List.of(), List.of(), List.of()); }

    /** v1和两种v2状态均保留原始字节；迁移不能重置预算、丢失失败或自动接受任务。 */
    @Test
    void migratesLegacyStatesOnSaveAndPreservesOriginalEvidence() throws Exception {
        var mapper = new ObjectMapper();
        var service = new DevelopmentMilestoneService(mapper);
        for (String legacyPolicy : List.of("missing", "ORDERED", "ADVISORY")) {
            Path project = root.resolve(legacyPolicy);
            Path metadata = project.resolve(".software-studio");
            java.nio.file.Files.createDirectories(metadata);
            String original = """
                    {"schemaVersion":%s,%s"tasks":[
                      {"id":"a","goal":"a","accepted":false},
                      {"id":"b","goal":"b","accepted":true,"sourceHash":"verified-b",
                       "snapshotPath":"saved-snapshot","checkpointReportPath":"saved-report"}],
                     "modelTurnsUsed":17,"toolCallsUsed":28,"lastFailure":"missing route"%s}
                    """.formatted(legacyPolicy.equals("missing") ? "1" : "2",
                    legacyPolicy.equals("missing") ? "" : "\"policy\":\"" + legacyPolicy + "\",",
                    legacyPolicy.equals("missing") ? "" : ",\"latestAcceptedId\":\"b\"");
            Path target = metadata.resolve(DevelopmentMilestoneService.STATE_FILE);
            Path backup = metadata.resolve(DevelopmentMilestoneService.LEGACY_STATE_BACKUP);
            java.nio.file.Files.writeString(target, original, java.nio.charset.StandardCharsets.UTF_8);

            var loaded = service.load(project);
            assertEquals("verified-b", loaded.lastAccepted().sourceHash());
            assertEquals(original, java.nio.file.Files.readString(target));
            assertFalse(java.nio.file.Files.exists(backup));
            var data = new SoftwareStudioWorkflowData("legacy project");
            data.projectPath = project.toString(); data.milestoneState = loaded;
            data.developmentModelTurnsUsed = loaded.modelTurnsUsed();
            data.developmentToolCallsUsed = loaded.toolCallsUsed();
            service.persist(data);

            var migrated = service.load(project);
            assertEquals(3, migrated.schemaVersion());
            assertEquals(17, migrated.modelTurnsUsed());
            assertEquals(28, migrated.toolCallsUsed());
            assertEquals("missing route", migrated.lastFailure());
            assertEquals(loaded.tasks(), migrated.tasks());
            assertEquals(loaded.lastAccepted(), migrated.lastAccepted());
            assertFalse(migrated.allAccepted());
            assertFalse(mapper.readTree(target.toFile()).has("policy"));
            assertEquals(original, java.nio.file.Files.readString(backup));
            service.recordFailure(data, "new verified failure");
            assertEquals(original, java.nio.file.Files.readString(backup));
        }
    }

    private WorkspaceCheckpointService.CheckpointResult checkpoint(String status) {
        return new WorkspaceCheckpointService.CheckpointResult("progress", status, "hash-1",
                root.resolve("source").toString(), root.resolve("report.json").toString(),
                null, null, true, List.of(), "", false, false);
    }
}
