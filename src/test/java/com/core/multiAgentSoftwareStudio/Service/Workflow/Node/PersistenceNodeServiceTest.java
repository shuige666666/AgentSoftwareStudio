package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PersistenceNodeServiceTest {

    /**
     * 门禁前快照应写入当前切片产物，但不能把切片误标为已经通过持久化门禁。
     */
    @Test
    void snapshotsCurrentSliceWithoutMarkingItPersisted() {
        WorkspaceService workspace = mock(WorkspaceService.class);
        PersistenceNodeService service = new PersistenceNodeService(workspace);
        FileBlueprint app = new FileBlueprint(
                "App.java", "src/main/java/com/example/App.java", "base", "app", "app", List.of(), List.of());
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = "ai_generated_projects/test";
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("app", "App", List.of(), List.of(app), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(app.targetPath(), "java", "class App {}"));

        service.snapshotCurrentSlice(data, message -> { });

        verify(workspace).writeSourceFilesToDisk(eq(Path.of(data.projectPath)), any(), any());
        assertFalse(data.currentSlicePersisted);
    }
}
