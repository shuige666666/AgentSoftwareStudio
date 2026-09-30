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

/** 管理当前 run 的业务里程碑；只有多个独立能力时才进入逐项交付协议。 */
@Service
public class DevelopmentMilestoneService {
    public static final String STATE_FILE = "development-state.json";
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
                DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION, tasks, 0, 0, "");
        persist(data);
    }

    /** 仅接受当前能力的完整验证快照，保存其源码哈希和报告入口。 */
    public DevelopmentMilestoneState.Task accept(SoftwareStudioWorkflowData data, String id,
                                                  WorkspaceCheckpointService.CheckpointResult checkpoint) {
        DevelopmentMilestoneState state = data.milestoneState;
        DevelopmentMilestoneState.Task current = state == null ? null : state.current();
        if (current == null || !current.id().equals(id) || checkpoint == null
                || !"PASSED".equals(checkpoint.status()) || !"progress".equals(checkpoint.scope())) {
            throw new IllegalArgumentException("Only the current passing progress checkpoint can accept a milestone");
        }
        List<DevelopmentMilestoneState.Task> updated = new ArrayList<>(state.tasks());
        int index = updated.indexOf(current);
        DevelopmentMilestoneState.Task accepted = new DevelopmentMilestoneState.Task(
                current.id(), current.goal(), current.acceptanceCriteria(), true,
                checkpoint.sourceHash(), checkpoint.snapshotPath(), checkpoint.reportPath());
        updated.set(index, accepted);
        data.currentSliceIndex = index + 1;
        data.milestoneState = new DevelopmentMilestoneState(state.schemaVersion(), updated,
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, "");
        data.acceptedSliceIds = updated.stream().filter(DevelopmentMilestoneState.Task::accepted)
                .map(DevelopmentMilestoneState.Task::id).toList();
        persist(data);
        return accepted;
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
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, "");
        data.acceptedSliceIds = List.of(accepted.id());
        data.currentSliceIndex = 1;
        persist(data);
    }

    /** 当前及此前接受的能力一起做契约回归，未来能力不阻断 progress。 */
    public ProjectContract contractThroughCurrent(SoftwareStudioWorkflowData data) {
        DevelopmentMilestoneState state = data.milestoneState;
        if (state == null || !state.active() || state.current() == null) return data.contract;
        List<DeliverySlice> slices = data.sliceDeliveryPlan.slices();
        int last = -1;
        for (int index = 0; index < slices.size(); index++) {
            if (slices.get(index).id().equals(state.current().id())) {
                last = index;
                break;
            }
        }
        if (last < 0) throw new IllegalStateException("Current milestone is absent from the plan");
        var endpoints = new ArrayList<com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract>();
        var views = new ArrayList<com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract>();
        var frontendCalls = new ArrayList<com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract>();
        for (int index = 0; index <= last; index++) {
            ProjectContract contract = slices.get(index).contract();
            endpoints.addAll(contract.endpoints());
            views.addAll(contract.views());
            frontendCalls.addAll(contract.frontendCalls());
        }
        return new ProjectContract(endpoints.stream().distinct().toList(),
                views.stream().distinct().toList(), frontendCalls.stream().distinct().toList(), List.of());
    }

    /** 模型与工具轮次使用同一全局预算，续接时按持久记录恢复已消耗值。 */
    public void persist(SoftwareStudioWorkflowData data) {
        if (data.projectPath == null) return;
        persistBehaviorCheck(data);
        if (data.milestoneState == null) return;
        DevelopmentMilestoneState state = data.milestoneState;
        data.milestoneState = new DevelopmentMilestoneState(state.schemaVersion(), state.tasks(),
                data.developmentModelTurnsUsed, data.developmentToolCallsUsed, state.lastFailure());
        Path root = Path.of(data.projectPath).toAbsolutePath().normalize()
                .resolve(".software-studio");
        try {
            Files.createDirectories(root);
            Path target = root.resolve(STATE_FILE);
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
                reason == null ? "" : reason);
        persist(data);
    }

    /** 从项目元数据加载最近一次完整写入的任务状态。 */
    public DevelopmentMilestoneState load(Path projectRoot) {
        Path path = projectRoot.resolve(".software-studio").resolve(STATE_FILE);
        try {
            DevelopmentMilestoneState state = mapper.readValue(path.toFile(), DevelopmentMilestoneState.class);
            if (state.schemaVersion() != DevelopmentMilestoneState.CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported development state version");
            }
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load development milestone state", e);
        }
    }
}
