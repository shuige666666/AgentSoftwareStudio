package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairRegressionGuardServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 契约修复若引入主代码编译错误，必须恢复修复前源码、失败分类和磁盘文件。
     */
    @Test
    void rollsBackContractRepairThatRegressesToMainCompile() throws Exception {
        SourceCodePathService pathService = new SourceCodePathService();
        RepairRegressionGuardService service = new RepairRegressionGuardService(
                new WorkspaceService(pathService), pathService);
        String filename = "src/main/java/com/example/PollController.java";
        String baselineCode = "package com.example; class PollController { int stable; }";
        String candidateCode = "package com.example; class PollController { MissingType broken; }";
        Path diskFile = tempDir.resolve(filename);
        Files.createDirectories(diskFile.getParent());
        Files.writeString(diskFile, candidateCode, StandardCharsets.UTF_8);

        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = tempDir.toString();
        data.codes.add(new SourceCode(filename, "java", candidateCode));
        data.repairBaselineCodes = new LinkedHashMap<>(java.util.Map.of(filename, baselineCode));
        data.repairCandidateChangedFiles = new ArrayList<>(List.of(filename));
        data.repairBaselineFailureKind = FailureKind.CONTRACT;
        data.repairBaselineErrorType = FailureKind.CONTRACT.name();
        data.repairBaselineFixLog = "frontend contract mismatch";
        data.repairBaselineExecutionResult = "BUILD SUCCESS";
        data.repairBaselineTestResult = "Tests run: 2, Failures: 0, Errors: 0";
        data.repairBaselineVerificationResult = VerificationResult.empty();
        data.repairBaselineQualityPolicyResult = ProjectQualityPolicyResult.empty();
        data.repairBaselineFailureFingerprint = "contract-fingerprint";
        data.pendingFailureKind = FailureKind.MAIN_COMPILE;
        data.lastFailureFingerprint = "contract-fingerprint";
        data.repeatedFailureCount = 1;

        boolean rolledBack = service.rollbackIfRegressed(data, message -> { });

        assertTrue(rolledBack);
        assertEquals(FailureKind.CONTRACT, data.pendingFailureKind);
        assertEquals(baselineCode, data.codes.getFirst().code());
        assertEquals(baselineCode, Files.readString(diskFile, StandardCharsets.UTF_8));
        assertEquals(1, data.repairRollbackCount);
        assertEquals(1, data.regressionRollbackCountsByFailure.get("contract-fingerprint"));
        assertEquals(null, data.lastFailureFingerprint);
        assertEquals(0, data.repeatedFailureCount);
        assertFalse(data.repairStopRequested);
        assertTrue(data.repairBaselineCodes.isEmpty());
    }

    /**
     * 同一基线第一次退化后允许换方案，第二个候选仍退化时才停止继续消耗预算。
     */
    @Test
    void stopsAfterSecondRegressionForSameBaselineFailure() {
        SourceCodePathService pathService = new SourceCodePathService();
        RepairRegressionGuardService service = new RepairRegressionGuardService(
                new WorkspaceService(pathService), pathService);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        prepareRegression(data, "same-baseline");

        assertTrue(service.rollbackIfRegressed(data, message -> { }));
        assertFalse(data.repairStopRequested);

        prepareRegression(data, "same-baseline");
        assertTrue(service.rollbackIfRegressed(data, message -> { }));

        assertTrue(data.repairStopRequested);
        assertEquals("REPEATED_REGRESSION", data.repairStopReason);
        assertEquals(2, data.repairRollbackCount);
        assertEquals(1, data.repeatedRegressionStopCount);
        assertEquals(2, data.regressionRollbackCountsByFailure.get("same-baseline"));
        assertEquals(0, data.repeatedFailureStopCount);
    }

    /**
     * 修复停留在同一失败层级时应提交候选状态，不能误回滚仍可能有效的渐进修改。
     */
    @Test
    void keepsCandidateAtSameFailureStage() {
        SourceCodePathService pathService = new SourceCodePathService();
        RepairRegressionGuardService service = new RepairRegressionGuardService(
                new WorkspaceService(pathService), pathService);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.repairBaselineCodes.put("pom.xml", "old");
        data.repairCandidateChangedFiles.add("pom.xml");
        data.repairBaselineFailureKind = FailureKind.MAIN_COMPILE;
        data.pendingFailureKind = FailureKind.MAIN_COMPILE;

        assertFalse(service.rollbackIfRegressed(data, message -> { }));
        assertTrue(data.repairBaselineCodes.isEmpty());
        assertEquals(0, data.repairRollbackCount);
    }

    /**
     * 候选修复新增的模板若导致更早阶段失败，回滚时只删除该精确新增文件。
     */
    @Test
    void removesCandidateOnlyFileDuringRollback() throws Exception {
        SourceCodePathService pathService = new SourceCodePathService();
        RepairRegressionGuardService service = new RepairRegressionGuardService(
                new WorkspaceService(pathService), pathService);
        String filename = "src/main/resources/templates/missing.html";
        Path diskFile = tempDir.resolve(filename);
        Files.createDirectories(diskFile.getParent());
        Files.writeString(diskFile, "<html>candidate</html>", StandardCharsets.UTF_8);

        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = tempDir.toString();
        data.codes.add(new SourceCode(filename, "html", "<html>candidate</html>"));
        data.repairBaselineCodes.put("pom.xml", "<project/>");
        data.repairCandidateChangedFiles.add(filename);
        data.repairBaselineFailureKind = FailureKind.CONTRACT;
        data.pendingFailureKind = FailureKind.MAIN_COMPILE;

        assertTrue(service.rollbackIfRegressed(data, message -> { }));
        assertFalse(Files.exists(diskFile));
        assertTrue(data.codes.stream().noneMatch(code -> filename.equals(code.filename())));
    }

    /**
     * Docker 或外部环境失败不代表代码退化，不能因此覆盖已经产生的候选源码。
     */
    @Test
    void doesNotRollbackOnEnvironmentFailure() {
        SourceCodePathService pathService = new SourceCodePathService();
        RepairRegressionGuardService service = new RepairRegressionGuardService(
                new WorkspaceService(pathService), pathService);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.repairBaselineCodes.put("pom.xml", "old");
        data.repairCandidateChangedFiles.add("pom.xml");
        data.repairBaselineFailureKind = FailureKind.CONTRACT;
        data.pendingFailureKind = FailureKind.ENVIRONMENT;

        assertFalse(service.rollbackIfRegressed(data, message -> { }));
        assertEquals(0, data.repairRollbackCount);
    }

    private void prepareRegression(SoftwareStudioWorkflowData data, String fingerprint) {
        data.repairBaselineCodes = new LinkedHashMap<>(java.util.Map.of("pom.xml", "<project/>"));
        data.repairCandidateChangedFiles = new ArrayList<>(List.of("pom.xml"));
        data.repairBaselineFailureKind = FailureKind.TEST_DISCOVERY;
        data.repairBaselineErrorType = FailureKind.TEST_DISCOVERY.name();
        data.repairBaselineFixLog = "No @SpringBootTest was generated";
        data.repairBaselineFailureFingerprint = fingerprint;
        data.pendingFailureKind = FailureKind.TEST_COMPILE;
        data.lastFailureFingerprint = fingerprint;
        data.repeatedFailureCount = 1;
    }
}
