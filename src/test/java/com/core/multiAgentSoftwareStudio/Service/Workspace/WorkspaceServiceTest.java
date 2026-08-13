package com.core.multiAgentSoftwareStudio.Service.Workspace;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceServiceTest {

    /**
     * 磁盘写入层必须拒绝空白整文件修复，作为 Agent 编排之外的最后一道数据保护。
     */
    @Test
    void doesNotOverwriteExistingFileWithBlankFix(@TempDir Path projectDir) throws Exception {
        Path source = projectDir.resolve("src/main/java/com/example/CommentService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example; class CommentService {}", StandardCharsets.UTF_8);
        WorkspaceService service = new WorkspaceService(new SourceCodePathService());

        service.applyFixesToDisk(projectDir, List.of(new CodeFix(
                "src/main/java/com/example/CommentService.java", "blank", "  ")), message -> { });

        assertEquals("package com.example; class CommentService {}",
                Files.readString(source, StandardCharsets.UTF_8));
    }
}
