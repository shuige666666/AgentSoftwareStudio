package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentResumeService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 用当前平台复验保存的源码快照，不续接模型开发、不改写原候选和原报告。 */
public final class SavedCandidateCheckpoint {
    /** 复制快照后运行完整门禁；工作目录和新报告必须尚不存在，防止覆盖实验取证。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SavedCandidateCheckpoint <project-id> <snapshot> <new-work-copy> <new-report>");
        }
        Path snapshot = Path.of(args[1]).toAbsolutePath().normalize();
        Path work = Path.of(args[2]).toAbsolutePath().normalize();
        Path report = Path.of(args[3]).toAbsolutePath().normalize();
        if (!Files.isDirectory(snapshot) || Files.exists(work) || Files.exists(report) || work.startsWith(snapshot)) {
            throw new IllegalArgumentException("Use an existing snapshot and separate new destinations");
        }
        Files.createDirectories(work.getParent());
        Files.createDirectory(work);
        try (var paths = Files.walk(snapshot)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path target = work.resolve(snapshot.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var app = new SpringApplication(SoftwareStudioApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off")) {
            var registry = context.getBean(WorkspaceToolRegistry.class);
            var original = registry.snapshotWorkspace(snapshot);
            var data = context.getBean(DevelopmentResumeService.class).load(args[0]);
            data.projectPath = work.toString();
            data.codes = context.getBean(WorkspaceService.class).loadProjectFromDisk(work, System.out::println);
            var result = context.getBean(WorkspaceCheckpointService.class).verifyCandidate(data, System.out::println);
            if (!original.equals(registry.snapshotWorkspace(snapshot))) {
                throw new IllegalStateException("Saved source snapshot changed during verification");
            }
            Files.createDirectories(report.getParent());
            context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                    "originalProject", args[0], "snapshotUnchanged", true,
                    "sourceSnapshot", snapshot.toString(), "workCopy", work.toString(), "checkpoint", result));
            System.out.println("SAVED_CHECKPOINT_STATUS=" + result.status());
        }
    }
}
