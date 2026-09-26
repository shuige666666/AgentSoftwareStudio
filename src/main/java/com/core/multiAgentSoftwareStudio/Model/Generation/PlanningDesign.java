package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 同一次规划设计调用给出的工程结构、业务设计和公开契约。
 */
public record PlanningDesign(
        ProjectStructure structure,
        ProjectContract contract,
        String domainDesign,
        Map<String, List<String>> behaviorCoverage
) implements Serializable {
    public PlanningDesign {
        domainDesign = domainDesign == null ? "" : domainDesign.trim();
        behaviorCoverage = behaviorCoverage == null ? Map.of() : Map.copyOf(behaviorCoverage);
    }
}
