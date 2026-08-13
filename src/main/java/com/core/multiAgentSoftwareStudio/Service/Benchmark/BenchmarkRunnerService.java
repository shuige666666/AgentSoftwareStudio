package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Config.AiConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.AgentModelAssignment;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCaseResult;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkModelInfo;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkVerificationSummary;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkQualityResult;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunConfiguration;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRepairBudgetConfiguration;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunReport;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunRequest;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 顺序执行真实 LLM 基准任务。该服务只会被显式 HTTP 请求调用，绝不会由单元测试触发。
 */
@Service
public class BenchmarkRunnerService {

    private static final Path REPORT_ROOT = Path.of("benchmark-results");
    private static final long PROGRESS_HEARTBEAT_SECONDS = 30;
    private static final int MAX_REPETITIONS = 10;

    private final BenchmarkCaseRegistry caseRegistry;
    private final BenchmarkQualityEvaluator qualityEvaluator;
    private final SoftwareStudioWorkflowService workflowService;
    private final LlmUsageMetricsService llmUsageMetricsService;
    private final ObjectMapper objectMapper;
    private final String modelBaseUrl;

    public BenchmarkRunnerService(BenchmarkCaseRegistry caseRegistry,
            BenchmarkQualityEvaluator qualityEvaluator,
            SoftwareStudioWorkflowService workflowService,
            LlmUsageMetricsService llmUsageMetricsService,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url}") String modelBaseUrl) {
        this.caseRegistry = caseRegistry;
        this.qualityEvaluator = qualityEvaluator;
        this.workflowService = workflowService;
        this.llmUsageMetricsService = llmUsageMetricsService;
        this.objectMapper = objectMapper;
        this.modelBaseUrl = modelBaseUrl;
    }

    public List<BenchmarkCase> listCases() {
        return caseRegistry.allCases();
    }

    /**
     * 将当前集中配置的修复预算策略写入报告，避免继续记录已经不控制运行行为的旧重试参数。
     */
    private BenchmarkRepairBudgetConfiguration repairBudgetConfiguration() {
        RepairBudget budget = RepairBudget.forToolDrivenProject();
        return new BenchmarkRepairBudgetConfiguration(
                budget.policyVersion(),
                budget.maxLlmRepairs(),
                0,
                budget.maxRepairsPerSlice(),
                budget.reservedForFinalVerification());
    }

    public BenchmarkRunReport run(BenchmarkRunRequest request) {
        long batchStartedNanos = System.nanoTime();
        List<BenchmarkCase> selectedCases = caseRegistry.select(request == null ? List.of() : request.caseIds());
        int repetitions = resolveRepetitions(request == null ? null : request.repetitions());
        List<BenchmarkCase> cases = repeatCases(selectedCases, repetitions);
        Map<String, String> releaseLabels = request == null || request.modelReleaseLabels() == null
                ? Map.of()
                : request.modelReleaseLabels();
        Instant started = Instant.now();
        List<BenchmarkCaseResult> results = new ArrayList<>();
        Map<String, BenchmarkModelInfo> models = modelInfo(releaseLabels);
        List<AgentModelAssignment> agentModelAssignments = agentModelAssignments();
        GitSnapshot gitSnapshot = resolveGitSnapshot();
        Path reportPath = reportPathFor(started).toAbsolutePath();
        BenchmarkRunConfiguration benchmarkConfig = new BenchmarkRunConfiguration(
                selectedCases.stream().map(BenchmarkCase::id).toList(), repetitions, repairBudgetConfiguration(),
                gitSnapshot.commit(), gitSnapshot.dirty(), gitSnapshot.workingTreeFingerprint());

        System.out.println("[Benchmark] 质量基准测试进度：0/" + cases.size()
                + "，每个案例重复 " + repetitions + " 次，准备执行。");
        for (int index = 0; index < cases.size(); index++) {
            BenchmarkCase benchmarkCase = cases.get(index);
            int repetitionIndex = index % repetitions + 1;
            results.add(runCase(benchmarkCase, repetitionIndex, repetitions, index + 1, cases.size()));
            // 每个重复样本完成后立即覆盖检查点；后续超时或进程中断不会丢失已经完成的结果。
            BenchmarkRunReport checkpoint = buildReport(
                    started, Instant.now(), models, agentModelAssignments, benchmarkConfig, results, reportPath);
            persist(checkpoint, reportPath);
            System.out.println("[Benchmark] 已保存检查点：" + results.size() + "/" + cases.size()
                    + "，报告=" + reportPath);
        }

        Instant finished = Instant.now();
        BenchmarkRunReport finalReport = buildReport(
                started, finished, models, agentModelAssignments, benchmarkConfig, results, reportPath);
        persist(finalReport, reportPath);
        long totalElapsedMillis = elapsedMillis(batchStartedNanos);
        System.out.println("[Benchmark] 质量基准测试进度：" + cases.size() + "/" + cases.size()
                + "，全部任务已完成，整批总用时 " + formatTotalElapsed(totalElapsedMillis) + "。");
        return finalReport;
    }

    /**
     * 根据当前已完成样本构建可独立读取的报告，供中途检查点和最终结果共用。
     */
    static BenchmarkRunReport buildReport(
            Instant started,
            Instant finished,
            Map<String, BenchmarkModelInfo> models,
            List<AgentModelAssignment> agentModelAssignments,
            BenchmarkRunConfiguration benchmarkConfig,
            List<BenchmarkCaseResult> results,
            Path reportPath) {
        List<BenchmarkCaseResult> completedResults = List.copyOf(results);
        int platformSuccessCount = (int) completedResults.stream()
                .filter(BenchmarkCaseResult::platformSuccess).count();
        int independentPassCount = (int) completedResults.stream()
                .filter(BenchmarkCaseResult::independentQualityPassed).count();
        int falseSuccessCount = (int) completedResults.stream()
                .filter(result -> result.platformSuccess() && !result.independentQualityPassed())
                .count();
        Double falseSuccessRate = platformSuccessCount == 0
                ? null
                : (double) falseSuccessCount / platformSuccessCount;
        return new BenchmarkRunReport(
                started.toString(), finished.toString(), models, agentModelAssignments, benchmarkConfig,
                completedResults, platformSuccessCount, independentPassCount,
                falseSuccessCount, falseSuccessRate, reportPath.toString());
    }

    /**
     * 执行单个基准任务，并持续输出当前任务序号和耗时，避免长时间 LLM 调用看起来像控制台失去响应。
     */
    private BenchmarkCaseResult runCase(
            BenchmarkCase benchmarkCase,
            int repetitionIndex,
            int repetitionCount,
            int currentCase,
            int totalCases) {
        long started = System.nanoTime();
        printProgress(benchmarkCase.id(), currentCase, totalCases, "正在执行", started);
        ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeat = progressExecutor.scheduleWithFixedDelay(
                () -> printProgress(benchmarkCase.id(), currentCase, totalCases, "仍在执行", started),
                PROGRESS_HEARTBEAT_SECONDS,
                PROGRESS_HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
        try {
            WorkflowExecutionResult execution = workflowService.generateProjectWithResult(
                    benchmarkCase.prompt(),
                    message -> System.out.println("[Benchmark " + currentCase + "/" + totalCases + " "
                            + benchmarkCase.id() + "] " + message));
            BenchmarkQualityResult quality = qualityEvaluator.evaluate(benchmarkCase, execution);
            return new BenchmarkCaseResult(
                    benchmarkCase.id(), repetitionIndex, repetitionCount,
                    execution.platformSuccess(), quality.passed(), "NOT_REVIEWED",
                    execution.projectPath(), elapsedMillis(started), quality, execution.usage(),
                    execution.attemptsUsed(), resolveFinalFailure(execution),
                    execution.validationWarnings().size(),
                    BenchmarkVerificationSummary.from(execution.verification()), execution.runSummary(), null);
        } catch (Exception e) {
            BenchmarkQualityResult quality = new BenchmarkQualityResult(false, List.of());
            return new BenchmarkCaseResult(
                    benchmarkCase.id(), repetitionIndex, repetitionCount,
                    false, false, "NOT_REVIEWED", null, elapsedMillis(started), quality,
                    llmUsageMetricsService.snapshot(),
                    0, com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.UNKNOWN, 0,
                    BenchmarkVerificationSummary.empty(),
                    com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary.empty(),
                    e.getClass().getSimpleName() + ": " + safeMessage(e));
        } finally {
            // 当前任务结束后停止心跳线程，避免后续任务的控制台进度被旧任务重复刷写。
            heartbeat.cancel(false);
            progressExecutor.shutdown();
            printProgress(benchmarkCase.id(), currentCase, totalCases, "已完成", started);
        }
    }

    /**
     * 校验每个案例的重复次数，避免误传过大数值造成不可控的模型调用和等待时间。
     */
    static int resolveRepetitions(Integer requestedRepetitions) {
        int repetitions = requestedRepetitions == null ? 1 : requestedRepetitions;
        if (repetitions < 1 || repetitions > MAX_REPETITIONS) {
            throw new IllegalArgumentException("repetitions must be between 1 and " + MAX_REPETITIONS);
        }
        return repetitions;
    }

    /**
     * 将每个选中案例连续展开指定次数，便于在一个请求中获得可区分的重复样本。
     */
    static List<BenchmarkCase> repeatCases(List<BenchmarkCase> selectedCases, int repetitions) {
        List<BenchmarkCase> repeated = new ArrayList<>();
        for (BenchmarkCase benchmarkCase : selectedCases) {
            for (int repetition = 0; repetition < repetitions; repetition++) {
                repeated.add(benchmarkCase);
            }
        }
        return List.copyOf(repeated);
    }

    /**
     * 输出统一的基准测试进度行；心跳日志用于暴露外部调用长时间无响应的实际卡点。
     */
    private void printProgress(String caseId, int currentCase, int totalCases, String status, long caseStartedNanos) {
        System.out.println("[Benchmark] 质量基准测试进度：" + currentCase + "/" + totalCases
                + "，" + status + " " + caseId + "，本任务已耗时 " + elapsedMillis(caseStartedNanos) / 1000 + " 秒。");
    }

    private Path reportPathFor(Instant started) {
        String name = "benchmark-" + DateTimeFormatter.ISO_INSTANT.format(started).replace(':', '-') + ".json";
        return REPORT_ROOT.resolve(name);
    }

    void persist(BenchmarkRunReport report, Path reportPath) {
        try {
            Files.createDirectories(REPORT_ROOT);
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryReport = reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryReport.toFile(), report);
            try {
                Files.move(temporaryReport, reportPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryReport, reportPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist benchmark report", e);
        }
    }

    /**
     * 从 AiConfig 的实际运行常量生成模型快照，避免报告与真实调用参数分别维护。
     */
    private Map<String, BenchmarkModelInfo> modelInfo(Map<String, String> releaseLabels) {
        String provider = providerFrom(modelBaseUrl);
        Map<String, BenchmarkModelInfo> models = new LinkedHashMap<>();
        models.put(AiConfig.CODER_MODEL_BEAN_NAME, new BenchmarkModelInfo(
                AiConfig.CODER_MODEL_NAME, null, provider, modelBaseUrl,
                AiConfig.CODER_MODEL_TEMPERATURE, AiConfig.CODER_MODEL_MAX_OUTPUT_TOKENS,
                AiConfig.CODER_MODEL_TIMEOUT.toSeconds(), AiConfig.CODER_MODEL_JSON_OUTPUT_ENABLED, "unknown",
                normalizeNullable(releaseLabels.get(AiConfig.CODER_MODEL_BEAN_NAME))));
        models.put(AiConfig.LOGIC_MODEL_BEAN_NAME, new BenchmarkModelInfo(
                AiConfig.LOGIC_MODEL_NAME, null, provider, modelBaseUrl,
                AiConfig.LOGIC_MODEL_TEMPERATURE, AiConfig.LOGIC_MODEL_MAX_OUTPUT_TOKENS,
                AiConfig.LOGIC_MODEL_TIMEOUT.toSeconds(), AiConfig.LOGIC_MODEL_JSON_OUTPUT_ENABLED, "unknown",
                normalizeNullable(releaseLabels.get(AiConfig.LOGIC_MODEL_BEAN_NAME))));
        return Collections.unmodifiableMap(models);
    }

    /**
     * 直接读取 AiConfig 中 Agent Bean 参数的 Qualifier，确保报告映射跟随真实注入关系变化。
     */
    static List<AgentModelAssignment> agentModelAssignments() {
        return Arrays.stream(AiConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(BenchmarkRunnerService::agentModelAssignment)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AgentModelAssignment::agent))
                .toList();
    }

    private static AgentModelAssignment agentModelAssignment(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (!ChatLanguageModel.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
            if (qualifier != null) {
                return new AgentModelAssignment(method.getReturnType().getSimpleName(), qualifier.value());
            }
        }
        return null;
    }

    /**
     * 优先记录供应商接口域名；非标准地址也不阻断基准任务。
     */
    private String providerFrom(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException ignored) {
            return "unknown";
        }
    }

    /**
     * 读取当前 Git 提交号；在无 Git 环境的部署包中保留 unknown，不影响测试执行。
     */
    private GitSnapshot resolveGitSnapshot() {
        String environmentCommit = normalizeNullable(System.getenv("GIT_COMMIT"));
        String commit = environmentCommit == null ? runGit("rev-parse", "HEAD") : environmentCommit;
        if (commit == null) {
            commit = "unknown";
        }

        String rawStatus = runGit("-c", "core.quotepath=false", "status", "--porcelain=v1", "--untracked-files=all");
        if (rawStatus == null) {
            return new GitSnapshot(commit, false, null);
        }
        List<String> sourceStatus = rawStatus.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> !isGeneratedArtifactStatus(line))
                .toList();
        if (sourceStatus.isEmpty()) {
            return new GitSnapshot(commit, false, null);
        }

        String diff = runGit("diff", "--binary", "HEAD", "--", ".",
                ":(exclude)benchmark-results/**", ":(exclude)ai_generated_projects/**", ":(exclude)target/**");
        StringBuilder fingerprintInput = new StringBuilder(String.join("\n", sourceStatus))
                .append("\n---DIFF---\n")
                .append(diff == null ? "" : diff);
        for (String statusLine : sourceStatus) {
            if (!statusLine.startsWith("?? ")) {
                continue;
            }
            Path untracked = Path.of(statusLine.substring(3).trim());
            if (!Files.isRegularFile(untracked)) {
                continue;
            }
            try {
                fingerprintInput.append("\n---UNTRACKED:").append(untracked).append("---\n")
                        .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(untracked))));
            } catch (IOException | NoSuchAlgorithmException ignored) {
                fingerprintInput.append("<unreadable>");
            }
        }
        return new GitSnapshot(commit, true, sha256(fingerprintInput.toString()));
    }

    private boolean isGeneratedArtifactStatus(String statusLine) {
        String path = statusLine.length() <= 3 ? "" : statusLine.substring(3).trim().replace('\\', '/');
        return path.startsWith("benchmark-results/")
                || path.startsWith("ai_generated_projects/")
                || path.startsWith("target/");
    }

    private String runGit(String... arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(arguments));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                output.cancel(true);
                return null;
            }
            return normalizeNullable(new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (IOException | ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private record GitSnapshot(String commit, boolean dirty, String workingTreeFingerprint) {
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 将整批毫秒耗时格式化为适合控制台阅读的分秒文本，同时保留精确毫秒值。
     */
    static String formatTotalElapsed(long elapsedMillis) {
        long minutes = elapsedMillis / 60_000;
        long seconds = elapsedMillis % 60_000 / 1_000;
        return minutes + " 分 " + seconds + " 秒（" + elapsedMillis + " 毫秒）";
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? "No message" : error.getMessage();
    }

    private com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind resolveFinalFailure(
            WorkflowExecutionResult execution) {
        if (execution == null || execution.platformSuccess()) {
            return com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.NONE;
        }
        if (execution.pendingErrorType() != null && !execution.pendingErrorType().isBlank()) {
            try {
                return com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.valueOf(execution.pendingErrorType());
            } catch (IllegalArgumentException ignored) {
                // 兼容旧工作流返回的自由文本错误类型，继续使用结构化验证结果兜底。
            }
        }
        if (execution.verification() != null
                && execution.verification().primaryFailureKind()
                        != com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.NONE) {
            return execution.verification().primaryFailureKind();
        }
        return com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.UNKNOWN;
    }
}
