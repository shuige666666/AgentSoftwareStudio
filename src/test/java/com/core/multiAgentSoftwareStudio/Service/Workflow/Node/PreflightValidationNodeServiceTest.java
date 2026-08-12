package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreflightValidationNodeServiceTest {

    /**
     * BLOCK 门禁必须写入结构化失败，并在预算允许时路由到修复节点。
     */
    @Test
    void blocksWorkflowBeforeDocker() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        ProjectProfile profile = ProjectProfile.java17SpringBoot();
        when(profileService.resolve(any())).thenReturn(profile);
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of("pom.xml"));
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(
                new ProjectQualityPolicyResult(false, List.of(
                        new QualityPolicyFinding(
                                "PROFILE_POM_XML", QualityGateSeverity.BLOCK,
                                FailureKind.BUILD_PROFILE, "pom.xml 非法")),
                        List.of("pom.xml")));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");

        service(profileService).execute(data, message -> {
        });

        assertFalse(data.preflightPassed);
        assertTrue(data.shouldFix);
        assertEquals(FailureKind.BUILD_PROFILE, data.pendingFailureKind);
        assertEquals(1, data.runJournal.size());
    }

    /**
     * 没有 BLOCK 项时清理旧失败状态，并允许进入持久化或重新验证。
     */
    @Test
    void passesAndClearsPendingFailure() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of());
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.pendingFailureKind = FailureKind.CONTRACT;
        data.pendingErrorType = FailureKind.CONTRACT.name();

        service(profileService).execute(data, message -> {
        });

        assertTrue(data.preflightPassed);
        assertFalse(data.shouldFix);
        assertEquals(FailureKind.NONE, data.pendingFailureKind);
        assertEquals(1, data.runJournal.size());
    }

    /**
     * 纯语义契约问题先保留在质量结果中，但不能抢在编译和测试错误之前消耗修复预算。
     */
    @Test
    void defersContractBlockerUntilAfterTechnicalVerification() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of());
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(
                new ProjectQualityPolicyResult(false, List.of(
                        new QualityPolicyFinding(
                                "CONTRACT_VALIDATION", QualityGateSeverity.BLOCK,
                                FailureKind.CONTRACT, "frontend field mismatch")),
                        List.of()));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");

        service(profileService).execute(data, message -> {
        });

        assertTrue(data.preflightPassed);
        assertFalse(data.shouldFix);
        assertEquals(FailureKind.NONE, data.pendingFailureKind);
        assertEquals(1, data.runJournal.size());
    }

    /**
     * 当前切片没有生成自己的测试时必须在 Docker 前阻断，不能借用旧回归测试假通过。
     */
    @Test
    void blocksSliceWithoutFocusedAcceptanceTest() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of());
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("poll", "Poll", List.of(), List.of(), contract, List.of(), List.of())),
                List.of(), "test-policy");

        service(profileService).execute(data, message -> { });

        assertFalse(data.preflightPassed);
        assertTrue(data.shouldFix);
        assertEquals(FailureKind.TEST_DISCOVERY, data.pendingFailureKind);
        assertEquals("SLICE_TEST_DISCOVERY", data.qualityPolicyResult.findings().getFirst().gate());
    }

    /**
     * Profile 确定性补建的 Context 测试必须进入当前切片验证范围，不能生成后仍被测试选择器忽略。
     */
    @Test
    void includesNormalizedContextTestInCurrentSliceScope() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        String contextPath = "src/test/java/com/example/AppContextTest.java";
        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of(contextPath));
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("poll", "Poll", List.of(), List.of(), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(contextPath, "java", "@SpringBootTest class AppContextTest {}"));

        service(profileService).execute(data, message -> { });

        assertTrue(data.preflightPassed);
        assertEquals(List.of(contextPath), data.currentSliceTestFiles);
        assertTrue(data.activeTestFiles().contains(contextPath));
    }

    /**
     * 当前生产代码引用未来切片类型时必须在测试和 Docker 前按主源码问题阻断。
     */
    @Test
    void blocksFutureProductionTypeReference() {
        ProjectProfileService profileService = mock(ProjectProfileService.class);
        when(profileService.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        when(profileService.applySafeDefaults(any(), any())).thenReturn(List.of());
        when(profileService.evaluate(any(), any(), any(), any())).thenReturn(
                new ProjectQualityPolicyResult(false, List.of(
                        new QualityPolicyFinding("PROFILE_TEST_SOURCE", QualityGateSeverity.BLOCK,
                                FailureKind.TEST_DISCOVERY, "missing tests"),
                        new QualityPolicyFinding("PROFILE_SPRING_CONTEXT_TEST", QualityGateSeverity.BLOCK,
                                FailureKind.TEST_DISCOVERY, "missing context test")), List.of()));
        FileBlueprint comment = file("src/main/java/com/example/Comment.java", "comments");
        FileBlueprint article = file("src/main/java/com/example/Article.java", "articles");
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(comment, article));
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("comments", "Comments", List.of(), List.of(comment), contract, List.of(), List.of()),
                new DeliverySlice("articles", "Articles", List.of(), List.of(article), contract, List.of(), List.of())),
                List.of(), "test-policy");
        data.codes.add(new SourceCode(comment.targetPath(), "java", "class Comment { private Article article; }"));

        service(profileService).execute(data, message -> { });

        assertFalse(data.preflightPassed);
        assertEquals(FailureKind.MAIN_COMPILE, data.pendingFailureKind);
        assertEquals("SLICE_FUTURE_TYPE_REFERENCE", data.qualityPolicyResult.findings().getFirst().gate());
        assertTrue(data.qualityPolicyResult.findings().stream()
                .noneMatch(finding -> "PROFILE_TEST_SOURCE".equals(finding.gate())
                        || "PROFILE_SPRING_CONTEXT_TEST".equals(finding.gate())
                        || "SLICE_TEST_DISCOVERY".equals(finding.gate())));
    }

    private FileBlueprint file(String path, String batch) {
        return new FileBlueprint(java.nio.file.Path.of(path).getFileName().toString(), path,
                "base", batch, "test", List.of(), List.of());
    }

    private PreflightValidationNodeService service(ProjectProfileService profileService) {
        return new PreflightValidationNodeService(
                profileService,
                mock(WorkspaceService.class),
                new SourceCodePathService(),
                new RunJournalService(),
                new SliceProductionScopeService(new SourceCodePathService()));
    }
}
