package com.core.multiAgentSoftwareStudio.Service.Workspace;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage.REQUIREMENT;

class PlanningArtifactPersistenceServiceTest {

    @TempDir
    Path projectRoot;

    @Test
    void requirementCheckpointDoesNotPersistDefaultEmptyFuturePlans() {
        PlanningArtifactPersistenceService service = new PlanningArtifactPersistenceService(new ObjectMapper());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("生成一个项目");
        data.projectPath = projectRoot.toString();
        data.prd = new PrdDocument("项目", "目标", List.of(), List.of());

        service.persistStage(data, REQUIREMENT, message -> { });

        Path metadata = projectRoot.resolve(PlanningArtifactPersistenceService.METADATA_DIRECTORY);
        assertTrue(Files.exists(metadata.resolve("prd.json")));
        assertFalse(Files.exists(metadata.resolve("architecture.json")));
        assertFalse(Files.exists(metadata.resolve("slice-plan.json")));
        assertFalse(Files.exists(metadata.resolve("generation-plan.json")));
    }

    @Test
    void persistsPlanningArtifactsAndRunSummaryAsReloadableFiles() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PlanningArtifactPersistenceService service = new PlanningArtifactPersistenceService(objectMapper);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("生成一个短链接服务");
        data.projectPath = projectRoot.toString();
        data.prd = new PrdDocument("短链接服务", "生成并访问短链接", List.of("创建短链接"), List.of("Spring Boot"));
        data.structure = new ProjectStructure("com.example.shorturl", "SPRING_BOOT", "ShortUrlApplication", List.of());
        data.contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = SliceDeliveryPlan.empty();
        data.generationPlan = new GenerationPlan(List.of());

        service.persistAvailable(data, message -> { });
        service.persistRunSummary(data, WorkflowRunSummary.empty(), message -> { });

        Path metadata = projectRoot.resolve(PlanningArtifactPersistenceService.METADATA_DIRECTORY);
        assertTrue(Files.readString(metadata.resolve("user-request.md"), StandardCharsets.UTF_8)
                .contains("生成一个短链接服务"));
        assertEquals("短链接服务", objectMapper.readTree(metadata.resolve("prd.json").toFile())
                .path("projectName").asText());
        assertTrue(Files.exists(metadata.resolve("architecture.md")));
        assertTrue(Files.exists(metadata.resolve("contract.json")));
        assertTrue(Files.exists(metadata.resolve("slice-plan.json")));
        assertTrue(Files.exists(metadata.resolve("generation-plan.json")));
        assertTrue(Files.exists(metadata.resolve("run-summary.json")));
        JsonNode manifest = objectMapper.readTree(metadata.resolve("manifest.json").toFile());
        assertTrue(manifest.path("artifacts").toString().contains("contract.json"));
    }

    @Test
    void metadataDirectoryIsNotReloadedAsGeneratedSource() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class App {}", StandardCharsets.UTF_8);
        Path metadata = projectRoot.resolve(PlanningArtifactPersistenceService.METADATA_DIRECTORY);
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("contract.json"), "{\"internal\":true}", StandardCharsets.UTF_8);

        WorkspaceService workspaceService = new WorkspaceService(new SourceCodePathService());
        var loaded = workspaceService.loadProjectFromDisk(projectRoot, message -> { });

        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().filename().replace('\\', '/').endsWith("src/main/java/demo/App.java"));
        assertFalse(loaded.stream().anyMatch(code -> code.filename().contains(".software-studio")));
    }
}
