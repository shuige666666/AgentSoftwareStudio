package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 冻结候选源码到平台日志目录，在独立副本执行正式编译、全量测试及最终契约门禁。
 */
@Service
public class WorkspaceCheckpointService {
    private static final int MAX_FILES = 2_000;
    private static final long MAX_BYTES = 80L * 1024 * 1024;

    public record CheckpointResult(String scope, String status, String sourceHash, String snapshotPath,
                                   String reportPath, VerificationStepResult build,
                                   VerificationStepResult test, boolean contractPassed,
                                   List<String> contractBlockers, String failureOutput,
                                   boolean allowCompletion, boolean reusedVerification) { }

    public record RestoreResult(String milestoneId, String restoredHash, String backupPath,
                                List<String> changedFiles) { }

    private final DockerSandboxService sandbox;
    private final VerificationResultService verification;
    private final ProjectProfileService profiles;
    private final WorkspaceService workspace;
    private final RunJournalService journal;
    private final ObjectMapper mapper;

    public WorkspaceCheckpointService(DockerSandboxService sandbox, VerificationResultService verification,
                                      ProjectProfileService profiles, WorkspaceService workspace,
                                      RunJournalService journal, ObjectMapper mapper) {
        this.sandbox = sandbox;
        this.verification = verification;
        this.profiles = profiles;
        this.workspace = workspace;
        this.journal = journal;
        this.mapper = mapper;
    }

    /** 会话异常或达到轮次上限时，仍对保留的候选执行一次最终独立验收。 */
    public CheckpointResult verifyCandidate(com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData data,
                                            Consumer<String> logger) {
        WorkspaceAgentSession session = new WorkspaceAgentSession(Path.of(data.projectPath),
                WorkspaceAgentMode.DEVELOPMENT, data, logger, Map.of(), Set.of());
        return verify(session, "final", "Final verification after development session stopped");
    }

    /**
     * progress 只检查构建与已有测试；final 再检查契约，并且仅最终通过允许结束开发。
     */
    public CheckpointResult verify(WorkspaceAgentSession session, String scope, String summary) {
        return verify(session, scope, summary, null);
    }

