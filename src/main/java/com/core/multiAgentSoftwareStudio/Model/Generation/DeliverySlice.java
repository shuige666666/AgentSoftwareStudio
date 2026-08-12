package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;

import java.io.Serializable;
import java.util.List;

/**
 * 描述一个可独立生成、验证和验收的垂直业务切片。
 */
public record DeliverySlice(
        String id,
        String name,
        List<String> acceptanceCriteria,
        List<FileBlueprint> files,
        ProjectContract contract,
        List<String> dependsOnSlices,
        List<String> sharedFiles) implements Serializable {

    private static final long serialVersionUID = 1L;

    public DeliverySlice {
        id = id == null || id.isBlank() ? "unnamed-slice" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        files = files == null ? List.of() : List.copyOf(files);
        contract = contract == null ? new ProjectContract(List.of(), List.of(), List.of(), List.of()) : contract;
        dependsOnSlices = dependsOnSlices == null ? List.of() : List.copyOf(dependsOnSlices);
        sharedFiles = sharedFiles == null ? List.of() : List.copyOf(sharedFiles);
    }

    /**
     * 返回当前切片声明拥有的生产文件路径。
     */
    public List<String> ownedFiles() {
        return files.stream().map(FileBlueprint::targetPath).toList();
    }

    public GenerationBatch asGenerationBatch() {
        return new GenerationBatch(name, "vertical-slice", files);
    }
}
