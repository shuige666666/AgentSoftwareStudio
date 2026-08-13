package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowJournalEntry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存软件工坊 LangGraph 工作流中所有节点共享的可序列化状态。
 */
public class SoftwareStudioWorkflowData implements Serializable {
    private static final long serialVersionUID = 1L;

    // 这个内部状态类就是整张 LangGraph 图共享的“真状态”。
    // 你后面如果要加新节点，通常也是在这里补字段，然后让节点读写它。
    public String userRequest = "";
    public List<SourceCode> codes = new ArrayList<>();
    public List<String> validationWarnings = new ArrayList<>();
    public List<WorkflowJournalEntry> runJournal = new ArrayList<>();
    public PrdDocument prd;
    public ProjectStructure structure;
    public ProjectContract contract;
    public ProjectProfile projectProfile;
    public ProjectQualityPolicyResult qualityPolicyResult = ProjectQualityPolicyResult.empty();
    public GenerationPlan generationPlan = new GenerationPlan(List.of());
    public int currentBatchIndex;
    public SliceDeliveryPlan sliceDeliveryPlan = SliceDeliveryPlan.empty();
    public int currentSliceIndex;
    public List<String> acceptedSliceIds = new ArrayList<>();
    public List<String> acceptedTestFiles = new ArrayList<>();
    public List<String> currentSliceTestFiles = new ArrayList<>();
    public Map<String, String> testSliceOwners = new LinkedHashMap<>();
    public boolean currentSlicePersisted;
    public boolean currentSliceVerified;
    public boolean finalVerificationStarted;
    public RepairBudget repairBudget;
    // 这里放进 LangGraph state 的对象都会被序列化，路径统一存字符串更稳妥。
    public String projectPath;
    public String executionResult;
    public String testResult;
    public String pendingFixLog;
    public String pendingErrorType;
    public FailureKind pendingFailureKind = FailureKind.NONE;
    public VerificationResult verificationResult = VerificationResult.empty();
    public boolean success;
    public boolean shouldFix;
    public boolean preflightPassed;
    public boolean repairStopRequested;
    public boolean resumeSliceTestGeneration;
    public String repairStoppedAt;
    public String repairStopReason;
    public int generatedTestFileCount;
    public int admittedTestFileCount;
    public int skippedFutureTypeTestCount;
    public int skippedFutureResourceTestCount;
    public int skippedAcceptedTestFileCount;
    public String lastFailureFingerprint;
    public int repeatedFailureCount;
    public int repeatedFailureStopCount;
    public int noChangeStopCount;
    // 修复先以候选变更保存；下一轮验证退化时可恢复此前可用的源码与质量证据。
    public Map<String, String> repairBaselineCodes = new LinkedHashMap<>();
    public List<String> repairCandidateChangedFiles = new ArrayList<>();
    public List<String> repairBaselineValidationWarnings = new ArrayList<>();
    public ProjectQualityPolicyResult repairBaselineQualityPolicyResult = ProjectQualityPolicyResult.empty();
    public VerificationResult repairBaselineVerificationResult = VerificationResult.empty();
    public FailureKind repairBaselineFailureKind = FailureKind.NONE;
    public String repairBaselineFixLog;
    public String repairBaselineErrorType;
    public String repairBaselineExecutionResult;
    public String repairBaselineTestResult;
    public String repairBaselineFailureFingerprint;
    public Map<String, Integer> regressionRollbackCountsByFailure = new LinkedHashMap<>();
    public int repairRollbackCount;
    public int repeatedRegressionStopCount;
    public int currentAttempt = 1;

    /**
     * 创建默认的空工作流状态
     */
    public SoftwareStudioWorkflowData() {
    }

    /**
     * 使用用户请求初始化工作流状态；真实修复预算在切片数量确定后建立。
     */
    public SoftwareStudioWorkflowData(String userRequest) {
        this.userRequest = userRequest;
        // 规划前保留安全的临时预算，切片计划节点会按真实切片数覆盖。
        this.repairBudget = RepairBudget.forSlicePlan(1);
    }

