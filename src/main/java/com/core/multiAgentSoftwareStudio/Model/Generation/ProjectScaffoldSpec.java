package com.core.multiAgentSoftwareStudio.Model.Generation;

import java.io.Serializable;
import java.util.List;

/**
 * 平台在业务编码前渲染的固定工程骨架输入。
 */
public record ProjectScaffoldSpec(
        String projectName,
        String rootPackage,
        String mainClassName,
        ProjectProfile profile,
        List<String> baseDependencies
) implements Serializable {
    public ProjectScaffoldSpec {
        baseDependencies = baseDependencies == null ? List.of() : List.copyOf(baseDependencies);
    }
}
