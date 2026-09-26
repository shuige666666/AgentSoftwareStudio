package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.PlanningAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningConflictDecision;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningDesign;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectContractMergeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningConflictContinuationTest {

    @Test
    void revisedPlanBecomesEffectiveAndIsPersistedForCurrentSession() {
        PlanningAgent agent = mock(PlanningAgent.class);
        ProjectContractMergeService merge = mock(ProjectContractMergeService.class);
        PlanningArtifactPersistenceService persistence = mock(PlanningArtifactPersistenceService.class);
        PlanningNodeService node = new PlanningNodeService(agent, merge, persistence);
        ProjectStructure structure = new ProjectStructure("demo", "SPRING_BOOT", "demo.App", List.of());
        ProjectContract contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        PlanningDesign revised = new PlanningDesign(structure, contract, "revised domain", Map.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("user contract");
        when(agent.reviewConflict(any(), any(), any(), any()))
                .thenReturn(new PlanningConflictDecision(true, "contract mismatch", revised));
        when(merge.merge(structure, contract)).thenReturn(structure);

        var decision = node.reviewConflict(data, "planning conflict evidence", message -> { });

        assertTrue(decision.revised());
        assertSame(revised, data.planningDesign);
        assertSame(contract, data.contract);
        assertSame(structure, data.structure);
        verify(persistence).persistStage(eq(data),
                eq(PlanningArtifactPersistenceService.PlanningStage.CONTRACT), any());
    }
}
