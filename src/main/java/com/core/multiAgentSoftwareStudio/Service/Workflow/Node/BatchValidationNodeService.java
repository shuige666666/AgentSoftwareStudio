package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Service.Contract.ContractValidationService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 负责执行批次级轻量契约校验，并推进当前批次游标。
 */
@Service
public class BatchValidationNodeService {

    private final ContractValidationService contractValidationService;

    /**
     * 注入契约校验服务。
     */
    public BatchValidationNodeService(ContractValidationService contractValidationService) {
        this.contractValidationService = contractValidationService;
    }

    /**
     * 对当前批次结果执行轻量契约校验
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        GenerationBatch batch = data.currentBatch();
        if (batch == null) {
            return data;
        }

        // 这里故意只做“便宜检查”：
        // - 路径和 package 是否对齐
        // - public 类型名是否和文件名一致
        // - 是否还残留明显占位内容
        // - 某些层级是否缺少典型注解
        //
        // 不在这里触发完整编译/修复，是为了控制 token 成本。
        // 真正昂贵的调试循环只在所有代码生成完以后做一次。
        List<String> warnings = contractValidationService.validateBatch(batch, data.codes);
        if (warnings.isEmpty()) {
            logger.accept("   Lightweight contract validation passed for batch `" + batch.name() + "`.");
        } else {
            logger.accept("   Lightweight contract validation found " + warnings.size() + " warnings in batch `"
                    + batch.name() + "`.");
            warnings.forEach(warning -> logger.accept("   - " + warning));
            data.validationWarnings.addAll(warnings);
        }
        data.currentBatchIndex++;
        return data;
    }
}
