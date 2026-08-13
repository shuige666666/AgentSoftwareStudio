package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.FrontendReviewAgent;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

/**
 * 负责执行前端专项审查节点，修复 HTML/CSS/JS 之间的交互一致性问题。
 */
@Service
public class FrontendReviewNodeService {

    private final FrontendReviewAgent frontendReviewAgent;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;

    /**
     * 注入前端审查 Agent 和源码上下文辅助服务。
     */
    public FrontendReviewNodeService(FrontendReviewAgent frontendReviewAgent,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService) {
        this.frontendReviewAgent = frontendReviewAgent;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 执行前端专项审查修复，重点处理 HTML/CSS/JS 之间的交互一致性
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        List<SourceCode> reviewCodes = data.currentSlice() == null || data.finalVerificationStarted
                ? data.codes
                : data.codes.stream()
                        .filter(code -> data.canModifyInCurrentSlice(code.filename()))
                        .toList();
        String frontendContext = codeContextBuilderService.buildFrontendContext(reviewCodes);
        if (frontendContext.isBlank()) {
            logger.accept("5.5. No frontend files detected, skipping frontend review.");
            return data;
        }

        logger.accept("5.5. Frontend reviewer is checking DOM, events, visibility states, and fetch calls.");
        var reviewContract = data.currentSlice() == null || data.finalVerificationStarted
                ? data.contract
                : data.currentSlice().contract();
        CodeFixResult reviewResult = frontendReviewAgent.reviewAndFix(reviewContract, frontendContext);
        List<CodeFix> fixes = reviewResult == null ? null : reviewResult.fixes();
        if (fixes == null || fixes.isEmpty()) {
            logger.accept("   Frontend reviewer found no concrete fixes.");
            return data;
        }

        logger.accept("   Frontend reviewer returned " + fixes.size() + " fixes.");
        for (CodeFix fix : fixes) {
            if (fix == null || fix.newCode() == null || fix.newCode().isBlank()) {
                logger.accept("   - Skipped blank frontend whole-file fix.");
                continue;
            }
            String normalizedFilename = sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode());
            if (!data.canModifyInCurrentSlice(normalizedFilename)) {
                logger.accept("   - Skipped out-of-slice frontend fix: " + normalizedFilename);
                continue;
            }
            logger.accept("   - " + safeValue(fix.explanation(), "No explanation provided"));
            sourceCodePathService.applyCodeFix(data.codes, normalizedFilename, fix.newCode());
        }
        return data;
    }
}
