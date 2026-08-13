package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchGenerationServiceTest {

    /**
     * 即使架构师遗漏 dependsOn，下游层文件也必须看到上游真实代码，并且只接收当前切片契约。
     */
    @Test
    void generatesDependencyWavesWithSliceScopedContract() {
        DeveloperAgent developer = mock(DeveloperAgent.class);
        CodeContextBuilderService contextBuilder = mock(CodeContextBuilderService.class);
        SourceCodePathService pathService = new SourceCodePathService();
        when(contextBuilder.buildRelevantCodeContext(any(), any(), any())).thenAnswer(invocation -> {
            List<SourceCode> snapshot = invocation.getArgument(0);
            return snapshot.stream().map(SourceCode::filename).toList().toString();
        });
        when(developer.writeCode(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(4);
                    String context = invocation.getArgument(3);
                    return new SourceCode(path, "java", "// context=" + context + "\nclass Generated {}");
                });

        String servicePath = "src/main/java/com/example/PollService.java";
        String controllerPath = "src/main/java/com/example/PollController.java";
        FileBlueprint serviceFile = file(servicePath, List.of());
        FileBlueprint controllerFile = file(controllerPath, List.of());
        ProjectContract fullContract = new ProjectContract(List.of(
                new ApiEndpointContract("GET", "/unrelated", "", "", "OtherController", "other")),
                List.of(), List.of(), List.of());
        ProjectContract sliceContract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/polls", "", "", "PollController", "create")),
                List.of(), List.of(), List.of());
        DeliverySlice slice = new DeliverySlice(
                "poll", "Poll", List.of(), List.of(controllerFile, serviceFile), sliceContract, List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.contract = fullContract;
        FileBlueprint futureFile = new FileBlueprint(
                "Vote.java", "src/main/java/com/example/Vote.java", "base", "poll-voting",
                "future", List.of(), List.of());
        data.structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(controllerFile, serviceFile, futureFile));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice), List.of(), "test-policy");

        new BatchGenerationService(
                developer, contextBuilder, pathService, new SliceProductionScopeService(pathService))
                .generateSlice(data, message -> { });

        SourceCode controller = data.codes.stream()
                .filter(code -> code.filename().equals(controllerPath)).findFirst().orElseThrow();
        assertTrue(controller.code().contains(servicePath));
        ArgumentCaptor<ProjectContract> contracts = ArgumentCaptor.forClass(ProjectContract.class);
        verify(developer, org.mockito.Mockito.times(2))
                .writeCode(any(), any(), contracts.capture(), any(), any(), any(), any(), any());
        assertEquals(List.of(sliceContract, sliceContract), contracts.getAllValues());
        ArgumentCaptor<ProjectStructure> structures = ArgumentCaptor.forClass(ProjectStructure.class);
        verify(developer, org.mockito.Mockito.times(2))
                .writeCode(any(), structures.capture(), any(), any(), any(), any(), any(), any());
        assertTrue(structures.getAllValues().stream()
                .flatMap(structure -> structure.files().stream())
                .noneMatch(file -> file.targetPath().equals(futureFile.targetPath())));
    }

    private FileBlueprint file(String path, List<String> dependencies) {
        return new FileBlueprint(
                java.nio.file.Path.of(path).getFileName().toString(), path,
                path.contains("Controller") ? "controller" : "service", "poll",
                "test", List.of(), dependencies);
    }
}
