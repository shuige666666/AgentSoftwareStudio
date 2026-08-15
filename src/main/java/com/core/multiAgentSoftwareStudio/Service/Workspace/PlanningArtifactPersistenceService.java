package com.core.multiAgentSoftwareStudio.Service.Workspace;

import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 将需求、架构、契约和生成计划持久化到项目元数据目录，供人工审查和后续恢复使用。
 */
@Service
public class PlanningArtifactPersistenceService {

    public static final String METADATA_DIRECTORY = ".software-studio";
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public enum PlanningStage {
        REQUIREMENT,
        ARCHITECTURE,
        CONTRACT,
        SLICE_PLAN,
        GENERATION_PLAN
    }

    public PlanningArtifactPersistenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将当前已经产生的规划对象分别写入 JSON 和可读 Markdown；尚未产生的阶段保持缺省。
     */
    public void persistAvailable(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        persistStage(data, PlanningStage.GENERATION_PLAN, logger);
    }

    /**
     * 按真实完成阶段写入检查点，避免将状态对象的默认空计划误认为已产生结果。
     */
    public void persistStage(
            SoftwareStudioWorkflowData data,
            PlanningStage stage,
            Consumer<String> logger) {
        if (data == null || data.projectPath == null || data.projectPath.isBlank()) {
            return;
        }
        Consumer<String> safeLogger = logger == null ? message -> { } : logger;
        Path metadataRoot = Path.of(data.projectPath).toAbsolutePath().normalize().resolve(METADATA_DIRECTORY);
        try {
            Files.createDirectories(metadataRoot);
            writeAtomic(metadataRoot.resolve("user-request.md"), markdownText("User Request", data.userRequest));
            writeArtifact(metadataRoot, "prd", "Product Requirements", data.prd);
            if (stage.ordinal() >= PlanningStage.ARCHITECTURE.ordinal()) {
                writeArtifact(metadataRoot, "architecture", "Project Architecture", data.structure);
            }
            if (stage.ordinal() >= PlanningStage.CONTRACT.ordinal()) {
                // 契约节点会把补充文件合并回架构，因此同时覆盖架构检查点。
                writeArtifact(metadataRoot, "architecture", "Project Architecture", data.structure);
                writeArtifact(metadataRoot, "contract", "Project Contract", data.contract);
            }
            if (stage.ordinal() >= PlanningStage.SLICE_PLAN.ordinal()) {
                writeJsonIfPresent(metadataRoot.resolve("slice-plan.json"), data.sliceDeliveryPlan);
            }
            if (stage.ordinal() >= PlanningStage.GENERATION_PLAN.ordinal()) {
                writeJsonIfPresent(metadataRoot.resolve("generation-plan.json"), data.generationPlan);
            }
            writeManifest(metadataRoot);
            safeLogger.accept("   Planning artifacts persisted to " + metadataRoot + ".");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist planning artifacts", e);
        }
    }

    /**
     * 在工作流返回前保存最终运行摘要，避免基准报告成为唯一诊断入口。
     */
    public void persistRunSummary(
            SoftwareStudioWorkflowData data,
            WorkflowRunSummary summary,
            Consumer<String> logger) {
        if (data == null || data.projectPath == null || data.projectPath.isBlank() || summary == null) {
            return;
        }
        Consumer<String> safeLogger = logger == null ? message -> { } : logger;
        Path metadataRoot = Path.of(data.projectPath).toAbsolutePath().normalize().resolve(METADATA_DIRECTORY);
        try {
            Files.createDirectories(metadataRoot);
            writeJsonIfPresent(metadataRoot.resolve("project-profile.json"), data.projectProfile);
            writeJson(metadataRoot.resolve("run-summary.json"), summary);
            writeManifest(metadataRoot);
            safeLogger.accept("   Workflow run summary persisted to " + metadataRoot.resolve("run-summary.json") + ".");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist workflow run summary", e);
        }
    }

    private void writeArtifact(Path root, String basename, String title, Object value) throws IOException {
        if (value == null) {
            return;
        }
        String json = prettyJson(value);
        writeAtomic(root.resolve(basename + ".json"), json + System.lineSeparator());
        writeAtomic(root.resolve(basename + ".md"), markdownJson(title, json));
    }

    private void writeJsonIfPresent(Path path, Object value) throws IOException {
        if (value != null) {
            writeJson(path, value);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        writeAtomic(path, prettyJson(value) + System.lineSeparator());
    }

    private String prettyJson(Object value) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private String markdownText(String title, String content) {
        return "# " + title + System.lineSeparator() + System.lineSeparator()
                + (content == null ? "" : content.strip()) + System.lineSeparator();
    }

    private String markdownJson(String title, String json) {
        return "# " + title + System.lineSeparator() + System.lineSeparator()
                + "```json" + System.lineSeparator()
                + json + System.lineSeparator()
                + "```" + System.lineSeparator();
    }

    private void writeManifest(Path metadataRoot) throws IOException {
        List<String> artifacts = new ArrayList<>();
        try (var stream = Files.list(metadataRoot)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("manifest.json"))
                    .sorted()
                    .forEach(artifacts::add);
        }
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("updatedAt", OffsetDateTime.now().toString());
        ArrayNode files = manifest.putArray("artifacts");
        artifacts.forEach(files::add);
        writeJson(metadataRoot.resolve("manifest.json"), manifest);
    }

    /**
     * 先写临时文件再替换目标，防止进程异常时留下半个 JSON 或 Markdown。
     */
    private void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content == null ? "" : content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
