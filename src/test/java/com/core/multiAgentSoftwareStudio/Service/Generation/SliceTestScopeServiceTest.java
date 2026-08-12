package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceTestScopeServiceTest {

    private final SliceTestScopeService service = new SliceTestScopeService(new SourceCodePathService());

    /**
     * 当前切片测试不能引用未来 Java 类型或未来前端资源，但可以引用已生成类型。
     */
    @Test
    void rejectsFutureTypesAndResources() {
        FileBlueprint controller = file("src/main/java/com/example/PollController.java", "poll");
        FileBlueprint frontend = file("src/main/resources/static/index.html", "frontend");
        DeliverySlice current = slice("poll", controller);
        DeliverySlice future = slice("frontend", frontend);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(controller, frontend));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(current, future), List.of(), "test-policy");
        data.codes.add(new SourceCode(controller.targetPath(), "java", "class PollController {}"));

        assertTrue(service.isInScope(data, new SourceCode(
                "src/test/java/com/example/PollControllerTest.java", "java",
                "class PollControllerTest { PollController controller; }")));
        assertFalse(service.isInScope(data, new SourceCode(
                "src/test/java/com/example/FrontendContractTest.java", "java",
                "new ClassPathResource(\"static/index.html\");")));
    }

    /**
     * 注释和断言文本提到未来类型不应被误判，只有真实 Java 类型引用才越界。
     */
    @Test
    void ignoresFutureTypeInCommentsAndStrings() {
        FileBlueprint poll = file("src/main/java/com/example/Poll.java", "poll");
        FileBlueprint vote = file("src/main/java/com/example/Vote.java", "vote");
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(poll, vote));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(slice("poll", poll), slice("vote", vote)),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(poll.targetPath(), "java", "class Poll {}"));

        assertTrue(service.isInScope(data, new SourceCode(
                "src/test/java/com/example/PollTest.java", "java",
                "class PollTest { String label = \"Vote\"; /* Vote comes later */ }")));
        assertFalse(service.isInScope(data, new SourceCode(
                "src/test/java/com/example/PollTest.java", "java",
                "class PollTest { Vote vote; }")));
    }

    private DeliverySlice slice(String id, FileBlueprint file) {
        return new DeliverySlice(id, id, List.of(), List.of(file),
                new ProjectContract(List.of(), List.of(), List.of(), List.of()), List.of(), List.of());
    }

    private FileBlueprint file(String path, String batch) {
        return new FileBlueprint(java.nio.file.Path.of(path).getFileName().toString(), path,
                "base", batch, "test", List.of(), List.of());
    }
}
