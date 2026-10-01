package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** 回放真实失败参数和失败 XML，验证通用修复；不调用模型或修改原快照。 */
public final class PlatformFailureReplay {
    /** 用新副本回放首个新增文件格式拒绝，并直接读取保存的异常链证据。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: PlatformFailureReplay <audit.jsonl> <before-source> <failed-verification-work> <new-output-dir>");
        }
        var mapper = new ObjectMapper();
        var events = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(Path.of(args[0]))) events.add(mapper.readTree(line));
        JsonNode failure = events.stream().filter(event -> "TOOL_COMPLETED".equals(event.path("event").asText())
                && event.path("result").path("message").asText().startsWith("Added file lines must start with +"))
                .findFirst().orElseThrow();
        JsonNode request = events.stream().filter(event -> "TOOL_REQUESTED".equals(event.path("event").asText())
                && event.path("toolCallId").asText().equals(failure.path("toolCallId").asText()))
                .findFirst().orElseThrow();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Preserve prior replay evidence");
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Path work = Files.createDirectory(output.resolve("work"));
        Path before = Path.of(args[1]).toAbsolutePath().normalize();
        try (var files = Files.walk(before)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = work.resolve(before.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }

        // 原参数完全不改写，只由当前解析器处理无歧义的新增文件标记。
        var session = new WorkspaceAgentSession(work, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("Local fixed replay"), ignored -> {}, Map.of(), Set.of());
        var patch = new WorkspacePatchService().apply(request.path("arguments").path("patch").asText(), session);
        String feedback = SurefireFailureFeedback.read(Path.of(args[2]), Map.of());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("replay.json").toFile(), Map.of(
                "originalModelTurn", request.path("modelTurn").asInt(), "originalFailure", failure.path("result"),
                "samePatchArguments", true, "currentPatchResult", patch, "currentFailureFeedback", feedback));
        if (!patch.ok() || !feedback.contains("Observed cause from exception trace:")) {
            throw new IllegalStateException("Saved evidence did not demonstrate the intended repairs");
        }
        System.out.println("FIXED_REPLAY_PASSED=true");
    }
}
