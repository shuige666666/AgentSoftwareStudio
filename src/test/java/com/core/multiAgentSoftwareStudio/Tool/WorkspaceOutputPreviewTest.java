package com.core.multiAgentSoftwareStudio.Tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOutputPreviewTest {

    /** 大量测试日志后出现的失败摘要仍须进入 Agent 可见预览。 */
    @Test
    void retainsFailureAtEndOfLongOutput() {
        var preview = new DockerSandboxService.WorkspaceOutputPreview();
        preview.append("[INFO] Maven started\n");
        for (int index = 0; index < 120; index++) {
            preview.append("[INFO] test output block " + index + " " + "x".repeat(1000) + "\n");
        }
        preview.append("[ERROR] Tests run: 26, Failures: 2, Errors: 9\n");
        preview.append("[ERROR] ArticleControllerTest.shouldUpdate: expected 302 but was 200\n");

        String result = preview.snapshot();
        assertTrue(result.contains("[INFO] Maven started"));
        assertTrue(result.contains("[ERROR] Tests run: 26, Failures: 2, Errors: 9"));
        assertTrue(result.contains("expected 302 but was 200"));
        assertFalse(result.contains("test output block 60"));
        assertTrue(result.length() < 32_000);
    }

    /** 短命令输出保持原文，避免人为插入截断标记。 */
    @Test
    void leavesShortOutputIntact() {
        var preview = new DockerSandboxService.WorkspaceOutputPreview();
        preview.append("compile ");
        preview.append("passed");

        assertEquals("compile passed", preview.snapshot());
    }
}
