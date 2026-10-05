package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Generation.DevelopmentMilestoneObjective;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 按工作预算保留必要目标和真实证据，完整源码与工具审计仍以磁盘为准。 */
final class WorkspaceAgentContextCompactor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SIMPLE_MAVEN_CHECK = Pattern.compile(
            "(?:mvn|mvnw|\\./mvnw)(?:\\s+(?:-[^\\s;&|<>]+|compile|test|verify|package))+"
                    + "(?:\\s+2>&1)?(?:\\s*\\|\\s*tail\\s+(?:-\\d+|-n\\s+\\d+))?\\s*");
    static final String REJECTED_EDIT_PREFIX = "Historical rejected edit; complete call remains in audit. ";
    private final WorkspaceAgentLimitsConfig.ContextPolicy policy;

    WorkspaceAgentContextCompactor(WorkspaceAgentLimitsConfig.ContextPolicy policy) {
        this.policy = policy;
    }

    /** 只在全部工具响应配对完成后调用；正常压缩同时考虑窗口阈值与工作预算。 */
    Result compact(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                   int remainingModelTurns) {
        return compact(session, lastToolName, lastToolResult, remainingModelTurns, false, false);
    }

    /** 超时没有产生新工具调用，先移出冗余历史再在原全局预算内重试。 */
    Result compactForRetry(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns) {
        return compact(session, lastToolName, lastToolResult, remainingModelTurns, true, false);
    }

    /** 恢复操作会移出旧用户摘要，首请求前重建诊断，但不因续接清空可容纳的源码正文。 */
    Result compactForResume(WorkspaceAgentSession session, int remainingModelTurns) {
        return compact(session, "", "", remainingModelTurns, false, true);
    }

    /** 先压缩旧版本读取，再按完整轮次移出历史；不能用截断源码伪装成准确编辑上下文。 */
    private Result compact(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns, boolean retry, boolean resumed) {
        List<ToolChatMessage> messages = session.messages();
        int before = messages.size();
        long tokensBefore = estimatedTokens(messages);
        long trigger = Math.min(workingBudget(),
                (long) policy.getMaxContextTokens() * policy.getCompactAtPercent() / 100);
        if (messages.size() < 2 || !resumed && (messages.size() == 2 || !retry && tokensBefore < trigger)) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }

        List<Integer> assistantIndexes = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        for (int index = 2; index < messages.size(); index++) {
            ToolChatMessage message = messages.get(index);
            if ("assistant".equals(message.role())) assistantIndexes.add(index);
            message.toolCalls().forEach(call -> toolNames.put(call.id(), call.name()));
        }
        if (assistantIndexes.isEmpty() && !resumed) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }

        List<ToolChatMessage> pinned = new ArrayList<>();
        pinned.add(messages.getFirst());
        // 当前任务由持久化状态重建，首任务通过后不能重新注入旧目标。
        if (session.mode() == WorkspaceAgentMode.DEVELOPMENT
                && session.workflowData().milestoneState != null) {
            boolean includePreviousFailure = session.lastCheckpointHash().isBlank();
            pinned.add(ToolChatMessage.user(session.workflowData().milestoneState.active()
                    ? DevelopmentMilestoneObjective.render(session.workflowData(), includePreviousFailure)
                    : DevelopmentMilestoneObjective.renderSingle(session.workflowData(), includePreviousFailure)));
        } else {
            pinned.add(messages.get(1));
        }
        pinned.add(ToolChatMessage.user(summary(session, lastToolName, lastToolResult, remainingModelTurns)));
        String rejection = latestRejectedEdit(messages, toolNames);
        if (rejection != null) pinned.add(ToolChatMessage.user(rejection));

        // 预算决定历史边界，最近轮数只作为正文保留优先级，不能提前丢掉仍能容纳的源码。
        List<ToolChatMessage> recent = assistantIndexes.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(messages.subList(assistantIndexes.getFirst(), messages.size()));
        omitSupersededReads(recent, toolNames);
        summarizePlatformValidation(recent);
        summarizeMavenDiagnostics(recent, toolNames);
        // 重试时即使尚未达到预算，也移出已有的大段读取，避免再次原样发送超时请求。
        if (retry) omitAllReadBodies(recent, toolNames);
        while (!recent.isEmpty() && estimatedTokens(join(pinned, recent)) > workingBudget()) {
            if (omitOldReadMetadataRound(recent)) continue;
            // 验证失败已进入权威摘要，大报告整轮移出，避免先清空源码正文再丢掉同一报告。
            if (omitLargeCheckpointRound(recent)) continue;
            // 没有落盘的失败编辑已保留诊断，不能让旧补丁参数优先挤出刚读到的修复源码。
            if (omitRejectedEditRound(recent)) continue;
            // 先移出单个大读取的正文，保留同轮其他文件及真实工具配对。
            if (omitLargestReadBody(recent, toolNames)) continue;
            int next = -1;
            for (int index = 1; index < recent.size(); index++) {
                if ("assistant".equals(recent.get(index).role())) {
                    next = index;
                    break;
                }
            }
            if (next >= 0) {
                recent.subList(0, next).clear();
            } else {
                // 最近一轮读取超出预算时只留下文件元信息，要求后续按行重新读取。
                List<ToolChatMessage> reduced = new ArrayList<>(recent);
                omitAllReadBodies(reduced, toolNames);
                if (!reduced.equals(recent)) recent = reduced;
                else recent.clear(); // 大补丁等已执行轮次整体移出，不伪造部分工具参数。
            }
        }

        List<ToolChatMessage> compacted = join(pinned, recent);
        if (messages.equals(compacted)) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }
        messages.clear();
        messages.addAll(compacted);
        return new Result(true, before, messages.size(), tokensBefore, estimatedTokens(messages),
                workingBudget(), retry ? "timeout_retry" : resumed ? "resume_restore" : "context_budget");
    }

    /** 只归档可确认零写入的单工具编辑轮次；多工具和局部成功继续保留完整证据。 */
    private boolean omitRejectedEditRound(List<ToolChatMessage> messages) {
        for (int index = 0; index + 1 < messages.size(); index++) {
            var request = messages.get(index);
            var response = messages.get(index + 1);
            if (!"assistant".equals(request.role()) || request.toolCalls().size() != 1
                    || !"tool".equals(response.role())) continue;
            var call = request.toolCalls().getFirst();
            if (!call.id().equals(response.toolCallId())) continue;
            try {
                if (!zeroWriteRejection(call.name(), MAPPER.readTree(response.content()))) continue;
                messages.subList(index, index + 2).clear();
                return true;
            } catch (Exception ignored) {
                // 非标准响应无法证明没有写入，保持原始调用与结果。
            }
        }
        return false;
    }

    /** 把最近真实拒绝保留为有界元信息；后续实际写入后不再固定旧拒绝。 */
    private String latestRejectedEdit(List<ToolChatMessage> messages, Map<String, String> toolNames) {
        String latest = null;
        for (var message : messages) {
            if ("user".equals(message.role()) && message.content().startsWith(REJECTED_EDIT_PREFIX)) {
                latest = message.content();
            } else if ("tool".equals(message.role())) {
                try {
                    var result = MAPPER.readTree(message.content());
                    if (hasWrites(result)) latest = null;
                    String tool = toolNames.get(message.toolCallId());
                    if (!zeroWriteRejection(tool, result)) continue;
                    var metadata = MAPPER.createObjectNode().put("tool", tool);
                    for (String field : List.of("error", "failedPath", "message")) {
                        if (result.has(field)) metadata.set(field, result.get(field));
                    }
                    latest = REJECTED_EDIT_PREFIX + bounded(metadata.toString());
                } catch (Exception ignored) {
                    // 非标准结果不产生归档诊断，也不能抹掉已确认的失败。
                }
            }
        }
        return latest;
    }

    /** 以真实工具返回的写入列表判定变化，不把失败状态误当作没有副作用。 */
    private boolean hasWrites(com.fasterxml.jackson.databind.JsonNode result) {
        if (result == null) return false;
        var applied = result.path("applied");
        return result.path("changedFiles").isArray() && !result.path("changedFiles").isEmpty()
                || applied.isArray() && !applied.isEmpty() || applied.isNumber() && applied.asInt() > 0;
    }

    /** 只确认编辑预检拒绝或整组回退完成；执行异常的未知副作用不能靠猜测归档。 */
    private boolean zeroWriteRejection(String tool, com.fasterxml.jackson.databind.JsonNode result) {
        if (!("apply_patch".equals(tool) || "apply_edits".equals(tool)) || result == null
                || !result.isObject() || result.path("error").asText().isBlank() || hasWrites(result)) return false;
        String error = result.path("error").asText();
        if ("TOOL_ARGUMENTS_INVALID_JSON".equals(error)) return true;
        if ("apply_patch".equals(tool)) {
            return !result.path("ok").asBoolean(true) && result.path("applied").isArray();
        }
        return Set.of("STALE_FILE", "EDIT_NOT_UNIQUE", "EMPTY_FILE", "NO_EDITS", "DUPLICATE_FILE_EDIT").contains(error)
                || result.path("rollbackComplete").asBoolean() && result.path("changedFiles").isArray();
    }

    /** 按版本和行范围移出重复或失效正文，保留可用片段及真实读取元信息。 */
    private void omitSupersededReads(List<ToolChatMessage> recent, Map<String, String> toolNames) {
        Map<String, String> latestHashes = new HashMap<>();
        Map<String, List<ReadSpan>> covered = new HashMap<>();
        var writtenLater = new HashSet<String>();
        for (int index = recent.size() - 1; index >= 0; index--) {
            ToolChatMessage message = recent.get(index);
            if (!"tool".equals(message.role())) continue;
            try {
                ObjectNode result = (ObjectNode) MAPPER.readTree(message.content());
                // 后续 patch/Bash 已改动文件时，即使没有再次读取，旧正文也不能继续当作当前版本。
                result.path("changedFiles").forEach(path -> writtenLater.add(path.asText()));
                // patch 的真实响应使用 applied，Bash 使用 changedFiles，两种写入都使旧正文失效。
                result.path("applied").forEach(path -> { if (path.isTextual()) writtenLater.add(path.asText()); });
                if (!isRead(toolNames.get(message.toolCallId()))) continue;
                boolean changed = false;
                for (var entry : result.path("files")) {
                    String path = entry.path("path").asText();
                    if (!(entry instanceof ObjectNode file)) continue;
                    String hash = file.path("sha256").asText();
                    String latest = hash.isBlank() ? latestHashes.get(path) : latestHashes.putIfAbsent(path, hash);
                    if (!file.has("content")) continue;
                    ReadSpan span = readSpan(file);
                    List<ReadSpan> spans = covered.computeIfAbsent(path, ignored -> new ArrayList<>());
                    // 同一文件不同片段不是重复读取；只有新版本或已被完整覆盖的范围可以移出。
                    boolean superseded = writtenLater.contains(path) || latest != null && !latest.equals(hash)
                            || !hash.isBlank() && rangeCovered(span, spans);
                    if (superseded) {
                        omitBody(file);
                        changed = true;
                    } else if (!file.path("truncated").asBoolean() && !hash.isBlank()) {
                        spans.add(span);
                    }
                }
                if (changed) recent.set(index, ToolChatMessage.tool(message.toolCallId(), result.toString()));
            } catch (Exception ignored) {
                // 非标准或失败响应保持原文，不能把解析问题变成伪造的读取证据。
            }
        }
    }

    /** 完整读取按实际行数计算，片段沿用工具给出的精确范围。 */
    private ReadSpan readSpan(ObjectNode file) {
        int start = file.path("startLine").asInt(1);
        int end = file.has("endLine") ? file.path("endLine").asInt()
                : (int) file.path("content").asText().lines().count();
        return new ReadSpan(start, end);
    }

    /** 多个后续片段连续覆盖旧范围时才去重，不合并正文或伪造整文件。 */
    private boolean rangeCovered(ReadSpan span, List<ReadSpan> later) {
        long cursor = span.start();
        for (ReadSpan candidate : later.stream().sorted(java.util.Comparator.comparingInt(ReadSpan::start)).toList()) {
            if (candidate.start() > cursor) break;
            cursor = Math.max(cursor, (long) candidate.end() + 1);
            if (cursor > span.end()) return true;
        }
        return false;
    }

    /** 先回收旧正文，最后处理刚完成的读取，避免模型尚未使用新证据就将其移出。 */
    private boolean omitLargestReadBody(List<ToolChatMessage> messages, Map<String, String> toolNames) {
        List<Integer> turns = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if ("assistant".equals(messages.get(index).role())) turns.add(index);
        }
        int preferredStart = turns.get(Math.max(0, turns.size() - policy.getRetainedModelTurns()));
        int latestStart = turns.getLast();
        // 最近三轮内部也区分先后；新读取较大不代表它应当先于旧片段被丢弃。
        for (int beforeIndex : List.of(preferredStart, latestStart, messages.size())) {
            int selectedIndex = -1;
            int largest = 0;
            ObjectNode selectedResult = null;
            ObjectNode selectedFile = null;
            for (int index = 0; index < messages.size(); index++) {
                var message = messages.get(index);
                if (index >= beforeIndex || !"tool".equals(message.role())
                        || !isRead(toolNames.get(message.toolCallId()))) continue;
                try {
                    ObjectNode result = (ObjectNode) MAPPER.readTree(message.content());
                    for (var entry : result.path("files")) {
                        if (entry instanceof ObjectNode file && file.has("content")
                                && file.path("content").asText().length() > largest) {
                            largest = file.path("content").asText().length();
                            selectedIndex = index; selectedResult = result; selectedFile = file;
                        }
                    }
                } catch (Exception ignored) {
                    // 非标准工具响应保持原文，后续按完整轮次回收。
                }
            }
            if (selectedIndex >= 0) {
                omitBody(selectedFile);
                var message = messages.get(selectedIndex);
                messages.set(selectedIndex, ToolChatMessage.tool(message.toolCallId(), selectedResult.toString()));
                return true;
            }
        }
        return false;
    }

    private record ReadSpan(int start, int end) { }

    /** 较早的只读轮次已无正文时回收整轮，不能让历史哈希和重复目录清单挤出可用源码。 */
    private boolean omitOldReadMetadataRound(List<ToolChatMessage> messages) {
        List<Integer> turns = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) if ("assistant".equals(messages.get(index).role())) turns.add(index);
        for (int turn = 0; turn < turns.size() - policy.getRetainedModelTurns(); turn++) {
            int start = turns.get(turn), end = turns.get(turn + 1);
            var calls = messages.get(start).toolCalls();
            if (calls.isEmpty() || calls.stream().anyMatch(call -> !isRead(call.name()) && !"list_files".equals(call.name()))) continue;
            boolean useful = false;
            for (int index = start + 1; index < end; index++) {
                var message = messages.get(index);
                if (!"tool".equals(message.role())) { useful = true; break; }
                try {
                    var result = MAPPER.readTree(message.content());
                    if (!result.has("files")) { useful = true; break; }
                    for (var file : result.path("files")) if (file.has("content")) useful = true;
                } catch (Exception ignored) { useful = true; }
            }
            if (!useful) { messages.subList(start, end).clear(); return true; }
        }
        return false;
    }

    /** 平台主动验证已经登记到会话状态，历史消息仅保留验证版本和日志入口。 */
    private void summarizePlatformValidation(List<ToolChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if (!"user".equals(message.role()) || !message.content().startsWith("Platform validation of current candidate")) continue;
            int start = message.content().indexOf('{');
            if (start < 0) continue;
            String metadata = checkpointMetadata(message.content().substring(start));
            if (metadata != null) messages.set(index, ToolChatMessage.user(
                    "Historical platform validation; unresolved failures are in the authoritative summary. " + metadata));
        }
    }

    /** 超大 checkpoint 响应按完整调用轮次移出，不留下孤立请求或伪造通过结果。 */
    private boolean omitLargeCheckpointRound(List<ToolChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if (!"assistant".equals(message.role()) || message.toolCalls().size() != 1
                    || !"submit_checkpoint".equals(message.toolCalls().getFirst().name())) continue;
            int end = index + 1;
            boolean large = false;
            while (end < messages.size() && !"assistant".equals(messages.get(end).role())) {
                var response = messages.get(end);
                if ("tool".equals(response.role()) && response.content().length() > policy.getMaxLatestToolResultChars()
                        && checkpointMetadata(response.content()) != null) large = true;
                end++;
            }
            if (large) {
                messages.subList(index, end).clear();
                return true;
            }
        }
        return false;
    }

    /** 已有失败摘要和完整日志的Maven输出先去重，给当前准确源码保留预算。 */
    private void summarizeMavenDiagnostics(List<ToolChatMessage> messages, Map<String, String> toolNames) {
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if (!"tool".equals(message.role()) || !"bash".equals(toolNames.get(message.toolCallId()))) continue;
            String metadata = mavenDiagnosticMetadata(message.content());
            if (metadata != null) messages.set(index, ToolChatMessage.tool(message.toolCallId(), metadata));
        }
    }

    /** 只省略可追溯的纯诊断stdout；写入、混合命令或环境阻塞仍保留原响应。 */
    private String mavenDiagnosticMetadata(String content) {
        try {
            var parsed = MAPPER.readTree(content);
            if (!(parsed instanceof ObjectNode result) || !result.has("passed") || result.path("passed").asBoolean(true)
                    || !result.path("changedFiles").isArray() || !result.path("changedFiles").isEmpty()
                    || !result.has("timedOut") || result.path("timedOut").asBoolean()
                    || !result.has("infrastructureError") || !result.path("infrastructureError").asText("").isBlank()
                    || !result.path("exitCode").isInt() || Set.of(126, 127).contains(result.path("exitCode").asInt())
                    || result.path("fullLogPath").asText().isBlank() || result.path("output").asText().isBlank()
                    || result.path("output").asText().contains("unable to create native thread")) return null;
            if (result.path("failureHighlights").asText().isBlank() && result.path("testFailureEvidence").asText().isBlank()) return null;
            String command = result.path("command").asText().trim();
            if (!SIMPLE_MAVEN_CHECK.matcher(command).matches()
                    || java.util.Arrays.stream(command.split("\\s+"))
                    .noneMatch(Set.of("compile", "test", "verify", "package")::contains)) return null;
            result.remove("output");
            result.put("outputOmitted", true);
            result.put("historyNote", "Maven stdout omitted; failure diagnostics remain here and full output remains at fullLogPath and in audit.");
            return result.toString();
        } catch (Exception ignored) {
            return null; // 非标准工具结果不能证明诊断完整，保留真实响应。
        }
    }

    /** 大报告的检查细节沿用失败摘要，版本、状态和报告路径保留真实字段。 */
    private String checkpointMetadata(String content) {
        try {
            var result = MAPPER.readTree(content);
            if (!result.isObject() || !result.has("sourceHash") || !result.has("status") || !result.has("scope")) return null;
            // 环境阻塞不一定进入源码失败摘要，保留原诊断供模型定位。
            if (!Set.of("PASSED", "FAILED").contains(result.path("status").asText())) return null;
            ObjectNode metadata = MAPPER.createObjectNode();
            for (String field : List.of("scope", "status", "sourceHash", "snapshotPath", "reportPath", "allowCompletion",
                    "contractPassed", "acceptedMilestone", "acceptedMilestones", "nextMilestone", "allMilestonesAccepted")) {
                if (result.has(field)) metadata.set(field, result.get(field));
            }
            metadata.put("historyNote", "Recorded checkpoint metadata; full report remains in audit and checkpoint logs.");
            return metadata.toString();
        } catch (Exception ignored) {
            return null; // 非正式验证响应不能用元信息替换。
        }
    }

    /** 完整源码保存在磁盘；移出正文时不提供容易被当作整文件的首尾片段。 */
    private void omitAllReadBodies(List<ToolChatMessage> messages, Map<String, String> toolNames) {
        for (int index = 0; index < messages.size(); index++) {
            ToolChatMessage message = messages.get(index);
            if (isRead(toolNames.get(message.toolCallId()))) {
                messages.set(index, ToolChatMessage.tool(message.toolCallId(), withoutReadBodies(message.content())));
            }
        }
    }

    /** 保留标准文件响应的元信息，不生成可能误用于编辑的正文片段。 */
    private String withoutReadBodies(String content) {
        try {
            ObjectNode result = (ObjectNode) MAPPER.readTree(content);
            for (var entry : result.path("files")) {
                if (entry instanceof ObjectNode file && file.has("content")) omitBody(file);
            }
            return result.toString();
        } catch (Exception ignored) {
            return content;
        }
    }

    /** 明确标记读取已经成为历史，旧哈希不能直接代表当前磁盘版本。 */
    private void omitBody(ObjectNode file) {
        file.remove("content");
        file.put("contentOmitted", true);
        file.put("historyNote", "Historical read metadata, not current editable text. Re-read the file with read_files (startLine/endLine) or read_planning.");
    }

    /** 工作预算不能大于配置的窗口；必需目标过大时保留事实并由审计明确报告。 */
    private int workingBudget() {
        return Math.min(policy.getMaxWorkingContextTokens(), policy.getMaxContextTokens());
    }

    /** 只精简已知只读工具的正文，其他执行事实保持原结果。 */
    private boolean isRead(String name) {
        return "read_files".equals(name) || "read_planning".equals(name);
    }

    /** 将必要事实与完整工具轮次组合为下一次请求的历史。 */
    private List<ToolChatMessage> join(List<ToolChatMessage> pinned, List<ToolChatMessage> recent) {
        List<ToolChatMessage> result = new ArrayList<>(pinned);
        result.addAll(recent);
        return result;
    }

    /** 两字符一 token 的保守估算用于工作预算，不代替供应商计费用量。 */
    static long estimatedTokens(List<ToolChatMessage> messages) {
        long characters = 0;
        for (ToolChatMessage message : messages) {
            characters += message.content().length() + 32L;
            for (var call : message.toolCalls()) {
                characters += call.name().length() + call.arguments().length() + 32L;
            }
        }
        return (characters + 1) / 2;
    }

    /** 目标、验证失败、当前写入版本及剩余预算始终保留，不把最新读取正文再复制一份。 */
    private String summary(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns) {
        String closingReminder = session.mode() == WorkspaceAgentMode.DEVELOPMENT
                && remainingModelTurns >= 0 && remainingModelTurns <= 8
                ? "- remaining model turns: " + remainingModelTurns
                        + "; fix unresolved failures, rerun tests and submit the current checkpoint.\n"
                : "- remaining model turns: " + remainingModelTurns + "\n";
        String latestResult = "submit_checkpoint".equals(lastToolName) ? checkpointMetadata(lastToolResult) : null;
        if (latestResult == null && "bash".equals(lastToolName)) latestResult = mavenDiagnosticMetadata(lastToolResult);
        if (latestResult == null) latestResult = isRead(lastToolName) ? withoutReadBodies(lastToolResult) : lastToolResult;
        return """
                Platform context checkpoint (authoritative state; omitted tool details remain on disk):
                - mode: %s
                - current changed files: %s
                - workspace write version: %d
                - latest compile current: %s
                - latest tests current: %s
                - latest contract validation current: %s
                %s\
                - unresolved verification failure from write version %d:
                %s
                - latest tool: %s
                - latest real tool result:
                %s
                %s
                Re-read current files before editing; omitted bodies and historical hashes are not current editable text.
                Use read_planning for the complete public contract and read_files line ranges for exact source.
                """.formatted(session.mode(), session.changedFiles(), session.writeVersion(),
                session.compileCurrent(), session.testsCurrent(), session.contractCurrent(),
                checkpointOverview(session),
                session.unresolvedFailureVersion(), failureSummary(session),
                lastToolName == null ? "" : lastToolName,
                bounded(latestResult), closingReminder);
    }

    /** 同版本状态对照只保留受检事实；后续写入明确使旧快照成为历史证据。 */
    private String checkpointOverview(WorkspaceAgentSession session) {
        var check = session.checkpointObservation();
        if (check == null) return "";
        return "- last official snapshot check (evidence only; limited to recorded scope):\n"
                + "sourceHash=" + check.sourceHash() + " writeVersion=" + check.writeVersion()
                + " currentWriteVersion=" + (check.writeVersion() == session.writeVersion())
                + " scope=" + check.scope() + " overall=" + check.status()
                + " build=" + check.buildStatus() + " tests=" + check.testStatus()
                + " testsRun=" + (check.testSummaryPresent() ? check.testsRun() : "UNKNOWN")
                + " failures=" + (check.testSummaryPresent() ? check.failures() : "UNKNOWN")
                + " errors=" + (check.testSummaryPresent() ? check.errors() : "UNKNOWN")
                + " skipped=" + (check.testSummaryPresent() ? check.skipped() : "UNKNOWN") + "\n"
                + "independent behavior=" + check.behaviorStatus() + " passed=" + check.behaviorPassed()
                + " unresolved=" + check.behaviorUnresolved() + " report=" + check.reportPath() + "\n";
    }

    /** 摘要使用有界首尾证据，完整诊断仍在原工具日志中。 */
    private String bounded(String value) {
        return bounded(value, policy.getMaxLatestToolResultChars());
    }

    /** 自测、完整验收与独立业务诊断均有摘要预算，避免长日志遮住其他失败。 */
    private String failureSummary(WorkspaceAgentSession session) {
        var failures = session.verificationFailures();
        if (failures.isEmpty()) return "";
        int limit = policy.getMaxLatestToolResultChars();
        return failures.stream().map(failure -> bounded(failure, limit / failures.size()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 按本类诊断的预算保留首尾事实，不把省略内容表述为已完整交付。 */
    private String bounded(String value, int limit) {
        String safe = value == null ? "" : value;
        if (safe.length() <= limit) return safe;
        int head = limit / 4;
        int tail = Math.max(0, limit - head - 64);
        return safe.substring(0, head) + "\n... omitted; full evidence remains on disk ...\n"
                + safe.substring(safe.length() - tail);
    }

    record Result(boolean compacted, int messagesBefore, int messagesAfter, long estimatedTokensBefore,
                  long estimatedTokensAfter, int workingBudgetTokens, String reason) {
        boolean withinWorkingBudget() { return estimatedTokensAfter <= workingBudgetTokens; }
        private static Result unchanged(int size, long tokens, int budget) {
            return new Result(false, size, size, tokens, tokens, budget, "");
        }
    }
}
