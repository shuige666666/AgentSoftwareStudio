package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevelopmentResumeServiceTest {
    @TempDir Path root;

    /** 中断续接从磁盘候选和已持久化的规划读取，沿用此前消耗的模型与工具轮次。 */
    @Test
    void loadsCurrentCandidateAndGlobalBudget() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DevelopmentMilestoneService milestones = new DevelopmentMilestoneService(mapper);
        Path metadata = root.resolve(".software-studio");
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("user-request.md"), "# User Request\n\nBuild a demo\n");
        var prd = new PrdDocument("demo", "demo", List.of("create", "vote"), List.of());
        var structure = new ProjectStructure("demo", "SPRING_BOOT", "demo.App", List.of());
        var contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var plan = new SliceDeliveryPlan(List.of(slice("create", contract), slice("vote", contract)),
                List.of(), "test");
        mapper.writeValue(metadata.resolve("prd.json").toFile(), prd);
        mapper.writeValue(metadata.resolve("architecture.json").toFile(), structure);
        mapper.writeValue(metadata.resolve("contract.json").toFile(), contract);
        mapper.writeValue(metadata.resolve("slice-plan.json").toFile(), plan);
        mapper.writeValue(metadata.resolve("generation-plan.json").toFile(), new GenerationPlan(List.of()));
        var data = new SoftwareStudioWorkflowData("Build a demo");
        data.projectPath = root.toString();
        data.sliceDeliveryPlan = plan;
        milestones.initialize(data);
        data.developmentModelTurnsUsed = 12;
        data.developmentToolCallsUsed = 23;
        data.behaviorCheckId = "poll-system-behavior-v1";
        milestones.persist(data);
        Path code = root.resolve("src/main/java/demo/App.java");
        Files.createDirectories(code.getParent());
        Files.writeString(code, "package demo; class App {}", StandardCharsets.UTF_8);
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        when(profiles.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        var service = new DevelopmentResumeService(mapper, milestones,
                mock(WorkspaceCheckpointService.class), profiles,
                new WorkspaceService(new SourceCodePathService()));

        var resumed = service.loadResolved(root);

        assertEquals("Build a demo", resumed.userRequest);
        assertEquals(12, resumed.developmentModelTurnsUsed);
        assertEquals(23, resumed.developmentToolCallsUsed);
        assertEquals("poll-system-behavior-v1", resumed.behaviorCheckId);
        assertEquals("create", resumed.milestoneState.current().id());
        assertTrue(resumed.codes.stream().anyMatch(source -> source.filename().endsWith("App.java")));
        assertThrows(IllegalArgumentException.class, () -> service.load("../outside"));
    }

    /** 旧强制状态只恢复真实进度和预算，读取时不改写原状态或规划。 */
    @Test
    void resumesLegacyStateWithoutReintroducingOrderedControl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var milestones = new DevelopmentMilestoneService(mapper);
        Path metadata = root.resolve(".software-studio");
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("user-request.md"), "# User Request\n\nDemo\n");
        var contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var plan = new SliceDeliveryPlan(List.of(slice("a", contract), slice("b", contract)), List.of(), "test");
        mapper.writeValue(metadata.resolve("prd.json").toFile(), new PrdDocument("demo", "demo", List.of(), List.of()));
        mapper.writeValue(metadata.resolve("architecture.json").toFile(), new ProjectStructure("demo", "SPRING_BOOT", "demo.App", List.of()));
        mapper.writeValue(metadata.resolve("contract.json").toFile(), contract);
        mapper.writeValue(metadata.resolve("slice-plan.json").toFile(), plan);
        mapper.writeValue(metadata.resolve("generation-plan.json").toFile(), new GenerationPlan(List.of()));
        var data = new SoftwareStudioWorkflowData("Demo");
        data.projectPath = root.toString(); data.sliceDeliveryPlan = plan;
        milestones.initialize(data);
        Path statePath = metadata.resolve(DevelopmentMilestoneService.STATE_FILE);
        var legacy = mapper.readTree(statePath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).put("schemaVersion", 2)
                .put("policy", "ORDERED").put("modelTurnsUsed", 17).put("toolCallsUsed", 28)
                .put("lastFailure", "Observed failure: missing route");
        String originalState = mapper.writeValueAsString(legacy);
        Files.writeString(statePath, originalState, StandardCharsets.UTF_8);
        var profiles = mock(ProjectProfileService.class);
        when(profiles.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        var resumed = new DevelopmentResumeService(mapper, milestones, mock(WorkspaceCheckpointService.class), profiles,
                new WorkspaceService(new SourceCodePathService())).loadResolved(root);
        assertEquals(17, resumed.developmentModelTurnsUsed);
        assertEquals(28, resumed.developmentToolCallsUsed);
        assertEquals("Observed failure: missing route", resumed.milestoneState.lastFailure());
        assertTrue(resumed.acceptedSliceIds.isEmpty());
        milestones.validateSelection(resumed, List.of("b", "a"));
        assertEquals(originalState, Files.readString(statePath, StandardCharsets.UTF_8));
    }

    private DeliverySlice slice(String id, ProjectContract contract) {
        return new DeliverySlice(id, id, List.of(), List.of(), contract, List.of(), List.of());
    }
}
