package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectContractMergeServiceTest {

    /**
     * 契约已声明 MVC View 时应确定性补全模板蓝图，并继承 Controller 的切片归属。
     */
    @Test
    void addsMissingViewTemplateBlueprint() {
        var controller = new FileBlueprint(
                "ArticleController.java", "src/main/java/com/example/ArticleController.java",
                "controller", "article-crud", "articles", List.of(), List.of());
        var structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(controller));
        var contract = new ProjectContract(List.of(), List.of(new ViewContract(
                "article", "src/main/resources/templates/article.html",
                "ArticleController", "article detail")), List.of(), List.of());

        var merged = new ProjectContractMergeService(new SourceCodePathService()).merge(structure, contract);

        assertEquals(2, merged.files().size());
        FileBlueprint template = merged.files().get(1);
        assertEquals("src/main/resources/templates/article.html", template.targetPath());
        assertEquals("article-crud", template.batchName());
        assertEquals(List.of(controller.targetPath()), template.dependsOn());
    }
}
