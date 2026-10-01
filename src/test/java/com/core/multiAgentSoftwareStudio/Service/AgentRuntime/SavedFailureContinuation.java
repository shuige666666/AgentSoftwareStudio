package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.AiConfig;
import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Generation.ProjectScaffoldService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.*;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.*;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** 手工验证保存失败候选的有限续接，不由 Maven 测试自动调用远程模型。 */
public final class SavedFailureContinuation {
    private static final int ADDED_MODEL_TURNS = 12;
    private static final int ADDED_TOOL_CALLS = 36;

    /** 在新副本复验起点、交付真实反馈，再走生产续接链；原候选和累计预算保留。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SavedFailureContinuation <project-id> <saved-checkpoint.json> <new-project-id> <new-log-dir>");
        }
        Path log = Path.of(args[3]).toAbsolutePath().normalize();
        if (Files.exists(log)) throw new IllegalArgumentException("Preserve prior continuation evidence");
        Files.createDirectories(log);
        var app = new SpringApplication(SoftwareStudioApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off",
                "--studio.agent-runtime.audit.root-directory=" + log.resolve("audit"))) {
            if (!context.getBean(DockerSandboxService.class).isDockerAvailable()) {
                throw new IllegalStateException("Docker must be available before remote continuation");
            }
            var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            var registry = context.getBean(WorkspaceToolRegistry.class);
            var resume = context.getBean(DevelopmentResumeService.class);
            var original = resume.load(args[0]);
            var saved = mapper.readTree(Path.of(args[1]).toFile()).path("result");
            Path source = Path.of(saved.path("snapshotPath").asText()).toAbsolutePath().normalize();
            var originalFiles = registry.snapshotWorkspace(source);
            Path base = Path.of("ai_generated_projects").toAbsolutePath().normalize().toRealPath();
            if (!args[2].matches("[\\p{L}\\p{N}_-]{1,120}")) throw new IllegalArgumentException("Invalid new project ID");
            Path root = base.resolve(args[2]);
            if (Files.exists(root)) throw new IllegalArgumentException("Preserve existing project");
            copy(source, root);
            copy(Path.of(original.projectPath).resolve(".software-studio"), root.resolve(".software-studio"));
            var data = resume.load(args[2]);
            var limits = context.getBean(WorkspaceAgentLimitsConfig.class);
            int previousTurns = data.developmentModelTurnsUsed;
            int previousTools = data.developmentToolCallsUsed;
            limits.getDevelopment().setMaxModelTurns(previousTurns + ADDED_MODEL_TURNS);
            limits.getDevelopment().setMaxToolCalls(previousTools + ADDED_TOOL_CALLS);
            mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("conditions.json").toFile(), Map.of(
                    "originalProject", args[0], "newProject", args[2], "model", AiConfig.CODER_MODEL_NAME,
                    "previousModelTurns", previousTurns, "previousToolCalls", previousTools,
                    "addedModelTurns", ADDED_MODEL_TURNS, "addedToolCalls", ADDED_TOOL_CALLS,
                    "planningCalls", 0, "cost", "UNKNOWN"));

            // 新规则重新取证，反馈由验证器产生，不把人工分析作为模型修复答案。
            var checkpoints = context.getBean(WorkspaceCheckpointService.class);
            var before = checkpoints.verifyCandidate(data, System.out::println);
            if (!saved.path("sourceHash").asText().equals(before.sourceHash()) || "PASSED".equals(before.status())) {
                throw new IllegalStateException("Starting source must match the saved failed candidate");
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("initial-checkpoint.json").toFile(), before);
            String feedback = WorkspaceCheckpointFeedback.failureSummary(before);
            var milestones = context.getBean(DevelopmentMilestoneService.class);
            milestones.recordFailure(data, feedback);
            Files.writeString(log.resolve("initial-feedback.txt"), feedback, StandardCharsets.UTF_8);

            var model = context.getBean(AiConfig.CODER_MODEL_BEAN_NAME, ToolCallingModel.class);
            ToolCallingModel observed = (messages, tools) -> {
                try {
                    Files.writeString(log.resolve("model-inputs.jsonl"), mapper.writeValueAsString(messages) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception e) { throw new IllegalStateException("Unable to preserve model input", e); }
                return model.generateWithTools(messages, tools);
            };
            var metrics = context.getBean(LlmUsageMetricsService.class);
            var runtime = new WorkspaceAgentRuntime(observed, registry, context.getBean(WorkspaceService.class),
                    metrics, limits, context.getBean(ToolCallAuditService.class), milestones);
            SoftwareStudioWorkflowData[] actual = {null};
            WorkspaceAgentRunResult[] run = {null};
            var batch = new BatchGenerationService(runtime) {
                @Override public WorkspaceAgentRunResult generateProject(SoftwareStudioWorkflowData value,
                        java.util.function.Consumer<String> logger) {
                    actual[0] = value;
                    run[0] = super.generateProject(value, logger);
                    return run[0];
                }
            };
            var workflow = new SoftwareStudioWorkflowService(context.getBean(PlanningNodeService.class),
                    context.getBean(ProjectScaffoldService.class), context.getBean(SlicePlanNodeService.class), milestones,
                    resume, batch, context.getBean(PersistenceNodeService.class), checkpoints, metrics,
                    context.getBean(RunJournalService.class));
            long started = System.nanoTime();
            var result = workflow.resumeProjectWithResult(args[2], System.out::println);
            var report = mapper.createObjectNode().put("type", "bounded saved failure continuation; not a matched treatment comparison")
                    .put("platformSuccess", result.platformSuccess()).put("originalProject", args[0])
                    .put("projectPath", root.toString()).put("initialHash", before.sourceHash())
                    .put("previousModelTurns", previousTurns).put("previousToolCalls", previousTools)
                    .put("addedModelTurnsUsed", actual[0].developmentModelTurnsUsed - previousTurns)
                    .put("addedToolCallsUsed", actual[0].developmentToolCallsUsed - previousTools)
                    .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000L).put("cost", "UNKNOWN");
            report.set("runtime", mapper.valueToTree(run[0]));
            report.set("finalCheckpoint", mapper.readTree(Path.of(actual[0].lastCheckpointReportPath).toFile()));
            report.set("usage", mapper.valueToTree(result.usage()));
            report.put("originalSnapshotUnchanged", originalFiles.equals(registry.snapshotWorkspace(source)));
            mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("result.json").toFile(), report);
            System.out.println("SAVED_CONTINUATION_COMPLETE=" + result.platformSuccess());
        }
    }

    /** 只复制固定源码与平台元数据到全新目录，不复制编译缓存或重执行历史工具。 */
    private static void copy(Path source, Path targetRoot) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = targetRoot.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }
}
