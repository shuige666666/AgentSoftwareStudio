package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 管理当前 run 的任务建议和已验证能力；完整项目验收不依赖逐项接受数量。 */
@Service
public class DevelopmentMilestoneService {
    public static final String STATE_FILE = "development-state.json";
    public static final String LEGACY_STATE_BACKUP = "development-state.pre-advisory-retirement.json";
    private final ObjectMapper mapper;

    public DevelopmentMilestoneService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 从已有业务切片建立任务清单，不新增模型规划调用或技术层批次。 */
    public void initialize(SoftwareStudioWorkflowData data) {
        if (data.milestoneState != null) return;
        List<DevelopmentMilestoneState.Task> tasks = data.sliceDeliveryPlan == null ? List.of()
                : data.sliceDeliveryPlan.slices().stream()
                .map(slice -> new DevelopmentMilestoneState.Task(slice.id(), slice.name(),
                        slice.acceptanceCriteria(), false, "", "", ""))
                .toList();
        data.milestoneState = new DevelopmentMilestoneState(
                DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION, tasks, 0, 0, "", "");
        persist(data);
    }

    /** 接受已实现的单个能力，保存通过快照的源码哈希和报告入口。 */
    public DevelopmentMilestoneState.Task accept(SoftwareStudioWorkflowData data, String id,
                                                  WorkspaceCheckpointService.CheckpointResult checkpoint) {
        return acceptSelected(data, List.of(id), checkpoint).getFirst();
    }

    /** 建议式可合并或调整任务顺序，但接受记录仍必须来自对应契约的通过快照。 */
    public List<DevelopmentMilestoneState.Task> acceptSelected(SoftwareStudioWorkflowData data, List<String> ids,
                                                  WorkspaceCheckpointService.CheckpointResult checkpoint) {
        DevelopmentMilestoneState state = data.milestoneState;
        DevelopmentMilestoneState.Task current = state == null ? null : state.current();
        validateSelection(data, ids);
        if (current == null || checkpoint == null
                || !"PASSED".equals(checkpoint.status()) || !"progress".equals(checkpoint.scope())) {
            throw new IllegalArgumentException("Only a passing progress checkpoint can accept milestones");
        }
        List<DevelopmentMilestoneState.Task> updated = new ArrayList<>(state.tasks());
        List<DevelopmentMilestoneState.Task> accepted = new ArrayList<>();
        for (String id : ids) {
            for (int index = 0; index < updated.size(); index++) {
                var task = updated.get(index);
                if (!task.id().equals(id)) continue;
                var verified = new DevelopmentMilestoneState.Task(task.id(), task.goal(), task.acceptanceCriteria(),
                        true, checkpoint.sourceHash(), checkpoint.snapshotPath(), checkpoint.reportPath());
                updated.set(index, verified);
                accepted.add(verified);
            }
        }
        data.currentSliceIndex = (int) updated.stream().filter(DevelopmentMilestoneState.Task::accepted).count();
        data.milestoneState = new DevelopmentMilestoneState(DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION, updated,
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, "", ids.getLast());
        data.acceptedSliceIds = updated.stream().filter(DevelopmentMilestoneState.Task::accepted)
                .map(DevelopmentMilestoneState.Task::id).toList();
        persist(data);
        return accepted;
    }

    /** 在验证前检查所选任务，拒绝未知、重复或已经接受的能力。 */
    public void validateSelection(SoftwareStudioWorkflowData data, List<String> ids) {
        var state = data.milestoneState;
        if (state == null || ids.isEmpty() || ids.stream().distinct().count() != ids.size()
                || ids.stream().anyMatch(id -> state.tasks().stream().noneMatch(task -> task.id().equals(id) && !task.accepted()))) {
            throw new IllegalArgumentException("Select known pending milestone IDs");
        }
    }

    /** 选择的任务连同已接受能力一起回归；未选择的未来能力不阻断 progress。 */
    public ProjectContract contractForSelection(SoftwareStudioWorkflowData data, List<String> ids) {
        validateSelection(data, ids);
        var selected = new java.util.HashSet<>(ids);
        data.milestoneState.tasks().stream().filter(DevelopmentMilestoneState.Task::accepted)
                .forEach(task -> selected.add(task.id()));
        var contracts = data.sliceDeliveryPlan.slices().stream().filter(slice -> selected.contains(slice.id()))
                .map(DeliverySlice::contract).toList();
        return new ProjectContract(contracts.stream().flatMap(contract -> contract.endpoints().stream()).distinct().toList(),
                contracts.stream().flatMap(contract -> contract.views().stream()).distinct().toList(),
                contracts.stream().flatMap(contract -> contract.frontendCalls().stream()).distinct().toList(),
                contracts.stream().flatMap(contract -> contract.additionalFiles().stream()).distinct().toList());
    }

