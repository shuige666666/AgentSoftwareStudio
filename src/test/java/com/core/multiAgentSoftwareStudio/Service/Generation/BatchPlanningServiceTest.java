package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchPlanningServiceTest {

    @Test
    void groupsCompleteGenerationByDependencyLayerInsteadOfBusinessSliceName() {
        FileBlueprint controller = file("OrderController.java", "controller", "orders");
        FileBlueprint dto = file("OrderRequest.java", "dto", "orders");
        FileBlueprint service = file("OrderService.java", "service", "orders");
        FileBlueprint sharedDto = file("UserRequest.java", "dto", "users");

        var plan = new BatchPlanningService(new SourceCodePathService()).createPlan(
                new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App",
                        List.of(controller, dto, service, sharedDto)));

        assertEquals(List.of("dto", "service", "controller"),
                plan.batches().stream().map(batch -> batch.name()).toList());
        assertEquals(2, plan.batches().get(0).files().size());
    }

    private FileBlueprint file(String name, String layer, String businessSlice) {
        return new FileBlueprint(
                name,
                "src/main/java/com/example/" + name,
                layer,
                businessSlice,
                "implement " + name,
                List.of(),
                List.of());
    }
}
