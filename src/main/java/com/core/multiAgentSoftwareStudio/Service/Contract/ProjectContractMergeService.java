package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将接口契约中的补充文件合并回项目结构
 */
@Service
public class ProjectContractMergeService {

    private final SourceCodePathService sourceCodePathService;

    public ProjectContractMergeService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 合并架构文件和契约补充文件，确保后续批次规划能看到 DTO、模板和前端资源
     */
    public ProjectStructure merge(ProjectStructure structure, ProjectContract contract) {
        if (structure == null) {
            return new ProjectStructure("", "PURE_JAVA_MAVEN", "", List.of());
        }

        Map<String, FileBlueprint> filesByPath = new LinkedHashMap<>();
        if (structure.files() != null) {
            for (FileBlueprint file : structure.files()) {
                filesByPath.put(sourceCodePathService.normalizePath(file.targetPath()), file);
            }
        }

        if (contract != null && contract.additionalFiles() != null) {
            for (FileBlueprint file : contract.additionalFiles()) {
                filesByPath.putIfAbsent(sourceCodePathService.normalizePath(file.targetPath()), file);
            }
        }

        // ViewContract 已经明确承诺模板时，确定性补全遗漏蓝图，避免 Controller 运行后才暴露缺页。
        if (contract != null && contract.views() != null) {
            for (ViewContract view : contract.views()) {
                addMissingViewTemplate(filesByPath, view);
            }
        }

        return new ProjectStructure(
                structure.rootPackage(),
                structure.projectType(),
                structure.mainClassName(),
                new ArrayList<>(filesByPath.values()));
    }

    /**
     * 将契约中的 Thymeleaf 视图转换为模板蓝图，并继承对应 Controller 的业务切片归属。
     */
    private void addMissingViewTemplate(Map<String, FileBlueprint> filesByPath, ViewContract view) {
        String templatePath = sourceCodePathService.normalizePath(view.templatePath());
        if (!templatePath.startsWith("src/main/resources/templates/") || !templatePath.endsWith(".html")
                || filesByPath.containsKey(templatePath)) {
            return;
        }
        String controllerType = simpleTypeName(view.returnedBy());
        FileBlueprint controller = filesByPath.values().stream()
                .filter(file -> simpleTypeName(file.targetPath()).equals(controllerType))
                .findFirst()
                .orElse(null);
        String batchName = controller == null ? "server-rendered-views" : controller.batchName();
        List<String> dependencies = controller == null ? List.of() : List.of(controller.targetPath());
        filesByPath.put(templatePath, new FileBlueprint(
                Path.of(templatePath).getFileName().toString(),
                templatePath,
                "frontend",
                batchName,
                "Render MVC view " + view.name() + " according to the declared view contract.",
                List.of(),
                dependencies));
    }

    private String simpleTypeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = sourceCodePathService.normalizePath(value);
        String name = Path.of(normalized).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
