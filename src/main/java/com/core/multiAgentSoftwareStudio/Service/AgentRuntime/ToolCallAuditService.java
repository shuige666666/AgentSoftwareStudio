package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 将工具会话目标、模型工具指令和真实执行结果按 JSONL 持久化，且审计失败不阻断生成主流程。
 */
@Service
public class ToolCallAuditService {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(api[-_]?key|authorization|password|secret|access[-_]?token|cookie).*");
    private static final Pattern TEXT_SECRET = Pattern.compile(
            "(?i)((?:api[-_]?key|authorization|password|secret|access[-_]?token)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;]+"
    );

    private final ObjectMapper objectMapper;
    private final WorkspaceAgentAuditConfig config;

    public ToolCallAuditService(ObjectMapper objectMapper, WorkspaceAgentAuditConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * 创建独立会话标识并只记录一次完整系统指令和阶段目标，避免每轮重复整段历史。
     */
    public ToolCallAuditSession startSession(
            WorkspaceAgentSession workspaceSession,
            String systemPrompt,
            String objective,
            int maxModelTurns,
            int maxToolCalls) {
        if (!config.isEnabled()) {
            return ToolCallAuditSession.disabled();
        }
        String projectKey = safeSegment(workspaceSession.projectRoot().getFileName().toString());
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        String sessionId = workspaceSession.mode().name().toLowerCase(Locale.ROOT)
                + "-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path logPath = Path.of(config.getRootDirectory()).toAbsolutePath().normalize()
                .resolve(projectKey).resolve("tool-calls.jsonl");
        ToolCallAuditSession auditSession = new ToolCallAuditSession(sessionId, logPath, true);

        ObjectNode event = baseEvent(auditSession, workspaceSession, "SESSION_STARTED");
        putLimited(event, "systemPrompt", redactText(systemPrompt), config.getMaxPromptChars());
        putLimited(event, "objective", redactText(objective), config.getMaxPromptChars());
        event.put("maxModelTurns", maxModelTurns);
        event.put("maxToolCalls", maxToolCalls);
        ArrayNode writePaths = event.putArray("allowedWritePaths");
        workspaceSession.allowedWritePaths().forEach(writePaths::add);
        append(auditSession, workspaceSession, event);
        workspaceSession.logger().accept("   Tool audit log: " + logPath + ".");
        return auditSession;
    }

    public void recordModelTurn(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            int turn) {
        ObjectNode event = baseEvent(auditSession, workspaceSession, "MODEL_TURN_STARTED");
        event.put("modelTurn", turn);
        append(auditSession, workspaceSession, event);
    }

    public void recordModelResponse(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            ToolModelResponse response) {
        ObjectNode event = baseEvent(auditSession, workspaceSession, "MODEL_RESPONSE");
        event.put("finishReason", response == null ? "" : safe(response.finishReason()));
        String content = response == null || response.message() == null ? "" : response.message().content();
        putLimited(event, "assistantContent", redactText(content), config.getMaxResultChars());
        event.put("toolCallCount", response == null || response.message() == null
                ? 0 : response.message().toolCalls().size());
        append(auditSession, workspaceSession, event);
    }

    public void recordToolRequested(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            ToolCall call) {
        ObjectNode event = baseEvent(auditSession, workspaceSession, "TOOL_REQUESTED");
        event.put("modelTurn", workspaceSession.modelTurns());
        event.put("toolCallIndex", workspaceSession.toolCalls());
        event.put("toolCallId", call == null ? "" : safe(call.id()));
        event.put("toolName", call == null ? "" : safe(call.name()));
        putJsonOrText(event, "arguments", call == null ? "{}" : call.arguments(), config.getMaxArgumentChars());
        append(auditSession, workspaceSession, event);
    }

    public void recordToolCompleted(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            ToolCall call,
            String result,
            long durationMillis) {
        ObjectNode event = baseEvent(auditSession, workspaceSession, "TOOL_COMPLETED");
        event.put("modelTurn", workspaceSession.modelTurns());
        event.put("toolCallIndex", workspaceSession.toolCalls());
        event.put("toolCallId", call == null ? "" : safe(call.id()));
        event.put("toolName", call == null ? "" : safe(call.name()));
        event.put("durationMillis", Math.max(0, durationMillis));
        putJsonOrText(event, "result", result, config.getMaxResultChars());
        append(auditSession, workspaceSession, event);
    }

    /**
     * 会话所有退出路径统一写入停止原因和真实变更文件，便于事后重建时间线。
     */
    public void finishSession(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            WorkspaceAgentStopReason stopReason,
            List<String> changedFiles,
            String message) {
        ObjectNode event = baseEvent(auditSession, workspaceSession, "SESSION_FINISHED");
        event.put("completed", stopReason == WorkspaceAgentStopReason.COMPLETED);
        event.put("stopReason", stopReason == null ? "" : stopReason.name());
        event.put("modelTurns", workspaceSession.modelTurns());
        event.put("toolCalls", workspaceSession.toolCalls());
        putLimited(event, "message", redactText(message), config.getMaxResultChars());
        ArrayNode changed = event.putArray("changedFiles");
        (changedFiles == null ? List.<String>of() : changedFiles).forEach(changed::add);
        append(auditSession, workspaceSession, event);
    }

    private ObjectNode baseEvent(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            String type) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("timestamp", OffsetDateTime.now().toString());
        event.put("event", type);
        event.put("sessionId", auditSession == null ? "" : auditSession.sessionId());
        event.put("mode", workspaceSession.mode().name());
        event.put("projectPath", workspaceSession.projectRoot().toString());
        return event;
    }

