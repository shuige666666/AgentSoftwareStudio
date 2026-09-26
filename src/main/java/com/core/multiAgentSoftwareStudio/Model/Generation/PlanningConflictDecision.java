package com.core.multiAgentSoftwareStudio.Model.Generation;

/**
 * 规划冲突审查结论；未接受修改时沿用当前有效设计。
 */
public record PlanningConflictDecision(boolean revised, String reason, PlanningDesign revisedDesign) { }
