package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningConflictDecision;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolDefinition;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PlanningNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * 注册编码阶段真正需要的工作区、编译、测试和契约工具。
 */
@Component
public class WorkspaceToolRegistry {
    private static final int MAX_FILE_CHARS = 40_000;
    private static final int MAX_SEARCH_RESULTS = 80;
    private static final Pattern GREEN_TEST_SUMMARY = Pattern.compile(
            "Tests run:\\s*[1-9]\\d*,\\s*Failures:\\s*0,\\s*Errors:\\s*0");
    private static final Pattern FULL_MAVEN_TEST_COMMAND = Pattern.compile(
            "(?i)(?:^|[\\s/\\\\])mvnw?(?:\\.cmd)?\\b.*\\b(?:test|verify)\\b");

    private final ObjectMapper objectMapper;
    private final DockerSandboxService sandboxService;
    private final WorkspaceService workspaceService;
    private final ProjectProfileService projectProfileService;
    private final WorkspacePatchService patchService;
    private final WorkspaceCheckpointService checkpointService;
    private final PlanningNodeService planningNodeService;
    private final DevelopmentMilestoneService milestoneService;
    private final Map<String, WorkspaceTool> tools = new LinkedHashMap<>();

    public WorkspaceToolRegistry(
            ObjectMapper objectMapper,
            DockerSandboxService sandboxService,
            WorkspaceService workspaceService,
            ProjectProfileService projectProfileService,
            WorkspacePatchService patchService,
            WorkspaceCheckpointService checkpointService,
            PlanningNodeService planningNodeService,
            DevelopmentMilestoneService milestoneService) {
        this.objectMapper = objectMapper;
        this.sandboxService = sandboxService;
        this.workspaceService = workspaceService;
        this.projectProfileService = projectProfileService;
        this.patchService = patchService;
        this.checkpointService = checkpointService;
        this.planningNodeService = planningNodeService;
        this.milestoneService = milestoneService;
        registerCoreTools();
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(WorkspaceTool::definition).toList();
    }

    /** 连续开发会话只暴露当前协议，避免模型转回旧的阶段完成路径。 */
    public List<ToolDefinition> definitions(WorkspaceAgentMode mode) {
        if (mode != WorkspaceAgentMode.DEVELOPMENT) return definitions();
        return List.of("list_files", "search_code", "read_files", "get_current_diff",
                        "bash", "apply_patch", "submit_checkpoint", "restore_checkpoint", "report_blocker")
                .stream().map(name -> tools.get(name).definition()).toList();
    }