    /** 里程碑 progress 额外检查此前已接受能力和当前能力的公开契约。 */
    public CheckpointResult verify(WorkspaceAgentSession session, String scope, String summary,
                                   ProjectContract progressContract) {
        if (!"progress".equals(scope) && !"final".equals(scope)) {
            throw new IllegalArgumentException("Checkpoint scope must be progress or final");
        }
        boolean finalScope = "final".equals(scope);
        // 容器可能有后台写入；先关闭，再按源文件内容冻结本次真实受检版本。
        session.closeShell(sandbox);
        Path root = session.projectRoot();
        Path reportDirectory = Path.of("studio-run-logs", root.getFileName().toString(), "checkpoints",
                UUID.randomUUID().toString()).toAbsolutePath().normalize();
        Path source = reportDirectory.resolve("source");
        Path verificationWork = reportDirectory.resolve("verification-work");
        try {
            String priorHash = session.lastCheckpointHash();
            boolean priorPassed = session.lastCheckpointPassed();
            VerificationResult priorVerification = session.workflowData().verificationResult;
            String priorReport = session.workflowData().lastCheckpointReportPath;
            Files.createDirectories(source);
            copyCandidate(root, source);
            String hash = fingerprint(source);
            boolean reuse = finalScope && priorPassed && hash.equals(priorHash)
                    && priorVerification != null && priorVerification.passed();
            String type = session.workflowData().projectProfile == null
                    ? session.workflowData().structure.projectType()
                    : session.workflowData().projectProfile.projectType();
            session.workflowData().codes = workspace.loadProjectFromDisk(root, message -> { });
            // 本次快照失败不得继承上一个 checkpoint 的测试和契约结论。
            session.workflowData().qualityPolicyResult = ProjectQualityPolicyResult.empty();
            SandboxExecutionResult buildExecution = null;
            SandboxExecutionResult testExecution = null;
            VerificationStepResult build;
            VerificationStepResult test;
            if (reuse) {
                // 相同源码版本的 progress 已完成完整构建与测试，final 仅补契约门禁。
                build = priorVerification.build();
                test = priorVerification.test();
                copyPriorLog(priorReport, "build.log", reportDirectory);
                copyPriorLog(priorReport, "test.log", reportDirectory);
            } else {
                session.workflowData().testResult = null;
                // 测试可能自行写文件；只在执行副本运行 Maven，保留受检源码快照原样。
                Files.createDirectories(verificationWork);
                copyCandidate(source, verificationWork);
                buildExecution = runCheckpointCommand(verificationWork, type, true, reportDirectory);
                Files.writeString(reportDirectory.resolve("build.log"), safe(buildExecution.output()), StandardCharsets.UTF_8);
                build = verification.toStep(VerificationStage.BUILD, buildExecution);
                session.workflowData().executionResult = buildExecution.output();
                session.workflowData().verificationResult = VerificationResult.afterBuild(build);
                journal.recordVerification(session.workflowData(), build);
                test = VerificationStepResult.notRun(VerificationStage.TEST);
                if (build.passed()) {
                    testExecution = runCheckpointCommand(verificationWork, type, false, reportDirectory);
                    Files.writeString(reportDirectory.resolve("test.log"), safe(testExecution.output()), StandardCharsets.UTF_8);
                    test = verification.toStep(VerificationStage.TEST, testExecution);
                    session.workflowData().testResult = testExecution.output();
                    session.workflowData().verificationResult = session.workflowData().verificationResult.withTest(test);
                    journal.recordVerification(session.workflowData(), test);
                }
            }

            boolean checkContract = finalScope || progressContract != null;
            boolean contractPassed = !checkContract;
            List<String> blockers = List.of();
            if (checkContract && build.passed() && test.passed()) {
                ProjectQualityPolicyResult quality = profiles.evaluate(session.workflowData().projectProfile,
                        workspace.loadProjectFromDisk(source, message -> { }),
                        finalScope ? session.workflowData().contract : progressContract, List.of());
                session.workflowData().qualityPolicyResult = quality;
                blockers = quality.blockingFindings().stream()
                        .map(finding -> finding.gate() + ": " + finding.evidence()).toList();
                contractPassed = blockers.isEmpty();
            }
            boolean sameSource = hash.equals(fingerprint(root));
            if (!sameSource) blockers = concat(blockers, "Candidate changed during isolated verification");
            boolean passed = build.passed() && test.passed() && contractPassed && sameSource;
            boolean allowCompletion = finalScope && passed;
            session.recordCheckpoint(hash, passed, finalScope);
            session.workflowData().success = allowCompletion;
            if (finalScope) session.workflowData().finalVerificationStarted = true;
            String status = passed ? "PASSED"
                    : build.failureKind() == FailureKind.ENVIRONMENT || test.failureKind() == FailureKind.ENVIRONMENT
                    ? "BLOCKED" : "FAILED";
            String failureOutput = passed ? "" : shortOutput(!build.passed()
                    ? buildExecution.output() : !test.passed() && testExecution != null
                    ? testExecution.output() : String.join("\n", blockers));
            if (!passed && build.passed() && !test.passed() && !reuse) {
                // 新执行副本没有旧报告，先给出实际失败方法和源码位置，再保留有界日志。
                String evidence = SurefireFailureFeedback.read(verificationWork, Map.of());
                if (!evidence.isBlank()) failureOutput = evidence + "\n" + failureOutput;
            }
            CheckpointResult result = new CheckpointResult(scope, status, hash, source.toString(),
                    reportDirectory.resolve("checkpoint.json").toString(), build, test, contractPassed,
                    blockers.stream().limit(12).toList(), failureOutput, allowCompletion, reuse);
            session.workflowData().lastCheckpointHash = hash;
            session.workflowData().lastCheckpointSnapshotPath = result.snapshotPath();
            session.workflowData().lastCheckpointReportPath = result.reportPath();
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportDirectory.resolve("checkpoint.json").toFile(),
                    new CheckpointRecord(OffsetDateTime.now().toString(), summary, result));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create or verify source snapshot", e);
        }
    }

    private record CheckpointRecord(String completedAt, String summary, CheckpointResult result) { }

    /** 核对最近接受的快照哈希后恢复受管源码；先备份当前候选，不触碰平台元数据。 */
    public RestoreResult restoreLatestAccepted(WorkspaceAgentSession session) {
        DevelopmentMilestoneState state = session.workflowData().milestoneState;
        DevelopmentMilestoneState.Task accepted = state == null ? null : state.lastAccepted();
        if (accepted == null) throw new IllegalStateException("No accepted milestone checkpoint");
        session.closeShell(sandbox);
        Path root = session.projectRoot();
        Path source = verifiedAcceptedSnapshot(root, accepted);
        Path backup = Path.of("studio-run-logs", root.getFileName().toString(), "rollback-candidates",
                UUID.randomUUID().toString(), "source").toAbsolutePath().normalize();
        try {
            Files.createDirectories(backup);
            copyCandidate(root, backup);
            List<Path> savedFiles;
            try (Stream<Path> stream = Files.walk(source)) {
                List<Path> entries = stream.toList();
                rejectCandidateLinks(source, entries);
                savedFiles = entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> included(source.relativize(path))).toList();
            }
            Set<Path> savedPaths = savedFiles.stream().map(source::relativize)
                    .collect(java.util.stream.Collectors.toSet());
            List<Path> currentFiles;
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> entries = stream.toList();
                rejectCandidateLinks(root, entries);
                currentFiles = entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> included(root.relativize(path))).toList();
            }
            List<String> changed = new java.util.ArrayList<>();
            // 恢复受管文件集合；元数据、日志、缓存及构建产物均留在原处。
            for (Path file : currentFiles) {
                Path relative = root.relativize(file);
                if (!savedPaths.contains(relative)) {
                    Files.delete(file);
                    changed.add(relative.toString().replace('\\', '/'));
                }
            }
            for (Path file : savedFiles) {
                Path relative = source.relativize(file);
                Path destination = root.resolve(relative).normalize();
                if (!destination.startsWith(root)) throw new IOException("Restore path escapes project workspace");
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                changed.add(relative.toString().replace('\\', '/'));
            }
            if (!fingerprint(root).equals(accepted.sourceHash())) {
                throw new IOException("Restored candidate differs from accepted checkpoint");
            }
            changed.forEach(session::recordWrite);
            session.workflowData().codes = workspace.loadProjectFromDisk(root, message -> { });
            session.workflowData().verificationResult = VerificationResult.empty();
            session.workflowData().qualityPolicyResult = ProjectQualityPolicyResult.empty();
            session.workflowData().lastCheckpointHash = null;
            session.workflowData().lastCheckpointSnapshotPath = null;
            session.workflowData().lastCheckpointReportPath = null;
            session.workflowData().success = false;
            return new RestoreResult(accepted.id(), accepted.sourceHash(), backup.toString(),
                    List.copyOf(changed));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to restore accepted checkpoint; candidate backup is " + backup, e);
        }
    }

    /** 续接和回滚都先核对快照归属、真实路径及源码哈希。 */
    public Path verifiedAcceptedSnapshot(Path projectRoot, DevelopmentMilestoneState.Task accepted) {
        if (accepted == null || accepted.snapshotPath().isBlank() || accepted.sourceHash().isBlank()) {
            throw new IllegalStateException("Accepted milestone has no verified source snapshot");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path allowed = Path.of("studio-run-logs", root.getFileName().toString(), "checkpoints")
                .toAbsolutePath().normalize();
        Path source = Path.of(accepted.snapshotPath()).toAbsolutePath().normalize();
        try {
            if (!source.startsWith(allowed) || !source.getFileName().toString().equals("source")
                    || !Files.isDirectory(source) || Files.isSymbolicLink(source)
                    || !source.toRealPath().startsWith(allowed.toRealPath())
                    || !fingerprint(source).equals(accepted.sourceHash())) {
                throw new IllegalStateException("Accepted checkpoint path or hash is invalid");
            }
            return source;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to verify accepted checkpoint", e);
        }
    }

    /** 正式 Maven 验证只在断网容器运行；缓存缺项时由平台预取后重试同一命令。 */
    private SandboxExecutionResult runCheckpointCommand(Path source, String type, boolean build,
                                                         Path reportDirectory) throws IOException {
        if (!"SPRING_BOOT".equals(type) && !"PURE_JAVA_MAVEN".equals(type)) {
            // 原生 Java 工程也走同一隔离容器，避免回退到旧的联网宿主缓存挂载路径。
            String command = build
                    ? "find . -name '*.java' > sources.txt && javac -d . @sources.txt"
                    : "find . -name '*.java' > sources.txt && javac -d . @sources.txt "
                    + "&& java -cp . org.junit.runner.JUnitCore "
                    + "$(find . -name '*Test.class' | sed 's#^./##;s#\\.class$##;s#/#.#g')";
            return sandbox.runCheckpointMaven(source, command);
        }
        String command = build ? "mvn -o -B -ntp -DskipTests compile" : "mvn -o -B -ntp test";
        SandboxExecutionResult first = sandbox.runCheckpointMaven(source, command);
        if (!missingOfflineDependency(first)) return first;
        SandboxExecutionResult prefetch = sandbox.prefetchCheckpointDependencies(source);
        Files.writeString(reportDirectory.resolve("dependency-prefetch.log"), prefetch.output(),
                StandardCharsets.UTF_8);
        if (!prefetch.completedSuccessfully()) return prefetch;
        return sandbox.runCheckpointMaven(source, command);
    }

    /** 只对 Maven 明确报告的离线缓存缺项触发网络预取，源码或测试失败仍直接反馈开发者。 */
    private boolean missingOfflineDependency(SandboxExecutionResult execution) {
        if (execution == null || execution.completedSuccessfully() || execution.infrastructureError() != null) {
            return false;
        }
        String output = execution.output().toLowerCase(java.util.Locale.ROOT);
        return output.contains("has not been downloaded from it before")
                || output.contains("cannot access central") && output.contains("offline mode")
                || output.contains("offline mode") && output.contains("artifact")
                || output.contains("no plugin found for prefix") && output.contains("offline");
    }

    private void copyPriorLog(String priorReport, String name, Path currentDirectory) throws IOException {
        Path prior = priorReport == null ? null : Path.of(priorReport).getParent().resolve(name);
        if (prior != null && Files.isRegularFile(prior)) {
            Files.copy(prior, currentDirectory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(currentDirectory.resolve(name),
                    "Verification reused from previous passing checkpoint; original log unavailable.\n",
                    StandardCharsets.UTF_8);
        }
    }

    private List<String> concat(List<String> values, String extra) {
        java.util.ArrayList<String> combined = new java.util.ArrayList<>(values);
        combined.add(extra);
        return List.copyOf(combined);
    }

    private void copyCandidate(Path root, Path target) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> entries = stream.toList();
            rejectCandidateLinks(root, entries);
            files = entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> included(root.relativize(path))).sorted().toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("Candidate exceeds snapshot file limit");
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.size(file);
            if (bytes > MAX_BYTES) throw new IOException("Candidate exceeds snapshot size limit");
            Path destination = target.resolve(root.relativize(file)).normalize();
            if (!destination.startsWith(target)) throw new IOException("Snapshot path escapes source directory");
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String fingerprint(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> entries = stream.toList();
                rejectCandidateLinks(root, entries);
                for (Path file : entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> included(root.relativize(path))).sorted().toList()) {
                    digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(file));
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** 验证输入只接受真实文件，防止源码链接使快照与实际项目指向不同内容。 */
    private void rejectCandidateLinks(Path root, List<Path> entries) throws IOException {
        for (Path path : entries) {
            Path relative = root.relativize(path);
            if (Files.isSymbolicLink(path)
                    && (relative.toString().replace('\\', '/').equals("src") || included(relative))) {
                throw new IOException("Candidate source/config symlink is not allowed: " + relative);
            }
        }
    }

    private boolean included(Path relative) {
        String path = relative.toString().replace('\\', '/');
        return (path.startsWith("src/") || path.equals("pom.xml") || path.equals("README.md")
                || path.equals(".gitignore"))
                && !path.contains("/target/") && !path.contains("/node_modules/")
                && !path.endsWith(".class") && !path.endsWith(".jar");
    }

    private String shortOutput(String value) {
        String text = safe(value);
        if (text.length() <= 8_000) return text;
        return text.substring(0, 4_000) + "\n...[truncated; full output in checkpoint logs]...\n"
                + text.substring(text.length() - 4_000);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