    /**
     * 判断是否还有未处理的代码生成批次
     */
    public boolean hasMoreBatches() {
        // 是否还有下一批待生成文件，供条件边决定要不要继续回到 generate_batch。
        return generationPlan != null
                && generationPlan.batches() != null
                && currentBatchIndex < generationPlan.batches().size();
    }

    /**
     * 获取当前游标所指向的生成批次
     */
    public GenerationBatch currentBatch() {
        // 读取当前批次时不额外推进索引，索引推进统一放在 validate_batch 后面。
        if (!hasMoreBatches()) {
            return null;
        }
        return generationPlan.batches().get(currentBatchIndex);
    }

    public boolean hasMoreSlices() {
        return sliceDeliveryPlan != null
                && sliceDeliveryPlan.slices() != null
                && currentSliceIndex < sliceDeliveryPlan.slices().size();
    }

    public DeliverySlice currentSlice() {
        return hasMoreSlices() ? sliceDeliveryPlan.slices().get(currentSliceIndex) : null;
    }

    public String currentSliceId() {
        DeliverySlice slice = currentSlice();
        return finalVerificationStarted ? "FINAL_VERIFICATION" : slice == null ? "UNSCOPED" : slice.id();
    }

    public boolean canModifyInCurrentSlice(String filename) {
        if (finalVerificationStarted || currentSlice() == null) {
            return true;
        }
        String normalized = filename == null ? "" : filename.replace('\\', '/');
        if (normalized.startsWith("src/test/")) {
            // 已接受切片的测试属于回归基线；当前切片可修改自己的测试，也可新增测试。
            boolean acceptedTest = acceptedTestFiles.stream()
                    .map(path -> path.replace('\\', '/'))
                    .anyMatch(normalized::equals);
            boolean currentTest = currentSliceTestFiles.stream()
                    .map(path -> path.replace('\\', '/'))
                    .anyMatch(normalized::equals);
            return !acceptedTest || currentTest;
        }
        return currentSlice().ownedFiles().stream().map(path -> path.replace('\\', '/')).anyMatch(normalized::equals)
                || sliceDeliveryPlan.sharedFiles().stream().map(path -> path.replace('\\', '/')).anyMatch(normalized::equals);
    }

    public List<String> activeTestFiles() {
        if (finalVerificationStarted) {
            return List.of();
        }
        java.util.LinkedHashSet<String> tests = new java.util.LinkedHashSet<>(acceptedTestFiles);
        tests.addAll(currentSliceTestFiles);
        return List.copyOf(tests);
    }

    public boolean canRepairNow() {
        if (repairStopRequested || repairBudget == null) {
            return false;
        }
        return repairBudget.canRepair(
                currentSliceId(), finalVerificationStarted,
                !finalVerificationStarted);
    }

    public void consumeRepairBudget() {
        repairBudget.consume(
                currentSliceId(), finalVerificationStarted,
                !finalVerificationStarted);
        currentAttempt = 1 + repairBudget.usedLlmRepairs();
    }

    /**
     * 失败已经需要修复但当前策略不允许继续时，记录实际停止位置供基准报告诊断。
     */
    public void markRepairBudgetUnavailable() {
        if (repairBudget != null && !repairBudget.canRepair(
                currentSliceId(), finalVerificationStarted,
                !finalVerificationStarted)) {
            repairBudget.markExhausted(currentSliceId(), finalVerificationStarted);
            markRepairStopped("BUDGET_EXHAUSTED");
        }
    }

    /**
     * 记录修复停止的真实原因，避免把无变化或重复失败误报成预算耗尽。
     */
    public void markRepairStopped(String reason) {
        if (repairStoppedAt == null) {
            repairStoppedAt = currentSliceId();
            repairStopReason = reason == null ? "UNKNOWN" : reason;
        }
    }
}