    /**
     * 校验工具名和 JSON 参数后执行；异常转换成工具结果交还模型继续判断。
     */
    public String execute(String name, String rawArguments, WorkspaceAgentSession session) {
        if (session.mode() == WorkspaceAgentMode.DEVELOPMENT
                && definitions(WorkspaceAgentMode.DEVELOPMENT).stream().noneMatch(def -> def.name().equals(name))) {
            return jsonError("TOOL_NOT_ALLOWED", "Tool is outside the development protocol: " + name);
        }
        WorkspaceTool tool = tools.get(name);
        if (tool == null) {
            return jsonError("UNKNOWN_TOOL", "Tool is not allowed: " + name);
        }
        try {
            JsonNode arguments = objectMapper.readTree(
                    rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
            return tool.execute(arguments, session);
        } catch (JsonProcessingException e) {
            // 模型一次提交过长参数时可能截断 JSON；提示缩小补丁而不是重试同一大请求。
            return jsonError("TOOL_ARGUMENTS_INVALID_JSON",
                    "Tool arguments are incomplete or invalid JSON. Resend valid JSON; split large patches into smaller related-file groups.");
        } catch (Exception e) {
            return jsonError("TOOL_EXECUTION_FAILED", e.getMessage());
        }
    }

    private void registerCoreTools() {
        register("list_files", "列出候选项目中的源码、测试和配置文件。", objectSchema(), this::listFiles);
        register("search_code", "在候选项目中搜索文本或符号，返回文件和行号。",
                objectSchema("query", "string", "true", "glob", "string", "false"), this::searchCode);
        register("read_files", "读取一个或多个项目文件并返回内容和 SHA-256。",
                arrayPropertySchema("paths", true), this::readFiles);
        register("apply_edits", "原子应用一组精确文本替换；每个文件限一项，并携带 read_files 返回的 expectedHash。",
                applyEditsSchema(), this::applyEdits);
        register("apply_patch", "首选源码写入工具：一次补丁可新增、修改或删除多个相关文件。若上下文不匹配，先读取当前文件再重做补丁；返回实际落盘文件。",
                objectSchema("patch", "string", "true"), this::applyPatch);
        register("bash", "在断网非 root 持久容器中运行编译、测试和诊断命令；常规源码修改使用 apply_patch。cwd 可用 . 或 /workspace 表示项目根目录，超时毫秒数可选。",
                objectSchema("command", "string", "true", "cwd", "string", "false",
                        "timeoutMillis", "integer", "false"), this::bash);
        register("submit_checkpoint", "冻结当前源码，在独立副本执行完整编译和全量已有测试；final 另做契约验收。",
                objectSchema("summary", "string", "true", "scope", "string", "true",
                        "milestoneId", "string", "false"), this::submitCheckpoint);
        register("restore_checkpoint", "恢复最近接受的业务里程碑源码版本，保留当前候选备份。",
                objectSchema(), this::restoreCheckpoint);
        register("get_current_diff", "查看本会话相对开始快照发生变化的文件摘要。",
                objectSchema(), this::currentDiff);
        register("compile_main", "在隔离 Docker 沙箱中编译当前生产代码。",
                objectSchema(), this::compileMain);
        register("run_tests", "在隔离 Docker 沙箱中运行指定测试类；selectors 为空时运行全量测试。",
                arrayPropertySchema("selectors", false), this::runTests);
        register("validate_contract", "对磁盘上的当前代码执行确定性项目契约检查。",
                objectSchema(), this::validateContract);
        register("complete_stage", "声明当前阶段完成；平台会检查修改后的代码是否已经真实验证。",
                objectSchema("summary", "string", "true"), this::completeStage);
        register("report_blocker", "报告无法安全处理的阻塞原因，不伪造代码修改。",
                objectSchema("reason", "string", "true", "kind", "string", "false"), this::reportBlocker);
    }

    private void register(
            String name,
            String description,
            JsonNode schema,
            BiFunction<JsonNode, WorkspaceAgentSession, String> executor) {
        tools.put(name, new WorkspaceTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, description, schema);
            }

            @Override
            public String execute(JsonNode arguments, WorkspaceAgentSession session) {
                return executor.apply(arguments, session);
            }
        });
    }

    private String listFiles(JsonNode ignored, WorkspaceAgentSession session) {
        ArrayNode result = objectMapper.createArrayNode();
        try (Stream<Path> stream = Files.walk(session.projectRoot())) {
            stream.filter(Files::isRegularFile)
                    .map(path -> normalize(session.projectRoot().relativize(path)))
                    .filter(this::isReadableProjectFile)
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list workspace files", e);
        }
        return objectMapper.createObjectNode().set("files", result).toString();
    }

    private String searchCode(JsonNode arguments, WorkspaceAgentSession session) {
        String query = arguments.path("query").asText("");
        String glob = arguments.path("glob").asText("");
        if (query.isBlank()) {
            return jsonError("INVALID_ARGUMENT", "query is required");
        }
        ArrayNode matches = objectMapper.createArrayNode();
        try (Stream<Path> stream = Files.walk(session.projectRoot())) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> isReadableProjectFile(normalize(session.projectRoot().relativize(path))))
                    .filter(path -> glob.isBlank() || simpleGlobMatches(normalize(session.projectRoot().relativize(path)), glob))
                    .sorted()
                    .toList();
            outer:
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    if (lines.get(index).contains(query)) {
                        ObjectNode match = matches.addObject();
                        match.put("path", normalize(session.projectRoot().relativize(file)));
                        match.put("line", index + 1);
                        match.put("text", limit(lines.get(index), 500));
                        if (matches.size() >= MAX_SEARCH_RESULTS) {
                            break outer;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to search workspace", e);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("matches", matches);
        result.put("truncated", matches.size() >= MAX_SEARCH_RESULTS);
        return result.toString();
    }

    private String readFiles(JsonNode arguments, WorkspaceAgentSession session) {
        ArrayNode files = objectMapper.createArrayNode();
        for (JsonNode item : arguments.path("paths")) {
            String relative = normalizeRelative(item.asText(), session);
            if (!isReadableProjectFile(relative)) {
                files.addObject().put("path", relative).put("error", "FILE_NOT_READABLE");
                continue;
            }
            Path path = resolveInsideRoot(session, relative);
            ObjectNode file = files.addObject();
            file.put("path", relative);
            if (!Files.isRegularFile(path)) {
                file.put("error", "FILE_NOT_FOUND");
                file.put("sha256", sha256(""));
                continue;
            }
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                file.put("sha256", sha256(content));
                file.put("content", limit(content, MAX_FILE_CHARS));
                file.put("truncated", content.length() > MAX_FILE_CHARS);
            } catch (IOException e) {
                file.put("error", e.getMessage());
            }
        }
        return objectMapper.createObjectNode().set("files", files).toString();
    }

    /**
     * 先在内存中验证所有编辑，再逐个原子替换，避免跨文件修改只落盘一半。
     */
    private String applyEdits(JsonNode arguments, WorkspaceAgentSession session) {
        List<PreparedEdit> prepared = new ArrayList<>();
        java.util.LinkedHashSet<String> editedPaths = new java.util.LinkedHashSet<>();
        for (JsonNode edit : arguments.path("edits")) {
            String relative = normalizeRelative(edit.path("path").asText(), session);
            if (!editedPaths.add(relative)) {
                return jsonError("DUPLICATE_FILE_EDIT", "Combine replacements into one edit per file: " + relative);
            }
            assertWritable(relative, session);
            Path path = resolveInsideRoot(session, relative);
            String expectedHash = edit.path("expectedHash").asText("");
            String oldText = edit.path("oldText").asText("");
            String newText = edit.path("newText").asText("");
            try {
                String current = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
                if (!expectedHash.equals(sha256(current))) {
                    return jsonError("STALE_FILE", "Hash changed; read the file again: " + relative);
                }
                String updated;
                if (oldText.isEmpty() && current.isEmpty()) {
                    updated = newText;
                } else {
                    int first = current.indexOf(oldText);
                    if (first < 0 || first != current.lastIndexOf(oldText)) {
                        return jsonError("EDIT_NOT_UNIQUE", "oldText must occur exactly once: " + relative);
                    }
                    updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
                }
                if (updated.isBlank()) {
                    return jsonError("EMPTY_FILE", "Refusing to write a blank file: " + relative);
                }
                prepared.add(new PreparedEdit(relative, path, updated));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to prepare edit for " + relative, e);
            }
        }
        if (prepared.isEmpty()) {
            return jsonError("NO_EDITS", "At least one edit is required");
        }
        try {
            for (PreparedEdit edit : prepared) {
                atomicWrite(edit.path(), edit.content());
                session.recordWrite(edit.relativePath());
            }
        } catch (IOException e) {
            restoreBaseline(session);
            throw new IllegalStateException("Atomic edit set failed and was rolled back", e);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("applied", prepared.size());
        ArrayNode changed = result.putArray("changedFiles");
        prepared.forEach(edit -> changed.add(edit.relativePath()));
        return result.toString();
    }

    /** 结束会话时清理持续容器，避免后台进程跨越工作流边界。 */
    public void closeSession(WorkspaceAgentSession session) {
        session.closeShell(sandboxService);
    }

    /** 多文件补丁先校验再落盘；发生局部 IO 错误时只报告实际已写入项。 */
    private String applyPatch(JsonNode arguments, WorkspaceAgentSession session) {
        if (session.mode() != WorkspaceAgentMode.DEVELOPMENT) {
            return jsonError("TOOL_NOT_ALLOWED", "apply_patch belongs to the development session");
        }
        return objectMapper.valueToTree(patchService.apply(arguments.path("patch").asText(""), session)).toString();
    }

    /** Shell 的所有写入都以命令前后的磁盘差异更新工作区版本。 */
    private String bash(JsonNode arguments, WorkspaceAgentSession session) {
        if (session.mode() != WorkspaceAgentMode.DEVELOPMENT) {
            return jsonError("TOOL_NOT_ALLOWED", "bash belongs to the development session");
        }
        Map<String, String> before = snapshot(session.projectRoot());
        if (session.shell() == null) session.setShell(sandboxService.startWorkspaceShell(session.projectRoot()));
        String command = arguments.path("command").asText("");
        String cwd = arguments.path("cwd").asText("");
        long timeout = arguments.path("timeoutMillis").asLong(120_000);
        DockerSandboxService.WorkspaceCommandResult run = sandboxService.runWorkspaceCommand(
                session.shell(), command, cwd, timeout);
        if (session.shell().closed()) session.setShell(null);
        Map<String, String> after = snapshot(session.projectRoot());
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<String> changed = paths.stream()
                .filter(path -> !java.util.Objects.equals(before.get(path), after.get(path)))
                .sorted().toList();
        changed.forEach(session::recordWrite);
        ObjectNode result = objectMapper.createObjectNode();
        SandboxExecutionResult execution = run.execution();
        recordBashVerification(session, command, execution);
        result.put("passed", execution.completedSuccessfully());
        result.put("command", command);
        result.put("cwd", cwd.isBlank() ? "." : cwd);
        if (execution.exitCode() == null) result.putNull("exitCode");
        else result.put("exitCode", execution.exitCode());
        result.put("durationMillis", execution.durationMillis());
        result.put("timedOut", execution.timedOut());
        result.put("infrastructureError", execution.infrastructureError());
        result.put("output", limitHeadAndTail(execution.output(), 16_000));
        if (!execution.completedSuccessfully()) {
            // 先呈现真正的编译或测试错误；完整输出和原始日志仍保留供核对。
            result.put("failureHighlights", failureHighlights(execution.output()));
        }
        result.put("fullLogPath", run.logPath());
        ArrayNode files = result.putArray("changedFiles");
        changed.forEach(files::add);
        return result.toString();
    }

    /** 失败反馈跨压缩保留，直到后续真实测试通过；普通成功命令不能清除测试错误。 */
    static void recordBashVerification(WorkspaceAgentSession session, String command,
                                       SandboxExecutionResult execution) {
        if (!execution.completedSuccessfully() && command.contains("mvn")) {
            // 保留最近的 Maven 失败，避免后续读取与补丁把错误反馈挤出压缩上下文。
            session.recordVerificationFailure("command=" + command + "\n" + failureHighlights(execution.output()));
        } else if (execution.completedSuccessfully()
                && FULL_MAVEN_TEST_COMMAND.matcher(command).find()
                && !command.contains("-Dtest=") && !command.contains("-DskipTests")
                && GREEN_TEST_SUMMARY.matcher(execution.output()).find()) {
            // 只有后来跑绿全套测试才清除旧失败；单测通过不代表原错误已修复。
            session.clearVerificationFailure();
        }
    }

    /** 从有噪声的构建输出中提取少量关键失败行，不改变命令的退出状态。 */
    static String failureHighlights(String output) {
        if (output == null || output.isBlank()) return "No command output; inspect exitCode and fullLogPath";
        StringBuilder highlights = new StringBuilder();
        int lines = 0;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            boolean error = line.startsWith("[ERROR]");
            boolean detail = line.startsWith("symbol:") || line.startsWith("location:")
                    || line.startsWith("Caused by:") && !line.contains("jansi")
                    || line.contains("Tests run:") && (line.contains("Failures:") || line.contains("Errors:"))
                    && !GREEN_TEST_SUMMARY.matcher(line).find();
            if (!error && !detail) continue;
            while (line.startsWith("[ERROR]")) line = line.substring(7).strip();
            if (line.isBlank() || line.contains("jansi-") || line.startsWith("-> [Help")
                    || line.contains("[Help ") || line.startsWith("To see ")
                    || line.startsWith("For more ") || line.startsWith("Please refer ")
                    || line.startsWith("Re-run ") || line.startsWith("http")
                    || line.startsWith("Failed to execute goal ") || !seen.add(line)) continue;
            if (highlights.length() + line.length() > 2_000 || lines >= 10) break;
            if (highlights.length() > 0) highlights.append('\n');
            highlights.append(line);
            lines++;
        }
        if (!highlights.isEmpty()) return highlights.toString();
        String stripped = output.strip();
        return stripped.length() <= 800 ? stripped : stripped.substring(0, 800) + "...";
    }

    /** 正式验证在冻结版本上执行，只有 final 全部通过才结束当前开发会话。 */
    private String submitCheckpoint(JsonNode arguments, WorkspaceAgentSession session) {
        if (session.mode() != WorkspaceAgentMode.DEVELOPMENT) {
            return jsonError("TOOL_NOT_ALLOWED", "submit_checkpoint belongs to the development session");
        }
        String scope = arguments.path("scope").asText("progress");
        String summary = arguments.path("summary").asText("");
        var state = session.workflowData().milestoneState;
        if (state != null && state.active()) {
            if ("final".equals(scope) && !state.allAccepted()) {
                return jsonError("MILESTONES_PENDING", "Accept every business milestone before final checkpoint");
            }
            if ("progress".equals(scope)) {
                String id = arguments.path("milestoneId").asText("");
                if (state.current() == null || !state.current().id().equals(id)) {
                    return jsonError("MILESTONE_OUT_OF_ORDER", "Current milestone is "
                            + (state.current() == null ? "none" : state.current().id()));
                }
                WorkspaceCheckpointService.CheckpointResult result = checkpointService.verify(
                        session, scope, summary,
                        milestoneService.contractThroughCurrent(session.workflowData()));
                recordCheckpointFeedback(session, result);
                ObjectNode feedback = objectMapper.valueToTree(result);
                if ("PASSED".equals(result.status())) {
                    milestoneService.accept(session.workflowData(), id, result);
                    var next = session.workflowData().milestoneState.current();
                    feedback.put("acceptedMilestone", id);
                    feedback.put("nextMilestone", next == null ? "" : next.id());
                    feedback.put("allMilestonesAccepted", next == null);
                    // 同一会话切换任务时交付下一项的具体边界，避免模型沿用上一项契约。
                    if (next != null) {
                        feedback.set("nextAcceptanceCriteria", objectMapper.valueToTree(next.acceptanceCriteria()));
                        session.workflowData().sliceDeliveryPlan.slices().stream()
                                .filter(slice -> slice.id().equals(next.id()))
                                .findFirst()
                                .ifPresent(slice -> feedback.set("nextMilestoneContract",
                                        objectMapper.valueToTree(slice.contract())));
                    }
                }
                return feedback.toString();
            }
        }
        WorkspaceCheckpointService.CheckpointResult result = checkpointService.verify(session, scope, summary);
        recordCheckpointFeedback(session, result);
        if (result.allowCompletion()) {
            session.complete(arguments.path("summary").asText("Final checkpoint passed"));
        }
        return objectMapper.valueToTree(result).toString();
    }

    /** 正式快照的失败或通过同样更新压缩摘要，避免只记住最后一次文件读取。 */
    private void recordCheckpointFeedback(WorkspaceAgentSession session,
                                          WorkspaceCheckpointService.CheckpointResult result) {
        if ("PASSED".equals(result.status())) {
            session.clearVerificationFailure();
        } else if (!result.failureOutput().isBlank()) {
            session.recordVerificationFailure("checkpoint " + result.scope() + ": " + result.failureOutput());
        }
    }

    /** 回滚只针对最近通过的能力快照，当前候选先备份以便人工检查。 */
    private String restoreCheckpoint(JsonNode ignored, WorkspaceAgentSession session) {
        if (session.mode() != WorkspaceAgentMode.DEVELOPMENT) {
            return jsonError("TOOL_NOT_ALLOWED", "restore_checkpoint belongs to the development session");
        }
        var state = session.workflowData().milestoneState;
        if (state == null || !state.active() || state.lastAccepted() == null) {
            return jsonError("NO_ACCEPTED_MILESTONE", "No accepted milestone is available for restore");
        }
        return objectMapper.valueToTree(checkpointService.restoreLatestAccepted(session)).toString();
    }

    private String currentDiff(JsonNode ignored, WorkspaceAgentSession session) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode changed = result.putArray("changedFiles");
        for (String path : session.changedFiles()) {
            Path file = resolveInsideRoot(session, path);
            String before = session.baselineFiles().get(path);
            try {
                String after = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
                ObjectNode entry = changed.addObject();
                entry.put("path", path);
                entry.put("beforeSha256", sha256(before == null ? "" : before));
                entry.put("afterSha256", sha256(after == null ? "" : after));
                entry.put("beforeLines", lineCount(before));
                entry.put("afterLines", lineCount(after));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to inspect diff for " + path, e);
            }
        }
        return result.toString();
    }

    private String compileMain(JsonNode ignored, WorkspaceAgentSession session) {
        SandboxExecutionResult execution = sandboxService.runCompileInSandboxWithResult(
                session.projectRoot(), projectType(session));
        if (execution.completedSuccessfully()) {
            session.recordCompilePassed();
        } else {
            session.recordVerificationFailure("compile_main: " + failureHighlights(execution.output()));
        }
        return executionJson(execution);
    }

    private String runTests(JsonNode arguments, WorkspaceAgentSession session) {
        List<String> selectors = new ArrayList<>();
        for (JsonNode selector : arguments.path("selectors")) {
            if (!selector.asText().isBlank()) {
                selectors.add(selector.asText());
            }
        }
        SandboxExecutionResult execution = sandboxService.runTestsInSandboxWithResult(
                session.projectRoot(), projectType(session), selectors);
        recordTestToolVerification(session, selectors, execution);
        return executionJson(execution);
    }

    /** 定向测试只用于定位问题；只有全套测试通过才更新会话的完成门禁。 */
    static void recordTestToolVerification(WorkspaceAgentSession session, List<String> selectors,
                                           SandboxExecutionResult execution) {
        if (execution.completedSuccessfully() && (selectors == null || selectors.isEmpty())) {
            session.recordCompilePassed();
            session.recordTestsPassed();
        } else if (!execution.completedSuccessfully()) {
            session.recordVerificationFailure("run_tests" + (selectors == null || selectors.isEmpty()
                    ? "" : " selectors=" + selectors) + ": " + failureHighlights(execution.output()));
        }
    }

    private String validateContract(JsonNode ignored, WorkspaceAgentSession session) {
        List<SourceCode> codes = workspaceService.loadProjectFromDisk(session.projectRoot(), message -> { });
        ProjectQualityPolicyResult result = projectProfileService.evaluate(
                session.workflowData().projectProfile,
                codes,
                session.workflowData().contract,
                List.of());
        if (result.passed()) {
            session.recordContractPassed();
        }
        ObjectNode json = objectMapper.createObjectNode();
        json.put("passed", result.passed());
        ArrayNode blockers = json.putArray("blockers");
        result.blockingFindings().forEach(finding -> {
            ObjectNode node = blockers.addObject();
            node.put("gate", finding.gate());
            node.put("evidence", finding.evidence());
        });
        return json.toString();
    }

    private String completeStage(JsonNode arguments, WorkspaceAgentSession session) {
        boolean requiresTests = session.mode() == WorkspaceAgentMode.TEST
                || session.mode() == WorkspaceAgentMode.REPAIR_TEST;
        boolean requiresContract = session.mode() == WorkspaceAgentMode.REPAIR_CONTRACT;
        boolean requiresCompile = session.mode() != WorkspaceAgentMode.FRONTEND_INTEGRATION
                || session.writeVersion() > 0;
        if (requiresCompile && !session.compileCurrent()) {
            return jsonError("VERIFICATION_REQUIRED", "Run compile_main after the latest edit before completion");
        }
        if (requiresTests && !session.testsCurrent()) {
            return jsonError("TESTS_REQUIRED", "Run the generated or modified tests after the latest edit");
        }
        if (requiresContract && !session.contractCurrent()) {
            return jsonError("CONTRACT_REQUIRED", "Run validate_contract after the latest edit");
        }
        session.complete(arguments.path("summary").asText("Completed with real tool verification"));
        return "{\"accepted\":true}";
    }

    private String reportBlocker(JsonNode arguments, WorkspaceAgentSession session) {
        String reason = arguments.path("reason").asText("unspecified");
        String kind = arguments.path("kind").asText("task_blocked");
        if (session.mode() == WorkspaceAgentMode.DEVELOPMENT && "planning_conflict".equals(kind)) {
            PlanningConflictDecision decision = planningNodeService.reviewConflict(
                    session.workflowData(), reason, session.logger());
            ObjectNode response = objectMapper.createObjectNode();
            response.put("kind", "planning_conflict");
            response.put("revised", decision.revised());
            response.put("reason", decision.reason());
            response.set("effectivePlan", objectMapper.valueToTree(session.workflowData().planningDesign));
            return response.toString();
        }
        if (!"task_blocked".equals(kind)) {
            return jsonError("UNKNOWN_BLOCKER_KIND", "Use planning_conflict or task_blocked");
        }
        session.block(reason);
        session.logger().accept("   Tool Agent blocker: " + reason);
        return "{\"recorded\":true,\"instruction\":\"Stop making speculative changes and return.\"}";
    }

    private void assertWritable(String path, WorkspaceAgentSession session) {
        String normalized = normalize(Path.of(path));
        boolean pathAllowed = session.allowedWritePaths().isEmpty()
                || session.allowedWritePaths().stream().map(this::normalizeText).anyMatch(normalized::equals);
        boolean scopeAllowed = switch (session.mode()) {
            case DEVELOPMENT -> normalized.startsWith("src/") || normalized.equals("pom.xml")
                    || normalized.equals("README.md") || normalized.equals(".gitignore");
            case TEST -> normalized.startsWith("src/test/");
            case IMPLEMENT -> pathAllowed && (normalized.startsWith("src/main/") || normalized.equals("pom.xml"));
            case FRONTEND_INTEGRATION -> normalized.startsWith("src/main/") && pathAllowed;
            case REPAIR_IMPLEMENTATION, REPAIR_TEST, REPAIR_CONTRACT ->
                    normalized.startsWith("src/") || normalized.equals("pom.xml");
        };
        if (!scopeAllowed) {
            throw new IllegalArgumentException("Write is outside the current Agent scope: " + path);
        }
    }

    private Path resolveInsideRoot(WorkspaceAgentSession session, String relative) {
        Path resolved = session.projectRoot().resolve(relative).normalize();
        if (!resolved.startsWith(session.projectRoot()) || relative.contains(".git")) {
            throw new IllegalArgumentException("Path escapes the candidate workspace: " + relative);
        }
        return resolved;
    }

    private String normalizeRelative(String raw, WorkspaceAgentSession session) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Project-relative path is required");
        }
        Path rawPath = Path.of(raw.replace('\\', '/')).normalize();
        if (rawPath.isAbsolute() || rawPath.startsWith("..")) {
            throw new IllegalArgumentException("Absolute or parent path is forbidden: " + raw);
        }
        String normalized = normalize(rawPath);
        resolveInsideRoot(session, normalized);
        return normalized;
    }

    private String projectType(WorkspaceAgentSession session) {
        if (session.workflowData().projectProfile != null) {
            return session.workflowData().projectProfile.projectType();
        }
        return session.workflowData().structure == null ? "SPRING_BOOT" : session.workflowData().structure.projectType();
    }

    private String executionJson(SandboxExecutionResult execution) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("passed", execution.completedSuccessfully());
        result.put("command", execution.command());
        result.put("exitCode", execution.exitCode());
        result.put("output", limitHeadAndTail(execution.output(), 16_000));
        result.put("truncated", execution.output() != null && execution.output().length() > 16_000);
        return result.toString();
    }

    private Map<String, String> snapshot(Path root) {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String relative = normalize(root.relativize(path));
                if (isReadableProjectFile(relative)) {
                    files.put(relative, Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to snapshot workspace", e);
        }
        return files;
    }

    public Map<String, String> snapshotWorkspace(Path root) {
        return snapshot(root.toAbsolutePath().normalize());
    }

    /**
     * 以会话开始快照为准计算最终真实差异，排除“修改后又改回原样”的伪变更。
     */
    public List<String> actualChangedFiles(WorkspaceAgentSession session) {
        Map<String, String> current = snapshot(session.projectRoot());
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>(session.baselineFiles().keySet());
        paths.addAll(current.keySet());
        return paths.stream()
                .filter(path -> !java.util.Objects.equals(session.baselineFiles().get(path), current.get(path)))
                .sorted()
                .toList();
    }

    public void restoreBaseline(WorkspaceAgentSession session) {
        try {
            Set<String> current = snapshot(session.projectRoot()).keySet();
            for (String path : current) {
                if (!session.baselineFiles().containsKey(path)) {
                    Files.deleteIfExists(resolveInsideRoot(session, path));
                }
            }
            for (Map.Entry<String, String> entry : session.baselineFiles().entrySet()) {
                atomicWrite(resolveInsideRoot(session, entry.getKey()), entry.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to restore workspace session", e);
        }
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent() == null ? target.toAbsolutePath().getParent() : target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tool-agent-tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ObjectNode objectSchema(String... definitions) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (int index = 0; index + 2 < definitions.length; index += 3) {
            String name = definitions[index];
            properties.putObject(name).put("type", definitions[index + 1]);
            if (Boolean.parseBoolean(definitions[index + 2])) {
                required.add(name);
            }
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode arrayPropertySchema(String name, boolean required) {
        ObjectNode schema = objectSchema();
        schema.with("properties").putObject(name).put("type", "array").putObject("items").put("type", "string");
        if (required) {
            schema.withArray("required").add(name);
        }
        return schema;
    }

    private ObjectNode applyEditsSchema() {
        ObjectNode schema = objectSchema();
        ObjectNode edits = schema.with("properties").putObject("edits");
        edits.put("type", "array");
        ObjectNode item = edits.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("path").put("type", "string");
        properties.putObject("expectedHash").put("type", "string");
        properties.putObject("oldText").put("type", "string");
        properties.putObject("newText").put("type", "string");
        item.putArray("required").add("path").add("expectedHash").add("oldText").add("newText");
        item.put("additionalProperties", false);
        schema.withArray("required").add("edits");
        return schema;
    }

    private String jsonError(String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("ok", false);
        error.put("error", code);
        error.put("message", message == null ? "" : message);
        return error.toString();
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean isReadableProjectFile(String path) {
        String normalized = normalizeText(path);
        return !normalized.startsWith("target/")
                && !normalized.startsWith(".git/")
                && !normalized.startsWith(".idea/")
                && !normalized.startsWith(PlanningArtifactPersistenceService.METADATA_DIRECTORY + "/")
                && !normalized.endsWith(".class")
                && !normalized.endsWith(".jar")
                && !normalized.endsWith(".png");
    }

    private boolean simpleGlobMatches(String path, String glob) {
        String regex = glob.replace(".", "\\.")
                .replace("**", "__DOUBLE_STAR__")
                .replace("*", "[^/]*")
                .replace("__DOUBLE_STAR__", ".*");
        return path.matches(regex);
    }

    private String normalize(Path path) { return normalizeText(path.toString()); }
    private String normalizeText(String path) { return path.replace('\\', '/'); }
    private String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value == null ? "" : value;
        return value.substring(0, maximum) + "\n...[truncated]";
    }
    private String limitHeadAndTail(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value == null ? "" : value;
        int head = maximum / 2;
        int tail = maximum - head;
        return value.substring(0, head) + "\n...[middle truncated; newest output follows]...\n"
                + value.substring(value.length() - tail);
    }
    private int lineCount(String value) { return value == null || value.isEmpty() ? 0 : value.split("\\R", -1).length; }

    private record PreparedEdit(String relativePath, Path path, String content) { }
}
