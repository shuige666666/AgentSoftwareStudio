package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 在真实验证通过后接受当前切片，并准备下一切片或最终全量验证。
 */
@Service
public class SliceAcceptanceNodeService {

    private final RunJournalService runJournalService;

    public SliceAcceptanceNodeService(RunJournalService runJournalService) {
        this.runJournalService = runJournalService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        var slice = data.currentSlice();
        if (slice == null || !data.currentSliceVerified) {
            return data;
        }
        if (!data.acceptedSliceIds.contains(slice.id())) {
            data.acceptedSliceIds.add(slice.id());
        }
        for (String testFile : data.currentSliceTestFiles) {
            if (!data.acceptedTestFiles.contains(testFile)) {
                data.acceptedTestFiles.add(testFile);
            }
            data.testSliceOwners.put(testFile, slice.id());
        }
        runJournalService.recordSliceAccepted(data);
        logger.accept("   Accepted vertical slice `" + slice.name() + "`.");

        data.currentSliceIndex++;
        data.currentSliceTestFiles.clear();
        data.currentSlicePersisted = false;
        data.currentSliceVerified = false;
        data.verificationResult = com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult.empty();
        data.qualityPolicyResult = com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult.empty();
        data.pendingFixLog = null;
        data.pendingErrorType = null;
        data.pendingFailureKind = com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.NONE;
        data.shouldFix = false;
        data.lastFailureFingerprint = null;
        data.repeatedFailureCount = 0;
        if (data.currentSlice() != null) {
            runJournalService.recordSliceStarted(data);
        }
        return data;
    }

    public SoftwareStudioWorkflowData prepareFinalVerification(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.finalVerificationStarted = true;
        data.currentSlicePersisted = true;
        data.currentSliceVerified = false;
        data.success = false;
        data.repairStopRequested = false;
        data.lastFailureFingerprint = null;
        data.repeatedFailureCount = 0;
        logger.accept("10. All slices accepted; starting final full-project verification.");
        return data;
    }
}
