package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePatchServiceTest {
    @TempDir Path root;
    private final WorkspacePatchService service = new WorkspacePatchService();

    /**
     * 一个补丁可以同时更新业务类、创建测试，并按磁盘实际写入记录版本。
     */
    @Test
    void appliesMultipleFilesAndNewTest() throws Exception {
        Path source = root.resolve("src/main/java/demo/Counter.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Counter {\n    int value = 1;\n}\n", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session();
        String patch = """
                *** Begin Patch
                *** Update File: src/main/java/demo/Counter.java
                @@
                -    int value = 1;
                +    int value = 2;
                *** Add File: src/test/java/demo/CounterTest.java
                +class CounterTest { }
                *** End Patch
                """;

        var result = service.apply(patch, session);

        assertTrue(result.ok(), result.message());
        assertEquals(2, result.applied().size());
        assertEquals(2, session.writeVersion());
        assertTrue(Files.readString(source).contains("value = 2"));
        assertTrue(Files.readString(root.resolve("src/test/java/demo/CounterTest.java"))
                .contains("class CounterTest"));
    }

    /**
     * 匹配失败前不能写入其他文件；越界路径不能通过宿主补丁触碰。
     */
    @Test
    void acceptsStandardUnifiedDiffForMultipleFiles() throws Exception {
        Path source = root.resolve("src/main/java/demo/Counter.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Counter {\n    int value = 1;\n}\n", StandardCharsets.UTF_8);
        var result = service.apply("""
                --- a/src/main/java/demo/Counter.java
                +++ b/src/main/java/demo/Counter.java
                @@ -1,3 +1,3 @@
                 class Counter {
                -    int value = 1;
                +    int value = 2;
                 }
                --- /dev/null
                +++ src/test/java/demo/CounterTest.java
                @@ -0,0 +1,1 @@
                +class CounterTest { }
                """, session());
        assertTrue(result.ok(), result.message());
        assertEquals(2, result.applied().size());
        assertTrue(Files.readString(source).contains("value = 2"));
        assertTrue(Files.exists(root.resolve("src/test/java/demo/CounterTest.java")));
    }

    @Test
    void treatsZeroOldRangeAsNewFileEvenWithOrdinaryDiffPath() throws Exception {
        var result = service.apply("""
                --- a/src/main/java/demo/New.java
                +++ b/src/main/java/demo/New.java
                @@ -0,0 +1,1 @@
                +class New { }
                """, session());
        assertTrue(result.ok(), result.message());
        assertTrue(Files.exists(root.resolve("src/main/java/demo/New.java")));
    }

    @Test
    void acceptsOmittedPlusOnAddedFileContinuation() throws Exception {
        var result = service.apply("""
                --- /dev/null
                +++ b/README.md
                @@ -0,0 +1,3 @@
                +# Demo

                Text without a diff prefix
                """, session());
        assertTrue(result.ok(), result.message());
        assertTrue(Files.readString(root.resolve("README.md"))
                .contains("\n\nText without a diff prefix"));
    }

    @Test
    void reportsMissingContextWithoutPartialWriteAndRejectsEscape() throws Exception {
        Path source = root.resolve("src/main/java/demo/Counter.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Counter {}\n", StandardCharsets.UTF_8);
        var session = session();
        var mismatch = service.apply("""
                *** Begin Patch
                *** Add File: src/test/java/demo/CounterTest.java
                +class CounterTest { }
                *** Update File: src/main/java/demo/Counter.java
                @@
                -class Missing {}
                +class Counter { }
                *** End Patch
                """, session);
        assertFalse(mismatch.ok());
        assertEquals("PATCH_CONTEXT_NOT_FOUND", mismatch.error());
        assertFalse(Files.exists(root.resolve("src/test/java/demo/CounterTest.java")));

        var escape = service.apply("""
                *** Begin Patch
                *** Add File: ../outside.java
                +class Outside {}
                *** End Patch
                """, session);
        assertEquals("PATH_OUT_OF_SCOPE", escape.error());
        assertEquals(0, session.writeVersion());
    }

    private WorkspaceAgentSession session() {
        return new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }
}
