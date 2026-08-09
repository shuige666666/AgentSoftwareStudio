package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
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
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);

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
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);
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
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);

        service(profileService).execute(data, message -> {
        });

        assertTrue(data.preflightPassed);
        assertFalse(data.shouldFix);
        assertEquals(FailureKind.NONE, data.pendingFailureKind);
        assertEquals(1, data.runJournal.size());
    }

    private PreflightValidationNodeService service(ProjectProfileService profileService) {
        return new PreflightValidationNodeService(
                profileService,
                mock(WorkspaceService.class),
                new SourceCodePathService(),
                new RunJournalService());
    }
}
