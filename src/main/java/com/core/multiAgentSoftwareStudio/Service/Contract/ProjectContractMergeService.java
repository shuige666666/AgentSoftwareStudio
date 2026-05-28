package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        return new ProjectStructure(
                structure.rootPackage(),
                structure.projectType(),
                structure.mainClassName(),
                new ArrayList<>(filesByPath.values()));
    }
}
