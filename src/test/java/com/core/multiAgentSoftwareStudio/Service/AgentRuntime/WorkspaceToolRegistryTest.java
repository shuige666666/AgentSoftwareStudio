package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DockerSandboxService sandboxService = mock(DockerSandboxService.class);
    private final WorkspaceToolRegistry registry = new WorkspaceToolRegistry(
            objectMapper,
            sandboxService,
            new WorkspaceService(new SourceCodePathService()),
            mock(ProjectProfileService.class));

    @TempDir
    Path projectRoot;

    @Test
    void planningMetadataIsExcludedFromAgentFileListingAndSnapshots() throws Exception {
        Path metadata = projectRoot.resolve(PlanningArtifactPersistenceService.METADATA_DIRECTORY);
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("contract.json"), "{\"internal\":true}", StandardCharsets.UTF_8);
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of());

        String listed = registry.execute("list_files", "{}", session);
        Map<String, String> snapshot = registry.snapshotWorkspace(projectRoot);

        assertFalse(listed.contains(".software-studio"));
        assertFalse(snapshot.keySet().stream().anyMatch(path -> path.startsWith(".software-studio/")));
        assertTrue(snapshot.containsKey("src/main/java/demo/App.java"));
    }

    @Test
    void rejectsStaleEditAndPreservesCurrentFile() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of(
                "src/main/java/demo/App.java", "class App {}"));

        String result = registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"stale",
                "oldText":"class App {}","newText":"class App { int value; }"}]}
                """, session);

        assertTrue(result.contains("STALE_FILE"));
        assertEquals("class App {}", Files.readString(source, StandardCharsets.UTF_8));
        assertTrue(session.changedFiles().isEmpty());
    }

    @Test
    void testAgentCannotModifyProductionSource() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.TEST, Map.of());
        String hash = readHash(session, "src/main/java/demo/App.java");

        String result = registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"%s",
                "oldText":"class App {}","newText":"class App { int value; }"}]}
                """.formatted(hash), session);

        assertTrue(result.contains("outside the current Agent scope"));
        assertEquals("class App {}", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void completionRequiresVerificationAfterLatestWrite() throws Exception {
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of());
        String missingHash = readHash(session, "src/main/java/demo/App.java");
        registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"%s",
                "oldText":"","newText":"class App {}"}]}
                """.formatted(missingHash), session);

        String beforeCompile = registry.execute("complete_stage", "{\"summary\":\"done\"}", session);
        assertTrue(beforeCompile.contains("VERIFICATION_REQUIRED"));
        assertFalse(session.completed());

        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        registry.execute("compile_main", "{}", session);
        String completed = registry.execute("complete_stage", "{\"summary\":\"done\"}", session);

        assertTrue(completed.contains("accepted"));
        assertTrue(session.completed());
    }

    @Test
    void finalDiffIgnoresFilesRestoredToBaselineContent() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of(
                "src/main/java/demo/App.java", "class App {}"));
        session.recordWrite("src/main/java/demo/App.java");

        assertTrue(registry.actualChangedFiles(session).isEmpty());
    }

    private WorkspaceAgentSession session(WorkspaceAgentMode mode, Map<String, String> baseline) {
        return new WorkspaceAgentSession(
                projectRoot,
                mode,
                new SoftwareStudioWorkflowData("test"),
                message -> { },
                baseline,
                Set.of());
    }

    private String readHash(WorkspaceAgentSession session, String path) throws Exception {
        JsonNode result = objectMapper.readTree(registry.execute(
                "read_files", "{\"paths\":[\"" + path + "\"]}", session));
        return result.at("/files/0/sha256").asText();
    }
}
