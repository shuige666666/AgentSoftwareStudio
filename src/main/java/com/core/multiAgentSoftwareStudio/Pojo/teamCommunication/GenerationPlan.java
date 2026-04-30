package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public record GenerationPlan(List<GenerationBatch> batches) implements Serializable {
    private static final long serialVersionUID = 1L;


    public GenerationPlan {
        if (batches == null) {
            batches = Collections.emptyList();
        }
    }
}