    /** 单任务项目直接由通过的 final checkpoint 记录交付版本，不增加 progress 门禁。 */
    public void acceptSingleFinal(SoftwareStudioWorkflowData data) {
        DevelopmentMilestoneState state = data.milestoneState;
        if (state == null || state.active() || state.tasks().size() != 1 || !data.success
                || data.lastCheckpointHash == null || data.lastCheckpointSnapshotPath == null
                || data.lastCheckpointReportPath == null) return;
        DevelopmentMilestoneState.Task original = state.tasks().getFirst();
        DevelopmentMilestoneState.Task accepted = new DevelopmentMilestoneState.Task(
                original.id(), original.goal(), original.acceptanceCriteria(), true,
                data.lastCheckpointHash, data.lastCheckpointSnapshotPath, data.lastCheckpointReportPath);
        data.milestoneState = new DevelopmentMilestoneState(state.schemaVersion(), List.of(accepted),
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, "", accepted.id());
        data.acceptedSliceIds = List.of(accepted.id());
        data.currentSliceIndex = 1;
        persist(data);
    }

    /** 模型与工具轮次使用同一全局预算，续接时按持久记录恢复已消耗值。 */
    public void persist(SoftwareStudioWorkflowData data) {
        if (data.projectPath == null) return;
        persistBehaviorCheck(data);
        if (data.milestoneState == null) return;
        DevelopmentMilestoneState state = data.milestoneState;
        data.milestoneState = new DevelopmentMilestoneState(DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION, state.tasks(),
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, state.lastFailure(), state.latestAcceptedId());
        Path root = Path.of(data.projectPath).toAbsolutePath().normalize()
                .resolve(".software-studio");
        try {
            if (Files.isSymbolicLink(root)) throw new IOException("Linked metadata is not allowed");
            Files.createDirectories(root);
            Path target = root.resolve(STATE_FILE);
            preserveLegacyState(target);
            Path temporary = Files.createTempFile(root, "development-state-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), data.milestoneState);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist development milestone state", e);
        }
    }

    /** 平台保存独立检查的绑定，续接沿用同一判卷，不让候选工具改写检查标识。 */
    private void persistBehaviorCheck(SoftwareStudioWorkflowData data) {
        if (data.behaviorCheckId == null || data.behaviorCheckId.isBlank()) return;
        if (!com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkBehaviorEvaluator.isBehaviorCase(data.behaviorCheckId)) {
            throw new IllegalArgumentException("Unknown public behavior check: " + data.behaviorCheckId);
        }
        try {
            Path metadata = Path.of(data.projectPath).resolve(".software-studio");
            if (Files.isSymbolicLink(metadata)) throw new IOException("Linked metadata is not allowed");
            Files.createDirectories(metadata);
            Path target = metadata.resolve("behavior-check.json");
            if (Files.isSymbolicLink(target)) throw new IOException("Linked check binding is not allowed");
            String content = mapper.writeValueAsString(java.util.Map.of("checkId", data.behaviorCheckId));
            if (!Files.exists(target) || !Files.readString(target).equals(content)) {
                Files.writeString(target, content, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist public behavior check binding", e);
        }
    }

    /** 保存本次中断或额度停止的原因，供续接会话先定位当前候选。 */
    public void recordFailure(SoftwareStudioWorkflowData data, String reason) {
        if (data.milestoneState == null) return;
        DevelopmentMilestoneState state = data.milestoneState;
        data.milestoneState = new DevelopmentMilestoneState(state.schemaVersion(), state.tasks(),
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed,
                reason == null ? "" : reason, state.latestAcceptedId());
        persist(data);
    }

    /** 只读加载旧状态；后续保存时迁移到建议式结构，不改变预算或接受事实。 */
    public DevelopmentMilestoneState load(Path projectRoot) {
        Path path = projectRoot.resolve(".software-studio").resolve(STATE_FILE);
        try {
            if (Files.isSymbolicLink(path)) throw new IOException("Linked development state is not allowed");
            DevelopmentMilestoneState state = mapper.readValue(path.toFile(), DevelopmentMilestoneState.class);
            if (state.schemaVersion() < 1 || state.schemaVersion() > DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported development state version");
            }
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load development milestone state", e);
        }
    }

    /** 首次覆盖旧状态前保留原始字节，旧字段只用于兼容读取，不再决定开发控制方式。 */
    private void preserveLegacyState(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Linked development state is not allowed");
        if (!Files.exists(target)) return;
        var saved = mapper.readTree(target.toFile());
        if (saved.path("schemaVersion").asInt() >= DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION) return;
        Path backup = target.resolveSibling(LEGACY_STATE_BACKUP);
        if (Files.isSymbolicLink(backup)) throw new IOException("Linked development state backup is not allowed");
        if (!Files.exists(backup)) Files.copy(target, backup);
    }
}
