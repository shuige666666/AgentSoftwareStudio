package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public record GenerationBatch(
        String name,
        String layer,
        List<FileBlueprint> files) implements Serializable {
    private static final long serialVersionUID = 1L;


    public GenerationBatch {
        if (name == null || name.isBlank()) {
            name = "unnamed-batch";
        }
        if (layer == null || layer.isBlank()) {
            layer = "base";
        }
        if (files == null) {
            files = Collections.emptyList();
        }
    }
}
