package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;

import java.io.Serializable;
import java.util.List;

/**
 * 聚合 ProjectProfile 归一化和确定性质量门禁的执行结果。
 */
public record ProjectQualityPolicyResult(
        boolean passed,
        List<QualityPolicyFinding> findings,
        List<String> normalizedFiles) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ProjectQualityPolicyResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        normalizedFiles = normalizedFiles == null ? List.of() : List.copyOf(normalizedFiles);
    }

    public static ProjectQualityPolicyResult empty() {
        return new ProjectQualityPolicyResult(true, List.of(), List.of());
    }

    /**
     * 返回所有必须阻断最终成功判定的门禁结果。
     */
    public List<QualityPolicyFinding> blockingFindings() {
        return findings.stream()
                .filter(finding -> finding.severity() == QualityGateSeverity.BLOCK)
                .toList();
    }

    /**
     * Profile 和源码结构问题会阻止项目进入 Docker，避免执行已知无效工程。
     */
    public List<QualityPolicyFinding> technicalBlockingFindings() {
        return blockingFindings().stream()
                .filter(finding -> finding.failureKind() != FailureKind.CONTRACT)
                .toList();
    }

    /**
     * 语义契约问题在编译和测试之后判断，确保有限修复预算优先处理技术错误。
     */
    public List<QualityPolicyFinding> contractBlockingFindings() {
        return blockingFindings().stream()
                .filter(finding -> finding.failureKind() == FailureKind.CONTRACT)
                .toList();
    }
}
