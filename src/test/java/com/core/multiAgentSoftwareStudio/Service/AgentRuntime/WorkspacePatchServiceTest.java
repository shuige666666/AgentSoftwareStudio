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

    /** 保留统一补丁中的空白上下文行，避免真实多文件编辑误报上下文不匹配。 */
    @Test
    void preservesBlankContextLinesInUnifiedDiff() throws Exception {
        Path source = root.resolve("src/main/java/demo/GlobalExceptionHandler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;

                import org.springframework.web.bind.annotation.ResponseStatus;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                public class GlobalExceptionHandler {

                    @ExceptionHandler(IllegalArgumentException.class)
                }
                """, StandardCharsets.UTF_8);

        var result = service.apply("""
                --- a/src/main/java/demo/GlobalExceptionHandler.java
                +++ b/src/main/java/demo/GlobalExceptionHandler.java
                @@ -3,8 +3,8 @@
                 import org.springframework.web.bind.annotation.ResponseStatus;
                -import org.springframework.web.bind.annotation.RestControllerAdvice;
                +import org.springframework.web.bind.annotation.ControllerAdvice;
                §
                -@RestControllerAdvice
                +@ControllerAdvice
                 public class GlobalExceptionHandler {
                §
                     @ExceptionHandler(IllegalArgumentException.class)
                """.replace('§', ' '), session());

        assertTrue(result.ok(), result.message());
        assertTrue(Files.readString(source, StandardCharsets.UTF_8).contains("@ControllerAdvice"));
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

    /** 已有构建配置使补丁上下文失配时，反馈指出实际分歧而不猜测并写入。 */
    @Test
    void reportsNearestMismatchForExistingPomSection() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    </dependency>
                  </dependencies>
                  <build>
                  </build>
                </project>
                """, StandardCharsets.UTF_8);

        var result = service.apply("""
                *** Begin Patch
                *** Update File: pom.xml
                @@
                     </dependency>
                   </dependencies>
                 </project>
                +<extra/>
                *** End Patch
                """, session());

        assertEquals("PATCH_CONTEXT_NOT_FOUND", result.error());
        assertTrue(result.message().contains("expected `</project>`"), result.message());
        assertTrue(result.message().contains("found `  <build>`"), result.message());
        assertFalse(Files.readString(pom).contains("<extra/>"));
    }

    /** 同一文件的两个更新块顺序预检并一次落盘，后块失败时保持原文件。 */
    @Test
    void appliesRepeatedUpdateBlocksAtomically() throws Exception {
        Path source = root.resolve("src/main/java/demo/Counter.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Counter {\n    int first = 1;\n    int second = 2;\n}\n",
                StandardCharsets.UTF_8);
        String patch = """
                *** Begin Patch
                *** Update File: src/main/java/demo/Counter.java
                @@
                -    int first = 1;
                +    int first = 10;
                *** Update File: src/main/java/demo/Counter.java
                @@
                -    int second = 2;
                +    int second = 20;
                *** End Patch
                """;
        WorkspaceAgentSession session = session();

        var result = service.apply(patch, session);

        assertTrue(result.ok(), result.message());
        assertEquals(1, result.applied().size());
        assertEquals(1, session.writeVersion());
        assertTrue(Files.readString(source).contains("first = 10;"));
        assertTrue(Files.readString(source).contains("second = 20;"));

        String original = "class Counter {\n    int first = 1;\n    int second = 2;\n}\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        var invalid = service.apply(patch.replace("int second = 2", "int second = 999"), session);
        assertEquals("PATCH_CONTEXT_NOT_FOUND", invalid.error());
        assertEquals(1, session.writeVersion());
        assertEquals(original, Files.readString(source));
    }

    private WorkspaceAgentSession session() {
        return new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }
}
