package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import java.util.regex.Pattern;

/** 为后续修复整理独立行为失败，完整判卷仍保留在原 checkpoint 和审计中。 */
public final class WorkspaceCheckpointFeedback {
    private static final Pattern HTTP_STATUS = Pattern.compile("^HTTP (\\d{3})\\b");
    private static final Pattern EXPECTED_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");
    private WorkspaceCheckpointFeedback() { }

    /** 工具内验收与会话外最终验收使用相同的诊断范围，普通绿色不能清除未覆盖的失败。 */
    static void record(WorkspaceAgentSession session, WorkspaceCheckpointService.CheckpointResult result) {
        recordObservation(session, result);
        if ("final".equals(result.scope())) {
            String finding = finalFindings(result);
            session.recordFinalFeedback(finding.isBlank() ? "" : "Final contract/delivery last checked at writeVersion="
                    + session.writeVersion() + " sourceHash=" + result.sourceHash() + " report=" + result.reportPath()
                    + "; re-check final after edits.\n" + finding);
        }
        if (!"NOT_APPLICABLE".equals(result.behavior().status())) {
            session.recordBehaviorFeedback(result.behavior().passed() ? "" :
                    behaviorFailures(result.behavior(), result.sourceHash(), result.reportPath()));
        }
        if ("PASSED".equals(result.status()) || result.build() != null && result.test() != null
                && result.build().passed() && result.test().passed()) {
            session.clearVerificationFailure();
        } else if (!"BLOCKED".equals(result.status()) && !result.failureOutput().isBlank()) {
            session.recordVerificationFailure("checkpoint " + result.scope() + ": " + result.failureOutput());
        }
    }

    /** 保留同一正式快照的状态对照，不重复成功响应正文，也不改变失败生命周期。 */
    private static void recordObservation(WorkspaceAgentSession session, WorkspaceCheckpointService.CheckpointResult result) {
        var testSummary = result.test() == null
                ? com.core.multiAgentSoftwareStudio.Model.Workflow.TestSummary.empty() : result.test().testSummary();
        int behaviorPassed = (int) result.behavior().findings().stream().filter(finding -> finding.passed()).count();
        session.recordCheckpointObservation(new WorkspaceAgentSession.CheckpointObservation(session.writeVersion(),
                result.sourceHash(), result.scope(), result.status(),
                result.build() == null ? "NOT_RUN" : result.build().status().name(),
                result.test() == null ? "NOT_RUN" : result.test().status().name(),
                testSummary.summaryPresent(), testSummary.testsRun(), testSummary.failures(), testSummary.errors(), testSummary.skipped(),
                result.behavior().status(), behaviorPassed, result.behavior().findings().size() - behaviorPassed,
                result.reportPath()));
    }

    /** 保存最终失败的真实验证证据，避免续接状态只剩额度停止；完整报告仍由路径定位。 */
    public static String failureSummary(WorkspaceCheckpointService.CheckpointResult result) {
        String source = result.failureOutput();
        int behaviorStart = source.indexOf("\nPublic behavior checks (sourceHash=");
        if (behaviorStart >= 0) source = source.substring(0, behaviorStart);
        // 状态只保存有界的源代码诊断；成功响应正文不再重复写入下一次开发目标。
        if (source.length() > 4_000) source = source.substring(0, 4_000) + "\n[diagnostic omitted; full report retained]";
        String behavior = behaviorFailures(result.behavior(), result.sourceHash(), result.reportPath());
        if (behavior.length() > 4_000) behavior = behavior.substring(0, 4_000) + "\n[remaining findings retained in report]";
        return "Final verification sourceHash=" + result.sourceHash() + " build=" + result.build().status()
                + " tests=" + result.test().status() + " contractPassed=" + result.contractPassed()
                + " report=" + result.reportPath() + "\n" + source + "\n" + finalFindings(result) + "\n" + behavior;
    }

    /** 完整验收诊断单独取结构化字段，避免被前面的构建长日志截掉。 */
    static String finalFindings(WorkspaceCheckpointService.CheckpointResult result) {
        String contract = result.contractPassed() ? "" : "Full contract: " + String.join("\n", result.contractBlockers());
        String findings = (contract + "\n" + result.deliveryFailure()).trim();
        return findings.length() <= 4_000 ? findings : findings.substring(0, 4_000)
                + "\n[remaining final findings retained in report]";
    }

    /** 摘要只展开失败和阻塞项，避免成功响应的 HTML 挤掉真正需要修复的请求。 */
    static String behaviorFailures(BenchmarkBehaviorResult result, String sourceHash, String reportPath) {
        StringBuilder feedback = new StringBuilder("Public behavior checks sourceHash=")
                .append(sourceHash).append(" status=").append(result.status())
                .append(" passed=").append(result.findings().stream().filter(finding -> finding.passed()).count())
                .append(" unresolved=").append(result.findings().stream().filter(finding -> !finding.passed()).count())
                .append(" report=").append(reportPath).append('\n');
        // 未启动或环境阻塞没有逐项判卷，具体原因仍须进入续接与压缩摘要。
        if (!"NOT_APPLICABLE".equals(result.status()) && result.reason() != null && !result.reason().isBlank()) {
            feedback.append("reason=").append(result.reason()).append('\n');
        }
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
