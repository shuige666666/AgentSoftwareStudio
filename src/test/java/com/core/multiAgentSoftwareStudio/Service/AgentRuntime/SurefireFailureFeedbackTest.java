package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SurefireFailureFeedbackTest {
    @TempDir Path root;

    /** 多个测试在公共准备步骤失败时，保留实际断言与共同源码位置。 */
    @Test
    void reportsAssertionsAndSharedFirstProjectFrame() throws Exception {
        Path source = root.resolve("src/test/java/demo/AppTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class AppTest {}");
        Path report = reportPath();
        Files.writeString(report, """
                <testsuite>
                  <testcase classname="demo.AppTest" name="create"><failure message="Status expected:&lt;404&gt; but was:&lt;200&gt;">at org.junit.Assert.fail(Assert.java:87)
                at demo.AppTest.setUp(AppTest.java:22)</failure></testcase>
                  <testcase classname="demo.AppTest" name="edit"><failure message="Status expected:&lt;404&gt; but was:&lt;200&gt;">at demo.AppTest.setUp(AppTest.java:22)</failure></testcase>
                </testsuite>
                """);

        String feedback = SurefireFailureFeedback.read(root, Map.of());

        assertTrue(feedback.contains("demo.AppTest.create"));
        assertTrue(feedback.contains("expected:<404> but was:<200>"));
        assertTrue(feedback.contains("src/test/java/demo/AppTest.java:22 (setUp)"));
        assertTrue(feedback.contains("(2 cases)"));
        assertTrue(feedback.contains("do not weaken valid assertions"));
    }

    /** 编译失败或普通命令没有刷新报告时，不借用上次 Surefire 结果。 */
    @Test
    void ignoresUnchangedReportsButReadsANewExecution() throws Exception {
        Path report = reportPath();
        Files.writeString(report, "<testsuite><testcase name=\"bad\" classname=\"demo.Test\"><failure message=\"wrong\"/></testcase></testsuite>");
        var before = SurefireFailureFeedback.snapshot(root);
        assertEquals("", SurefireFailureFeedback.read(root, before));
        Files.setLastModifiedTime(report, FileTime.fromMillis(before.get(report).toMillis() + 2_000));
        assertTrue(SurefireFailureFeedback.read(root, before).contains("demo.Test.bad"));
    }

    /** 不可信报告的外部实体不能读取候选以外的文件，解析失败仍由原日志提供诊断。 */
    @Test
    void rejectsExternalEntities() throws Exception {
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "PRIVATE-FILE-CONTENT");
        Path report = reportPath();
        Files.writeString(report, """
                <!DOCTYPE testsuite [<!ENTITY leak SYSTEM "%s">]>
                <testsuite><testcase name="bad" classname="demo.Test"><failure>&leak;</failure></testcase></testsuite>
                """.formatted(secret.toUri()));
        assertEquals("", SurefireFailureFeedback.read(root, Map.of()));
    }

    private Path reportPath() throws Exception {
        Path report = root.resolve("target/surefire-reports/TEST-demo.AppTest.xml");
        Files.createDirectories(report.getParent());
        return report;
    }
}
