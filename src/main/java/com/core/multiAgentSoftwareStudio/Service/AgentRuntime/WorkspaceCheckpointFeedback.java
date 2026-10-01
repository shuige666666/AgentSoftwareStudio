package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import java.util.regex.Pattern;

/** 为后续修复整理独立行为失败，完整判卷仍保留在原 checkpoint 和审计中。 */
final class WorkspaceCheckpointFeedback {
    private static final Pattern HTTP_STATUS = Pattern.compile("^HTTP (\\d{3})\\b");
    private static final Pattern EXPECTED_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");
    private WorkspaceCheckpointFeedback() { }

    /** 摘要只展开失败和阻塞项，避免成功响应的 HTML 挤掉真正需要修复的请求。 */
    static String behaviorFailures(BenchmarkBehaviorResult result, String sourceHash, String reportPath) {
        StringBuilder feedback = new StringBuilder("Public behavior checks sourceHash=")
                .append(sourceHash).append(" status=").append(result.status())
                .append(" passed=").append(result.findings().stream().filter(finding -> finding.passed()).count())
                .append(" unresolved=").append(result.findings().stream().filter(finding -> !finding.passed()).count())
                .append(" report=").append(reportPath).append('\n');
        result.findings().stream().filter(finding -> !finding.passed()).forEach(finding ->
                feedback.append("- ").append(finding.behavior()).append(" [").append(finding.status())
                        .append("] request=").append(finding.request()).append(" expected=").append(finding.expected())
                        .append(" actual=").append(conciseEvidence(finding.evidence(), finding.expected())).append('\n'));
        return feedback.toString();
    }

    /** 长响应仅保留短诊断，明确说明省略，不能把它当作完整响应正文。 */
    private static String conciseEvidence(String evidence, String expected) {
        if (evidence == null) return "";
        var actualStatus = HTTP_STATUS.matcher(evidence);
        var expectedStatus = EXPECTED_STATUS.matcher(expected == null ? "" : expected);
        int body = evidence.indexOf("; body=");
        // 已由状态码证明失败时，页面样式不是额外失败证据，不应挤掉其他失败请求。
        if (body > 0 && actualStatus.find() && expectedStatus.find()
                && !actualStatus.group(1).equals(expectedStatus.group(1))) {
            return evidence.substring(0, body) + " [body omitted: status mismatch]";
        }
        if (evidence.length() <= 160) return evidence;
        return evidence.substring(0, 120) + " ... [response evidence omitted] ... "
                + evidence.substring(evidence.length() - 40);
    }
}
