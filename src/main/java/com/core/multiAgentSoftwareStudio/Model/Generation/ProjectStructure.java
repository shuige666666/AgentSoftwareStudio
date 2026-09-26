package com.core.multiAgentSoftwareStudio.Model.Generation;



import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 规划步骤给出的工程类型、入口和业务文件建议。
 */
public record ProjectStructure(
        String rootPackage,
        String projectType,
        String mainClassName,
        List<FileBlueprint> files
) implements Serializable {
        private static final long serialVersionUID = 1L;

        public ProjectStructure {
                if (files == null) {
                        files = Collections.emptyList();
                }
        }
}
