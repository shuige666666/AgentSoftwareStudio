package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePatchServiceTest {
    /** 真实轨迹中的 Add+空 @@ 是无歧义新增，保留正文全部空行并继续预检后续文件。 */
    @Test
    void acceptsABareHunkMarkerOnlyAtTheStartOfAnAddedFile() throws Exception {
        var result = service.apply("""
                *** Begin Patch
                *** Add File: src/main/java/demo/Value.java
                @@
                +package demo;
                +
                +class Value {}
                *** Add File: README.md
                +Ready
                *** End Patch
                """, session());
        assertTrue(result.ok(), result.message());
        assertEquals("package demo;\n\nclass Value {}\n",
                Files.readString(root.resolve("src/main/java/demo/Value.java")));
        assertEquals(2, result.applied().size());
    }

    /** 不把有旧范围的 hunk 或无前缀正文当成新增内容，后续错误仍阻止前面的文件落盘。 */
    @Test
    void rejectsAmbiguousAddedFileBodiesBeforeWritingAnyFiles() {
        for (String invalid : List.of("@@ -1,2 +1,2 @@\n+class Bad {}", "@@\npackage demo;", "@@\n@@\n+class Bad {}")) {
            var session = session();
            var result = service.apply("*** Begin Patch\n*** Add File: README.md\n+Ready\n"
                    + "*** Add File: src/main/java/demo/Bad.java\n" + invalid + "\n*** End Patch", session);
            assertFalse(result.ok());
            assertTrue(result.message().contains("Add File block every source line"));
            assertFalse(Files.exists(root.resolve("README.md")));
            assertEquals(0, session.writeVersion());
        }
    }

    @TempDir Path root;
    private final WorkspacePatchService service = new WorkspacePatchService();

    /** 提前结束多文件补丁必须全量拒绝，并告知下一次应如何组织包络。 */
    @Test
    void explainsPrematureEndWithoutWritingFirstFile() throws Exception {
        Path source = root.resolve("src/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "before\n", StandardCharsets.UTF_8);
        var result = service.apply("""
                *** Begin Patch
                *** Update File: src/A.java
                @@
                -before
                +after
                *** End Patch
                *** Add File: src/B.java
                +second
                *** End Patch
                """, session());
        assertEquals("PATCH_FORMAT", result.error());
        assertTrue(result.message().contains("No files were written"));
        assertTrue(result.message().contains("Do not end each file separately"));
        assertEquals("before\n", Files.readString(source));
        assertFalse(Files.exists(root.resolve("src/B.java")));
    }

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

    /** 缩减真实失败：声明整段替换却只提供插入行，提示风险但不替模型猜测删除内容。 */
    @Test
    void warnsOnReplacementWrittenAsInsertionAndAllowsExplicitCorrection() throws Exception {
        Path source = root.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\n\npublic class Handler { int value = 1; }\n", StandardCharsets.UTF_8);
        var session = session();
        var inserted = service.apply("""
                --- a/src/main/java/demo/Handler.java
                +++ b/src/main/java/demo/Handler.java
                @@ -1,3 +1,3 @@
                 package demo;
                §
                +public class Handler { int value = 2; }
                """.replace('§', ' '), session);

        assertTrue(inserted.ok(), inserted.message());
        assertTrue(inserted.message().contains("declared old/new=3/3, actual=2/3"), inserted.message());
        assertTrue(inserted.message().contains("replacement must include '-'"));
        assertTrue(Files.readString(source).contains("value = 1"));
        // 工具没有语义删除权限：落盘成功仍可能编译失败，需要显式修正补丁。
        assertFalse(compiles(source));
        var corrected = service.apply("""
                *** Begin Patch
                *** Update File: src/main/java/demo/Handler.java
                @@
                -public class Handler { int value = 1; }
                *** End Patch
                """, session);
        assertTrue(corrected.ok(), corrected.message());
        assertTrue(compiles(source));
        assertEquals(2, session.writeVersion());
    }

    /** 模型行数估算失准不妨碍精确上下文替换，也不凭声明行号定位源码。 */
    @Test
    void appliesCorrectReplacementDespiteWrongRangeCounts() throws Exception {
        Path source = root.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\n\npublic class Handler { int value = 1; }\n", StandardCharsets.UTF_8);
        var result = service.apply("""
                --- a/src/main/java/demo/Handler.java
                +++ b/src/main/java/demo/Handler.java
                @@ -99,20 +99,21 @@
                -public class Handler { int value = 1; }
                +public class Handler { int value = 2; }
                """, session());

        assertTrue(result.ok(), result.message());
        assertTrue(result.message().contains("declared old/new=20/21, actual=1/1"));
        assertFalse(Files.readString(source).contains("value = 1"));
        assertTrue(compiles(source));
    }

    /** 每段独立计算上下文；省略数量表示一行，声明行号变化不产生误提示。 */
    @Test
    void preservesValidMultipleHunksWithImplicitCounts() throws Exception {
        Path source = root.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\npublic class Handler {\nint first = 1;\nint second = 2;\n}\n", StandardCharsets.UTF_8);
        var result = service.apply("""
                --- a/src/main/java/demo/Handler.java
                +++ b/src/main/java/demo/Handler.java
                @@ -99 +99 @@
                -int first = 1;
                +int first = 10;
                @@ -100 +100 @@
                -int second = 2;
                +int second = 20;
                """, session());

        assertTrue(result.ok(), result.message());
        assertFalse(result.message().contains("range mismatch"), result.message());
        assertTrue(Files.readString(source).contains("second = 20"));
        assertTrue(compiles(source));
    }

    /** 原生片段省略外围标记和空行前缀时，仍按完整上下文精确删除重复定义。 */
    @Test
    void acceptsNativeFragmentWithBlankContextAndIndentedEndMarker() throws Exception {
        Path source = root.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\n\npublic class Handler { int value = 2; }\nclass Duplicate {}\n", StandardCharsets.UTF_8);
        var result = service.apply("""
                *** Update File: src/main/java/demo/Handler.java
                @@
                 package demo;

                 public class Handler { int value = 2; }
                -class Duplicate {}
                 *** End Patch
                """, session());
        assertTrue(result.ok(), result.message());
        assertEquals("package demo;\n\npublic class Handler { int value = 2; }\n", Files.readString(source));
        assertTrue(compiles(source));
    }

    /** 兼容片段不放宽路径和精确匹配；多个文件预检失败时仍不能局部落盘。 */
    @Test
    void nativeFragmentsKeepPathAndAtomicContextProtection() throws Exception {
        Path source = root.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        String original = "package demo;\n\npublic class Handler {}\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        var session = session();
        var mismatch = service.apply("""
                *** Add File: src/test/java/demo/NewTest.java
                +class NewTest {}
                *** Update File: src/main/java/demo/Handler.java
                @@
                 package wrong;

                -public class Handler {}
                +public class Handler { int value; }
                """, session);
        assertEquals("PATCH_CONTEXT_NOT_FOUND", mismatch.error());
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root.resolve("src/test/java/demo/NewTest.java")));
        assertEquals(0, session.writeVersion());
        assertEquals("PATH_OUT_OF_SCOPE", service.apply("*** Add File: ../escape.java\n+class Escape {}", session).error());
    }

    /** 使用本机 JDK 独立编译固定候选，区分工具写入成功与 Java 源码有效。 */
    private boolean compiles(Path source) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path output = root.resolve("compiled");
        Files.createDirectories(output);
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            return compiler.getTask(new java.io.StringWriter(), files, null,
                    java.util.List.of("-d", output.toString()), null,
                    files.getJavaFileObjects(source.toFile())).call();
        }
    }

    /** 真实轨迹中少了一个上下文空格：只诊断，不写入；正确保留缩进后可以应用。 */
    @Test
    void diagnosesMissingContextMarkerSpaceWithoutFuzzyWrites() throws Exception {
        Path source = root.resolve("src/Example.java");
        Files.createDirectories(source.getParent());
        String original = "class Example {\n    int value = 1;\n}\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        var session = session();
        String bad = "*** Begin Patch\n*** Add File: src/Other.java\n+class Other {}\n"
                + "*** Update File: src/Example.java\n@@\n    int value = 1;\n+    int second = 2;\n*** End Patch\n";
        var rejected = service.apply(bad, session);
        assertEquals("PATCH_CONTEXT_NOT_FOUND", rejected.error());
        assertTrue(rejected.message().contains("only after ignoring leading whitespace"));
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root.resolve("src/Other.java")));
        assertEquals(0, session.writeVersion());
        assertTrue(service.apply(bad.replace("\n    int value", "\n     int value"), session).ok());
        assertTrue(Files.readString(source).contains("    int second = 2;"));
    }

    /** 无前缀的右括号应收到正文修复提示，而不是被误导为 Begin/End 问题。 */
    @Test
    void explainsUnmarkedBraceInHunk() throws Exception {
        Path source = root.resolve("src/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Example {\n}\n", StandardCharsets.UTF_8);
        var rejected = service.apply("*** Begin Patch\n*** Update File: src/Example.java\n@@\n class Example {\n}\n*** End Patch\n", session());
        assertEquals("PATCH_FORMAT", rejected.error());
        assertTrue(rejected.message().contains("including closing braces"));
        assertFalse(rejected.message().contains("Do not end each file separately"));
        assertEquals("class Example {\n}\n", Files.readString(source));
    }

    /** 真实续接中的尾随空 @@ 必须指出块位置，预检失败不能留下前面文件的修改。 */
    @Test
    void explainsEmptyHunkBeforeNextFileWithoutPartialWrites() throws Exception {
        Path source = root.resolve("src/Example.java");
        Files.createDirectories(source.getParent());
        String original = "public class Example {\n    int value = 1;\n}\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        var session = session();
        String patch = "*** Begin Patch\n*** Add File: README.md\n+Ready\n"
                + "*** Update File: src/Example.java\n@@\n-    int value = 1;\n+    int value = 2;\n@@\n"
                + "*** Add File: src/Other.java\n+class Other {}\n*** End Patch\n";

        var rejected = service.apply(patch, session);
        assertEquals("PATCH_CONTEXT", rejected.error());
        assertTrue(rejected.message().contains("Empty @@ hunk at normalized patch line 8"), rejected.message());
        assertTrue(rejected.message().contains("Remove the unused @@"));
        assertTrue(rejected.message().contains("No files were written"));
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root.resolve("README.md")));
        assertFalse(Files.exists(root.resolve("src/Other.java")));
        assertEquals(0, session.writeVersion());

        // 模拟重新提交明确修正的完整补丁；工具自身不猜测或丢弃失败块。
        var corrected = service.apply(patch.replace("@@\n*** Add File: src/Other.java",
                "*** Add File: src/Other.java"), session);
        assertTrue(corrected.ok(), corrected.message());
        assertTrue(Files.readString(source).contains("int value = 2"));
        assertTrue(compiles(source));
    }

    /** 只有新增行的 Update 不能猜插入位置，反馈要求准确锚点并保留原文件。 */
    @Test
    void distinguishesUnanchoredInsertionFromEmptyHunk() throws Exception {
        Path source = root.resolve("src/Example.java");
        Files.createDirectories(source.getParent());
        String original = "public class Example {}\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        var session = session();
        var rejected = service.apply("*** Begin Patch\n*** Update File: src/Example.java\n@@\n"
                + "+@Deprecated\n*** End Patch\n", session);
        assertEquals("PATCH_CONTEXT", rejected.error());
        assertTrue(rejected.message().contains("no existing source lines"), rejected.message());
        assertTrue(rejected.message().contains("exact unchanged context line"));
        assertFalse(rejected.message().contains("Remove the unused @@"));
        assertEquals(original, Files.readString(source));
        assertEquals(0, session.writeVersion());
    }

    /** 多文件操作头前误加空格应收到文法诊断，原子预检仍拒绝所有落盘。 */
    @Test
    void diagnosesPrefixedFileHeaderWithoutReinterpretingIt() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A { int value = 1; }\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/B.java"), "class B { int value = 1; }\n", StandardCharsets.UTF_8);
        String bad = "*** Begin Patch\n*** Update File: src/A.java\n@@\n"
                + "-class A { int value = 1; }\n+class A { int value = 2; }\n"
                + " *** Update File: src/B.java\n@@\n-class B { int value = 1; }\n"
                + "+class B { int value = 2; }\n*** End Patch\n";
        var session = session();
        var rejected = service.apply(bad, session);
        assertEquals("PATCH_CONTEXT_NOT_FOUND", rejected.error());
        assertTrue(rejected.message().contains("File operation headers have no hunk prefix"), rejected.message());
        assertTrue(rejected.message().contains("src/B.java"));
        assertEquals(0, session.writeVersion());
        assertTrue(Files.readString(root.resolve("src/A.java")).contains("value = 1"));
        assertTrue(Files.readString(root.resolve("src/B.java")).contains("value = 1"));
        assertTrue(service.apply(bad.replace("\n *** Update File:", "\n*** Update File:"), session).ok());
        assertTrue(compiles(root.resolve("src/A.java")));
        assertTrue(compiles(root.resolve("src/B.java")));
    }

    private WorkspaceAgentSession session() {
        return new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }
}
