package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.FrontendReviewAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFix;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

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
        String frontendContext = codeContextBuilderService.buildFrontendContext(data.codes);
        if (frontendContext.isBlank()) {
            logger.accept("5.5. No frontend files detected, skipping frontend review.");
            return data;
        }

        logger.accept("5.5. Frontend reviewer is checking DOM, events, visibility states, and fetch calls.");
        CodeFixResult reviewResult = frontendReviewAgent.reviewAndFix(data.contract, frontendContext);
        List<CodeFix> fixes = reviewResult == null ? null : reviewResult.fixes();
        if (fixes == null || fixes.isEmpty()) {
            logger.accept("   Frontend reviewer found no concrete fixes.");
            return data;
        }

        logger.accept("   Frontend reviewer returned " + fixes.size() + " fixes.");
        for (CodeFix fix : fixes) {
            CodeFix normalizedFix = new CodeFix(
                    sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode()),
                    fix.explanation(),
                    fix.newCode());
            logger.accept("   - " + safeValue(fix.explanation(), "No explanation provided"));
            updateCodesInMemory(data.codes, normalizedFix);
        }
        return data;
    }

    /**
     * 用修复后的代码更新内存中的文件列表
     */
    private void updateCodesInMemory(List<SourceCode> codes, CodeFix fix) {
        // debugger 返回修复结果后，先更新内存，再落盘。
        // 后续重跑时用到的是修复后的最新版本，而不是旧代码。
        boolean found = false;
        String normalizedFixFilename = sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode());
        String fixPureName = Path.of(normalizedFixFilename).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            String existingFilename = sourceCodePathService.normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (existingFilename.equals(normalizedFixFilename)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
            String existingPureName = Path.of(existingFilename).getFileName().toString();
            if (existingPureName.equals(fixPureName)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }

        if (!found) {
            codes.add(new SourceCode(normalizedFixFilename, sourceCodePathService.detectLanguageFromFilename(normalizedFixFilename),
                    fix.newCode()));
        }
    }

    /**
     * 在值为空时返回兜底文本
     */
    private String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
