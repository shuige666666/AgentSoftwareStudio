package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保存已配对的开发上下文；续接只恢复当前源码版本的证据，不重执行历史工具。 */
final class WorkspaceDevelopmentContext {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE = ".software-studio/development-context.json";
    record Saved(int schemaVersion, String sourceHash, int modelTurns, int toolCalls,
                 List<ToolChatMessage> messages) { }

    /** 保存前预算整理由运行时完成；不完整工具轮次不能成为下一次的恢复材料。 */
    static void save(WorkspaceAgentSession session, Map<String, String> files) throws Exception {
        List<ToolChatMessage> history = session.messages().size() <= 2 ? List.of()
                : List.copyOf(session.messages().subList(2, session.messages().size()));
        if (!paired(history)) return;
        Path target = safeFile(session.projectRoot());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".context-", ".tmp");
        try {
            Files.writeString(temporary, MAPPER.writeValueAsString(new Saved(1, fingerprint(files),
                    session.modelTurns(), session.toolCalls(), history)), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** 新目标和累计预算仍来自平台状态；文件或预算不一致时不注入旧会话。 */
    static int restore(WorkspaceAgentSession session) throws Exception {
        Path file = safeFile(session.projectRoot());
        if (!Files.isRegularFile(file)) return 0;
        Saved saved = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
        if (saved.schemaVersion() != 1 || saved.modelTurns() != session.modelTurns()
                || saved.toolCalls() != session.toolCalls()
                || !saved.sourceHash().equals(fingerprint(session.baselineFiles()))
                || !paired(saved.messages())) return 0;
        Map<String, String> tools = new HashMap<>();
        List<ToolChatMessage> restored = new ArrayList<>();
        for (ToolChatMessage message : saved.messages()) {
            message.toolCalls().forEach(call -> tools.put(call.id(), call.name()));
            String tool = tools.get(message.toolCallId());
            if ("tool".equals(message.role()) && ("read_files".equals(tool) || "read_planning".equals(tool))) {
                ObjectNode result = (ObjectNode) MAPPER.readTree(message.content());
                for (var entry : result.path("files")) {
                    String current = session.baselineFiles().get(entry.path("path").asText());
                    // 历史曾读取但后来被修改的正文不能作为当前补丁依据；规划以当前目标为准。
                    if (current == null || !hash(current).equals(entry.path("sha256").asText())) {
                        ((ObjectNode) entry).remove("content");
                        ((ObjectNode) entry).put("contentOmitted", "source changed; read current file before editing");
                    }
                }
                restored.add(ToolChatMessage.tool(message.toolCallId(), MAPPER.writeValueAsString(result)));
            } else if (!"user".equals(message.role())) restored.add(message);
            // 历史目标、收尾提醒和自动验证由当前目标与新验证替代，避免恢复过期任务说明。
        }
        session.messages().addAll(restored);
        return restored.size();
    }

    /** 工具调用必须按轮完整响应；任何残留调用都不能带入新请求。 */
    private static boolean paired(List<ToolChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ToolChatMessage message : messages) {
            if ("assistant".equals(message.role())) {
                if (!pending.isEmpty()) return false;
                for (var call : message.toolCalls()) if (!pending.add(call.id())) return false;
            } else if ("tool".equals(message.role())) {
                if (!pending.remove(message.toolCallId())) return false;
            } else if (!"user".equals(message.role()) || !pending.isEmpty()) return false;
        }
        return pending.isEmpty();
    }

    /** 恢复文件仅由平台维护，拒绝链接避免元数据写入越界。 */
    private static Path safeFile(Path root) throws Exception {
        Path file = root.resolve(FILE);
        if (Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file))
            throw new IllegalStateException("Linked development context is not allowed");
        return file;
    }

    /** 对当前可编辑文件和内容排序摘要，检测续接间的外部修改。 */
    private static String fingerprint(Map<String, String> files) throws Exception {
        StringBuilder value = new StringBuilder();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                value.append(entry.getKey()).append('\0').append(entry.getValue()).append('\0'));
        return hash(value.toString());
    }

    /** UTF-8 摘要与读取工具使用相同编码，避免把旧正文当成当前内容。 */
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
