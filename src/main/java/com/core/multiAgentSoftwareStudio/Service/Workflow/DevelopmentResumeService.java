package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningDesign;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 从本 run 保留的规划资料、任务状态和磁盘候选恢复开发，不重放模型消息。 */
@Service
public class DevelopmentResumeService {
    private final ObjectMapper mapper;
    private final DevelopmentMilestoneService milestones;
    private final WorkspaceCheckpointService checkpoints;
    private final ProjectProfileService profiles;
    private final WorkspaceService workspace;

    public DevelopmentResumeService(ObjectMapper mapper, DevelopmentMilestoneService milestones,
                                    WorkspaceCheckpointService checkpoints, ProjectProfileService profiles,
                                    WorkspaceService workspace) {
        this.mapper = mapper;
        this.milestones = milestones;
        this.checkpoints = checkpoints;
        this.profiles = profiles;
        this.workspace = workspace;
    }

    /** 项目 ID 只能定位已生成项目根目录，拒绝路径越界和链接跳转。 */
    public SoftwareStudioWorkflowData load(String projectId) {
        if (projectId == null || !projectId.matches("[\\p{L}\\p{N}_-]{1,120}")) {
            throw new IllegalArgumentException("Invalid generated project ID");
        }
        try {
            Path base = Path.of("ai_generated_projects").toAbsolutePath().normalize().toRealPath();
            Path candidate = base.resolve(projectId).normalize();
            if (!candidate.getParent().equals(base) || Files.isSymbolicLink(candidate)
                    || !Files.isDirectory(candidate)) {
                throw new IllegalArgumentException("Generated project does not exist");
            }
            Path root = candidate.toRealPath();
            if (!root.getParent().equals(base)) {
                throw new IllegalArgumentException("Generated project path escapes workspace");
            }
            return loadResolved(root);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load generated project for development resume", e);
        }
    }

    /** 在已校验的项目根目录中重建最小续接状态；源码始终从当前磁盘读取。 */
    SoftwareStudioWorkflowData loadResolved(Path root) throws IOException {
            Path metadata = root.resolve(".software-studio");
            if (Files.isSymbolicLink(metadata)) {
                throw new IllegalArgumentException("Project metadata must not be a link");
            }
            String request = Files.readString(metadata.resolve("user-request.md"), StandardCharsets.UTF_8)
                    .replaceFirst("(?s)^# User Request\\R\\R", "").strip();
            SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData(request);
            data.projectPath = root.toString();
            if (Files.isRegularFile(metadata.resolve("behavior-check.json"))) {
                data.behaviorCheckId = read(metadata, "behavior-check.json", com.fasterxml.jackson.databind.JsonNode.class)
                        .path("checkId").asText();
            }
            data.prd = read(metadata, "prd.json", PrdDocument.class);
            data.structure = read(metadata, "architecture.json", ProjectStructure.class);
            data.contract = read(metadata, "contract.json", ProjectContract.class);
            data.sliceDeliveryPlan = read(metadata, "slice-plan.json", SliceDeliveryPlan.class);
            data.generationPlan = read(metadata, "generation-plan.json", GenerationPlan.class);
            if (Files.isRegularFile(metadata.resolve("overall-design.json"))) {
                data.planningDesign = read(metadata, "overall-design.json", PlanningDesign.class);
            }
            data.projectProfile = Files.isRegularFile(metadata.resolve("project-profile.json"))
                    ? read(metadata, "project-profile.json", ProjectProfile.class)
                    : profiles.resolve(data.structure);
            data.milestoneState = milestones.load(root);
            List<String> plannedIds = data.sliceDeliveryPlan.slices().stream().map(slice -> slice.id()).toList();
            List<String> stateIds = data.milestoneState.tasks().stream()
                    .map(DevelopmentMilestoneState.Task::id).toList();
            if (!plannedIds.equals(stateIds)) {
                throw new IllegalStateException("Saved milestone state does not match the persisted plan");
            }
            DevelopmentMilestoneState.Task accepted = data.milestoneState.lastAccepted();
            if (accepted != null) {
                checkpoints.verifiedAcceptedSnapshot(root, accepted);
                data.lastCheckpointHash = accepted.sourceHash();
                data.lastCheckpointSnapshotPath = accepted.snapshotPath();
                data.lastCheckpointReportPath = accepted.checkpointReportPath();
            }
            data.developmentModelTurnsUsed = data.milestoneState.modelTurnsUsed();
            data.developmentToolCallsUsed = data.milestoneState.toolCallsUsed();
            data.acceptedSliceIds = data.milestoneState.tasks().stream()
                    .filter(DevelopmentMilestoneState.Task::accepted)
                    .map(DevelopmentMilestoneState.Task::id).toList();
            data.currentSliceIndex = data.acceptedSliceIds.size();
            data.codes = workspace.loadProjectFromDisk(root, message -> { });
            data.repairBudget = null;
            return data;
    }

    private <T> T read(Path metadata, String name, Class<T> type) throws IOException {
        Path file = metadata.resolve(name);
        if (Files.isSymbolicLink(file)) throw new IOException("Linked planning artifact is not allowed: " + name);
        return mapper.readValue(file.toFile(), type);
    }
}
