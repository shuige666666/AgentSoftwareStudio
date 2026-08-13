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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceProductionScopeServiceTest {

    private final SourceCodePathService pathService = new SourceCodePathService();
    private final SliceProductionScopeService service = new SliceProductionScopeService(pathService);

    /**
     * 当前切片只能看到已交付和本切片蓝图，并能识别真实代码中的未来类型引用。
     */
    @Test
    void limitsVisibleStructureAndFindsFutureTypeReference() {
        FileBlueprint comment = file("src/main/java/com/example/Comment.java", "comments");
        FileBlueprint article = file("src/main/java/com/example/Article.java", "articles");
        SoftwareStudioWorkflowData data = data(comment, article);
        data.codes.add(new SourceCode(comment.targetPath(), "java", "class Comment { private Article article; }"));

        ProjectStructure active = service.activeStructure(data);

        assertEquals(List.of(comment), active.files());
        assertEquals(1, service.findFutureTypeReferences(data).size());
        assertTrue(service.findFutureTypeReferences(data).getFirst().contains("Article"));
    }

    /**
     * 注释和字符串中提到未来类型不构成编译依赖，避免门禁产生无意义误报。
     */
    @Test
    void ignoresFutureTypeMentionInCommentAndString() {
        FileBlueprint comment = file("src/main/java/com/example/Comment.java", "comments");
        FileBlueprint article = file("src/main/java/com/example/Article.java", "articles");
        SoftwareStudioWorkflowData data = data(comment, article);
        data.codes.add(new SourceCode(comment.targetPath(), "java",
                "class Comment { // Article arrives later\n String note = \"Article\"; }"));

        assertTrue(service.findFutureTypeReferences(data).isEmpty());
    }

    private SoftwareStudioWorkflowData data(FileBlueprint current, FileBlueprint future) {
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(current, future));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("comments", "Comments", List.of(), List.of(current), contract, List.of(), List.of()),
                new DeliverySlice("articles", "Articles", List.of(), List.of(future), contract, List.of(), List.of())),
                List.of(), "test-policy");
        return data;
    }

    private FileBlueprint file(String path, String batch) {
        return new FileBlueprint(java.nio.file.Path.of(path).getFileName().toString(), path,
                "base", batch, "test", List.of(), List.of());
    }
}
