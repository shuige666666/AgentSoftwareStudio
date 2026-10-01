package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.core.multiAgentSoftwareStudio.Config.*;
import com.core.multiAgentSoftwareStudio.Model.Generation.*;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.*;
import com.core.multiAgentSoftwareStudio.Model.Workflow.*;
import com.core.multiAgentSoftwareStudio.Service.Generation.*;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.*;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.*;
import com.core.multiAgentSoftwareStudio.Service.Workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 手工启动有限真实对照；不由 Maven 单元测试自动调用远程模型。 */
public final class TaskMilestoneComparison {
    public static final String CASE = "task-workflow-behavior-v1";
    private static final Path FIXTURE = Path.of("src/test/resources/benchmark/task-workflow-v1");

    /** 固定案例规格只供手工实验使用；运行引擎与生产链共用。 */
    record CaseSpec(String caseId, Path fixture, String runPrefix, String acceptedTaskId, String baselineTest,
                    String oracleCoverage, java.util.function.BiFunction<Path, DevelopmentMilestonePolicy, SoftwareStudioWorkflowData> factory) { }

    /** 只接受第 1／2 组且拒绝覆盖已有日志，重复或失败启动不能无限重置实验额度。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("preflight", "1", "2").contains(args[1]))
            throw new IllegalArgumentException("Usage: TaskMilestoneComparison <shared-log-root> <preflight|1|2>");
        var spec = new CaseSpec(CASE, FIXTURE, "task_control", "task-create-query", "src/test/java/com/example/tasks/TaskBaselineTest.java",
                "Create/query/list; input validation; completion persistence and repeat; deletion; missing resources", TaskMilestoneComparison::data);
        run(Path.of(args[0]), args[1].equals("preflight") ? "preflight" : "group-" + args[1], spec,
                args[1].equals("2") ? List.of(DevelopmentMilestonePolicy.ADVISORY, DevelopmentMilestonePolicy.ORDERED)
                        : List.of(DevelopmentMilestonePolicy.ORDERED, DevelopmentMilestonePolicy.ADVISORY));
    }

    /** 固定规格、冻结版本、相等输入和每组统一预算；拒绝覆盖证据。 */
    static void run(Path logRoot, String runName, CaseSpec spec, List<DevelopmentMilestonePolicy> policies) throws Exception {
        boolean preflight = runName.startsWith("preflight");
        Path log = logRoot.toAbsolutePath().resolve(runName);
        if (Files.exists(log)) throw new IllegalStateException("Group already reserved; preserve evidence: " + log);
        Files.createDirectories(log);
        var app = new SpringApplication(SoftwareStudioApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off")) {
            var mapper = context.getBean(ObjectMapper.class);
            // 1. 先验证新构造的共享起点；预检不调用模型，也不占用配对实验次数。
            if (preflight) {
                Path root = Path.of("ai_generated_projects", spec.runPrefix() + "_preflight_20261001").toAbsolutePath();
                var seed = prepare(context, root, DevelopmentMilestonePolicy.ORDERED, spec);
                mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("initial-checkpoint.json").toFile(), seed.checkpoint());
                Files.writeString(log.resolve("initial-objective.txt"), DevelopmentMilestoneObjective.render(seed.data()), StandardCharsets.UTF_8);
                System.out.println("TASK_PREFLIGHT_PASS");
                return;
            }
            // 2. 固定双方共同预算并保存条件，模型配置直接引用实际运行常量。
            var limits = context.getBean(WorkspaceAgentLimitsConfig.class);
            limits.getDevelopment().setMaxModelTurns(20);
            limits.getDevelopment().setMaxToolCalls(60);
            var report = mapper.createObjectNode().put("caseId", spec.caseId()).put("group", runName)
                    .put("platformCommit", "c4fe76e plus working-tree changes; isolated runtime frozen before comparison")
                    .put("type", "fixed partial project continuation; not planning or scaffold generation")
                    .put("hypothesis", "Whether mandatory task order and per-task acceptance help complete two related capabilities")
                    .put("maxModelTurnsPerArm", 20).put("maxToolCallsPerArm", 60).put("maxGroups", 2)
                    .put("budgetReason", "Two narrow pending capabilities, inspection, behavior tests, checkpoint handoff and bounded repair slack")
                    .put("model", AiConfig.CODER_MODEL_NAME).put("temperature", AiConfig.CODER_MODEL_TEMPERATURE)
                    .put("maxOutputTokens", AiConfig.CODER_MODEL_MAX_OUTPUT_TOKENS)
                    .put("timeoutSeconds", AiConfig.CODER_MODEL_TIMEOUT.toSeconds()).put("cost", "UNKNOWN")
                    .put("successCriterion", "Full build, all valid tests, complete contract and all independent HTTP findings pass")
                    .put("oracleCoverage", spec.oracleCoverage() + "; not a concurrency or load test");
            report.set("contextPolicy", mapper.valueToTree(limits.getContext()));
            report.set("validationPolicy", mapper.valueToTree(limits.getValidation()));
            mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("experiment-spec.json").toFile(), report);
            var arms = report.putArray("arms");
            String commonSignature = "";
            for (var policy : policies) {
                // 3. 复制同一起点，检查共同输入；只有推进策略可以不同。
                String arm = policy.name().toLowerCase(Locale.ROOT);
                Path root = Path.of("ai_generated_projects", spec.runPrefix() + "_" + runName + "_" + arm + "_20261001").toAbsolutePath();
                var seed = prepare(context, root, policy, spec);
                var data = seed.data();
                var before = seed.checkpoint();
                var persistence = context.getBean(PersistenceNodeService.class);
                var milestones = context.getBean(DevelopmentMilestoneService.class);
                var checkpoints = context.getBean(WorkspaceCheckpointService.class);
                String goalHash = sha(DevelopmentMilestoneObjective.projectContext(data));
                String planHash = sha(mapper.writeValueAsString(data.sliceDeliveryPlan));
                String toolHash = sha(mapper.writeValueAsString(context.getBean(WorkspaceToolRegistry.class).definitions(WorkspaceAgentMode.DEVELOPMENT)));
                String signature = before.sourceHash() + goalHash + planHash + toolHash;
                if (!commonSignature.isBlank() && !commonSignature.equals(signature)) throw new IllegalStateException("Unequal comparison inputs");
                commonSignature = signature;
                Path armLog = log.resolve(arm); Files.createDirectories(armLog);
                mapper.writerWithDefaultPrettyPrinter().writeValue(armLog.resolve("initial-checkpoint.json").toFile(), before);
                Files.writeString(armLog.resolve("initial-objective.txt"), DevelopmentMilestoneObjective.render(data), StandardCharsets.UTF_8);
                var realModel = context.getBean(AiConfig.CODER_MODEL_BEAN_NAME, ToolCallingModel.class);
                ToolCallingModel model = (messages, tools) -> {
                    try {
                        var input = mapper.createObjectNode(); input.set("messages", mapper.valueToTree(messages)); input.set("tools", mapper.valueToTree(tools));
                        Files.writeString(armLog.resolve("model-inputs.jsonl"), mapper.writeValueAsString(input) + "\n", StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                    return realModel.generateWithTools(messages, tools);
                };
                var metrics = context.getBean(LlmUsageMetricsService.class);
                var audit = new WorkspaceAgentAuditConfig(); audit.setRootDirectory(log.resolve("audit").toString());
                var runtime = new WorkspaceAgentRuntime(model, context.getBean(WorkspaceToolRegistry.class), context.getBean(WorkspaceService.class),
                        metrics, limits, new ToolCallAuditService(mapper, audit), milestones);
                SoftwareStudioWorkflowData[] actual = {null}; WorkspaceAgentRunResult[] run = {null};
                var batch = new BatchGenerationService(runtime) {
                    @Override public WorkspaceAgentRunResult generateProject(SoftwareStudioWorkflowData value, java.util.function.Consumer<String> logger) {
                        actual[0] = value; run[0] = super.generateProject(value, logger); return run[0];
                    }
                };
                var workflow = new SoftwareStudioWorkflowService(context.getBean(PlanningNodeService.class), context.getBean(ProjectScaffoldService.class),
                        context.getBean(SlicePlanNodeService.class), milestones, context.getBean(DevelopmentResumeService.class), batch, persistence,
                        checkpoints, metrics, context.getBean(RunJournalService.class));
                // 4. 通过生产续接链运行开发，保存真实请求、用量与最终受检快照。
                System.out.println("TASK_ARM_START " + arm);
                long started = System.nanoTime();
                var result = workflow.resumeProjectWithResult(root.getFileName().toString(), message -> System.out.println("[" + arm + "] " + message));
                // 通过的 runtime 结果和外层复核均引用真实最终 checkpoint。
                var outcome = mapper.createObjectNode().put("arm", arm).put("initialHash", before.sourceHash()).put("commonGoalHash", goalHash)
                        .put("planHash", planHash).put("toolHash", toolHash).put("projectPath", root.toString()).put("platformSuccess", result.platformSuccess())
                        .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000L);
                outcome.set("runtime", mapper.valueToTree(run[0])); outcome.set("initialCheckpoint", mapper.valueToTree(before));
                outcome.set("finalCheckpoint", mapper.readTree(Path.of(actual[0].lastCheckpointReportPath).toFile()));
                outcome.set("usage", mapper.valueToTree(result.usage())); outcome.set("milestones", mapper.valueToTree(actual[0].milestoneState));
                // 字节相同只能证明文件未变；追加测试后的原断言是否保留另行核对。
                outcome.put("baselineTestUnchanged", Files.exists(root.resolve(spec.baselineTest()))
                        && Files.mismatch(spec.fixture().resolve(spec.baselineTest()), root.resolve(spec.baselineTest())) == -1);
                arms.add(outcome);
                mapper.writerWithDefaultPrettyPrinter().writeValue(log.resolve("comparison-result.json").toFile(), report);
                System.out.println("TASK_ARM_FINISH " + arm + " success=" + result.platformSuccess());
            }
        }
    }

    private record Seed(SoftwareStudioWorkflowData data, WorkspaceCheckpointService.CheckpointResult checkpoint) { }

    /** 为每个分支复制同一源码，并以真实 checkpoint 接受已有能力、暴露待实现行为。 */
    private static Seed prepare(ConfigurableApplicationContext context, Path root, DevelopmentMilestonePolicy policy, CaseSpec spec) throws Exception {
        if (Files.exists(root)) throw new IllegalStateException("Candidate exists: " + root);
        copyFixture(root, spec.fixture());
        var data = spec.factory().apply(root, policy);
        context.getBean(PersistenceNodeService.class).persistPlanningArtifacts(data,
                PlanningArtifactPersistenceService.PlanningStage.GENERATION_PLAN, System.out::println);
        var milestones = context.getBean(DevelopmentMilestoneService.class);
        milestones.initialize(data);
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, System.out::println, Map.of(), Set.of());
        var before = context.getBean(WorkspaceCheckpointService.class).verify(session, "progress",
                "Shared existing capability", milestones.contractThroughCurrent(data));
        if (!before.build().passed() || !before.test().passed() || !before.contractPassed()
                || !"FAIL".equals(before.behavior().status()))
            throw new IllegalStateException("Shared baseline must pass build/tests/base contract and expose pending behavior failures: " + before.failureOutput());
        milestones.accept(data, spec.acceptedTaskId(), before);
        milestones.recordFailure(data, WorkspaceCheckpointFeedback.behaviorFailures(before.behavior(), before.sourceHash(), before.reportPath()));
        return new Seed(data, before);
    }

    /** 相同需求与固定规划用于控制对照，不调用规划模型或依赖业务关键词推断拆分。 */
    static SoftwareStudioWorkflowData data(Path root, DevelopmentMilestonePolicy policy) {
        String request = "Java 17 Spring Boot task API. Existing create/list/query must remain correct. "
                + "POST /api/tasks JSON {title}: 201 {id:number,title:original,completed:false}; title null/blank/over100:400 without mutation. "
                + "GET /api/tasks:200 array; GET /api/tasks/{id}:200 task or404. "
                + "POST /api/tasks/{id}/complete:200 same id/title with completed=true persisted, repeat idempotent, missing404. "
                + "DELETE /api/tasks/{id}:204 remove; absent404. Preserve valid baseline tests, add meaningful tests and update README. "
                + "Use the existing memory store and default port8080. No extra features.";
        var data = new SoftwareStudioWorkflowData(request); data.projectPath = root.toString(); data.behaviorCheckId = CASE;
        data.projectProfile = ProjectProfile.java17SpringBoot(); data.milestonePolicy = policy;
        data.prd = new PrdDocument("Task workflow", "Complete task lifecycle while preserving existing capability",
                List.of("Create and query", "Complete with persistent state", "Delete and verify absence"), List.of("Java17", "SpringBoot3.2.4", "Maven"));
        var files = List.of(new FileBlueprint("TaskController.java", "src/main/java/com/example/tasks/TaskController.java", "controller", "shared",
                        "HTTP status, request and response mapping", List.of(), List.of()),
                new FileBlueprint("TaskStore.java", "src/main/java/com/example/tasks/TaskStore.java", "service", "shared",
                        "Shared in-memory task state", List.of(), List.of()));
        data.structure = new ProjectStructure("com.example.tasks", "SPRING_BOOT", "TasksApplication", files);
        var create = endpoint("POST", "/api/tasks", "com.example.tasks.TaskRequest", "com.example.tasks.Task", "201; title validation400");
        var list = endpoint("GET", "/api/tasks", "", "", "200 JSON array");
        var query = endpoint("GET", "/api/tasks/{id}", "", "com.example.tasks.Task", "200 or404");
        var complete = endpoint("POST", "/api/tasks/{id}/complete", "", "com.example.tasks.Task", "200; persisted true; repeat idempotent; missing404");
        var delete = endpoint("DELETE", "/api/tasks/{id}", "", "", "204 remove; missing404");
        data.contract = contract(List.of(create, list, query, complete, delete));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                slice("task-create-query", "Existing create and query", List.of(create, list, query), List.of()),
                slice("task-complete", "Complete task and retain state", List.of(complete), List.of("task-create-query")),
                slice("task-delete", "Delete task and verify absence", List.of(delete), List.of("task-create-query"))),
                files.stream().map(FileBlueprint::targetPath).toList(), "controlled-task-workflow-v1");
        data.generationPlan = new GenerationPlan(List.of());
        return data;
    }
    private static ApiEndpointContract endpoint(String method, String path, String request, String response, String rule) {
        return new ApiEndpointContract(method, path, request, response, "", rule);
    }
    private static ProjectContract contract(List<ApiEndpointContract> endpoints) { return new ProjectContract(endpoints, List.of(), List.of(), List.of()); }
    private static DeliverySlice slice(String id, String goal, List<ApiEndpointContract> endpoints, List<String> depends) {
        return new DeliverySlice(id, goal, List.of("Implement declared HTTP behavior and test observable state; preserve baseline"), List.of(), contract(endpoints), depends,
                List.of("src/main/java/com/example/tasks/TaskController.java", "src/main/java/com/example/tasks/TaskStore.java"));
    }
    /** 创建全新候选，不覆盖原产物；共享起点中的测试完整复制。 */
    private static void copyFixture(Path root, Path fixture) throws Exception {
        try (var paths = Files.walk(fixture)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve(fixture.relativize(file)); Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
    }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