    private void putJsonOrText(ObjectNode target, String field, String raw, int limit) {
        String redacted = redactText(safe(raw));
        LimitedText limited = limit(redacted, limit);
        try {
            JsonNode parsed = objectMapper.readTree(limited.text());
            redactNode(parsed);
            target.set(field, parsed);
        } catch (Exception ignored) {
            target.put(field, limited.text());
        }
        target.put(field + "Truncated", limited.truncated());
        target.put(field + "OriginalChars", redacted.length());
    }

    private void putLimited(ObjectNode target, String field, String value, int limit) {
        LimitedText limited = limit(safe(value), limit);
        target.put(field, limited.text());
        target.put(field + "Truncated", limited.truncated());
        target.put(field + "OriginalChars", safe(value).length());
    }

    private LimitedText limit(String value, int maxChars) {
        String safeValue = safe(value);
        if (safeValue.length() <= maxChars) {
            return new LimitedText(safeValue, false);
        }
        int head = Math.max(1, maxChars / 4);
        int tail = Math.max(1, maxChars - head - 64);
        return new LimitedText(safeValue.substring(0, head)
                + "\n... audit content truncated; tail follows ...\n"
                + safeValue.substring(safeValue.length() - tail), true);
    }

    private void redactNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (SENSITIVE_KEY.matcher(entry.getKey()).matches()) {
                    ((ObjectNode) node).put(entry.getKey(), "[REDACTED]");
                } else {
                    redactNode(entry.getValue());
                }
            });
        } else if (node.isArray()) {
            node.forEach(this::redactNode);
        }
    }

    private String redactText(String value) {
        return TEXT_SECRET.matcher(safe(value)).replaceAll("$1[REDACTED]");
    }

    /**
     * 单次 append 打开并关闭文件，确保进程在后续调用异常时已完成当前检查点落盘。
     */
    private synchronized void append(
            ToolCallAuditSession auditSession,
            WorkspaceAgentSession workspaceSession,
            ObjectNode event) {
        if (auditSession == null || !auditSession.enabled() || auditSession.logPath() == null) {
            return;
        }
        try {
            Files.createDirectories(auditSession.logPath().getParent());
            Files.writeString(
                    auditSession.logPath(),
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException auditFailure) {
            workspaceSession.logger().accept("   Tool audit write failed but the Agent will continue: "
                    + auditFailure.getMessage());
        }
    }

    private String safeSegment(String value) {
        String safeValue = safe(value).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return safeValue.isBlank() ? "unknown-project" : safeValue;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record LimitedText(String text, boolean truncated) {
    }
}
