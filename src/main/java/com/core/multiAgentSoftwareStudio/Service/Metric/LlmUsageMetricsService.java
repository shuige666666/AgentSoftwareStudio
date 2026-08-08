package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFailureCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFailureType;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFinishReasonCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationType;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 汇总一次软件生成任务中的 LLM Token 消耗，并在可用时记录 DeepSeek 远程缓存。
 */
@Service
public class LlmUsageMetricsService {
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong successfulCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong durationMillis = new AtomicLong();
    private final AtomicLong inputCacheHitTokens = new AtomicLong();
    private final AtomicLong inputCacheMissTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong finishStop = new AtomicLong();
    private final AtomicLong finishLength = new AtomicLong();
    private final AtomicLong finishContentFilter = new AtomicLong();
    private final AtomicLong finishToolCalls = new AtomicLong();
    private final AtomicLong finishOther = new AtomicLong();
    private final AtomicLong failureTimeout = new AtomicLong();
    private final AtomicLong failureHttpError = new AtomicLong();
    private final AtomicLong failureInterrupted = new AtomicLong();
    private final AtomicLong failureResponseParseError = new AtomicLong();
    private final AtomicLong failureProviderError = new AtomicLong();
    private final AtomicLong failureUnknown = new AtomicLong();
    private final AtomicLong validationEmptyContent = new AtomicLong();
    private final AtomicLong validationJsonParseError = new AtomicLong();
    private final AtomicLong validationSchemaError = new AtomicLong();
    private final AtomicBoolean allSuccessfulCallsHaveCacheMetrics = new AtomicBoolean(true);
    private final ThreadLocal<String> currentCallName = new ThreadLocal<>();

    public void beginTask() {
        calls.set(0);
        successfulCalls.set(0);
        failedCalls.set(0);
        durationMillis.set(0);
        inputCacheHitTokens.set(0);
        inputCacheMissTokens.set(0);
        outputTokens.set(0);
        totalTokens.set(0);
        finishStop.set(0);
        finishLength.set(0);
        finishContentFilter.set(0);
        finishToolCalls.set(0);
        finishOther.set(0);
        failureTimeout.set(0);
        failureHttpError.set(0);
        failureInterrupted.set(0);
        failureResponseParseError.set(0);
        failureProviderError.set(0);
        failureUnknown.set(0);
        validationEmptyContent.set(0);
        validationJsonParseError.set(0);
        validationSchemaError.set(0);
        allSuccessfulCallsHaveCacheMetrics.set(true);
    }

    public <T> T withCallName(String callName, Callable<T> action) {
        String previous = currentCallName.get();
        currentCallName.set(callName == null || callName.isBlank() ? "LLM调用" : callName);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                currentCallName.remove();
            } else {
                currentCallName.set(previous);
            }
        }
    }

    public String currentCallName() {
        String value = currentCallName.get();
        return value == null || value.isBlank() ? "LLM调用" : value;
    }

    public void recordSuccess(String modelName,
                              LlmCallUsage usage,
                              long elapsedMillis,
                              String finishReason) {
        long callIndex = calls.incrementAndGet();
        successfulCalls.incrementAndGet();
        durationMillis.addAndGet(elapsedMillis);
        recordFinishReason(finishReason);

        long hit = safe(usage.inputCacheHitTokens());
        long miss = safe(usage.inputCacheMissTokens());
        long output = safe(usage.outputTokens());
        long total = safe(usage.totalTokens());
        if (!usage.cacheMetricsAvailable()) {
            allSuccessfulCallsHaveCacheMetrics.set(false);
        }

        inputCacheHitTokens.addAndGet(hit);
        inputCacheMissTokens.addAndGet(miss);
        outputTokens.addAndGet(output);
        totalTokens.addAndGet(total);

        if (usage.cacheMetricsAvailable()) {
            System.out.printf(Locale.ROOT,
                    "[LLM用量] 第%d次 阶段=%s 模型=%s 耗时=%dms 输入(命中缓存)=%d 输入(未命中缓存)=%d 输出=%d 总计=%d 远程输入缓存命中率=%.2f%%%n",
                    callIndex, currentCallName(), modelName, elapsedMillis,
                    hit, miss, output, total, hitRate(hit, miss) * 100);
            return;
        }
        System.out.printf(Locale.ROOT,
                "[LLM用量] 第%d次 阶段=%s 模型=%s 耗时=%dms 输入=%d 输出=%d 总计=%d 远程输入缓存命中率=N/A%n",
                callIndex, currentCallName(), modelName, elapsedMillis,
                usage.inputTokens(), output, total);
    }

    public void recordFailure(String modelName,
                              long elapsedMillis,
                              LlmFailureType failureType,
                              Exception error) {
        long callIndex = calls.incrementAndGet();
        failedCalls.incrementAndGet();
        durationMillis.addAndGet(elapsedMillis);
        LlmFailureType normalizedType = failureType == null ? LlmFailureType.UNKNOWN : failureType;
        recordFailureType(normalizedType);
        System.out.printf("[LLM用量] 第%d次 阶段=%s 模型=%s 耗时=%dms 失败类型=%s 调用失败=%s%n",
                callIndex,
                currentCallName(),
                modelName,
                elapsedMillis,
                normalizedType,
                error == null ? "未知错误" : error.getMessage());
    }

    /**
     * 记录模型调用成功后的业务输出校验失败，不重复增加 failedCalls。
     */
    public void recordOutputValidationFailure(LlmOutputValidationType validationType) {
        LlmOutputValidationType normalizedType = validationType == null
                ? LlmOutputValidationType.SCHEMA_VALIDATION_ERROR
                : validationType;
        switch (normalizedType) {
            case EMPTY_CONTENT -> validationEmptyContent.incrementAndGet();
            case JSON_PARSE_ERROR -> validationJsonParseError.incrementAndGet();
            case SCHEMA_VALIDATION_ERROR -> validationSchemaError.incrementAndGet();
        }
        System.out.printf("[LLM输出校验] 阶段=%s 失败类型=%s%n", currentCallName(), normalizedType);
    }

    public LlmUsageSnapshot snapshot() {
        long successful = successfulCalls.get();
        return new LlmUsageSnapshot(
                calls.get(),
                successful,
                failedCalls.get(),
                durationMillis.get(),
                inputCacheHitTokens.get(),
                inputCacheMissTokens.get(),
                outputTokens.get(),
                totalTokens.get(),
                successful > 0 && allSuccessfulCallsHaveCacheMetrics.get(),
                new LlmFinishReasonCounts(
                        finishStop.get(), finishLength.get(), finishContentFilter.get(),
                        finishToolCalls.get(), finishOther.get()),
                new LlmFailureCounts(
                        failureTimeout.get(), failureHttpError.get(), failureInterrupted.get(),
                        failureResponseParseError.get(), failureProviderError.get(), failureUnknown.get()),
                new LlmOutputValidationCounts(
                        validationEmptyContent.get(), validationJsonParseError.get(), validationSchemaError.get()));
    }

    public String formatSummary() {
        LlmUsageSnapshot snapshot = snapshot();
        String inputSummary;
        if (snapshot.cacheMetricsAvailable()) {
            inputSummary = String.format(Locale.ROOT,
                    "输入Token(命中缓存)=%d%n输入Token(未命中缓存)=%d%n远程输入缓存命中率=%.2f%%",
                    snapshot.inputCacheHitTokens(),
                    snapshot.inputCacheMissTokens(),
                    snapshot.remoteInputCacheHitRate() * 100);
        } else {
            inputSummary = String.format(Locale.ROOT,
                    "输入Token=%d%n远程输入缓存命中率=N/A",
                    snapshot.inputTokens());
        }
        return String.format(Locale.ROOT, """
                [LLM用量汇总]
                LLM调用次数=%d，成功=%d，失败=%d，总耗时=%dms
                %s
                输出Token=%d
                总Token=%d
                结束原因：STOP=%d，LENGTH=%d，CONTENT_FILTER=%d，TOOL_CALLS=%d，OTHER=%d
                调用失败：TIMEOUT=%d，HTTP_ERROR=%d，INTERRUPTED=%d，RESPONSE_PARSE_ERROR=%d，PROVIDER_ERROR=%d，UNKNOWN=%d
                输出校验：EMPTY_CONTENT=%d，JSON_PARSE_ERROR=%d，SCHEMA_VALIDATION_ERROR=%d
                """,
                snapshot.calls(), snapshot.successfulCalls(), snapshot.failedCalls(), snapshot.durationMillis(),
                inputSummary, snapshot.outputTokens(), snapshot.totalTokens(),
                snapshot.finishReasonCounts().stop(), snapshot.finishReasonCounts().length(),
                snapshot.finishReasonCounts().contentFilter(), snapshot.finishReasonCounts().toolCalls(),
                snapshot.finishReasonCounts().other(),
                snapshot.failureCounts().timeout(), snapshot.failureCounts().httpError(),
                snapshot.failureCounts().interrupted(), snapshot.failureCounts().responseParseError(),
                snapshot.failureCounts().providerError(), snapshot.failureCounts().unknown(),
                snapshot.outputValidationCounts().emptyContent(),
                snapshot.outputValidationCounts().jsonParseError(),
                snapshot.outputValidationCounts().schemaValidationError()).stripTrailing();
    }

    private void recordFinishReason(String finishReason) {
        switch (finishReason == null ? "" : finishReason.strip().toLowerCase(Locale.ROOT)) {
            case "stop" -> finishStop.incrementAndGet();
            case "length" -> finishLength.incrementAndGet();
            case "content_filter" -> finishContentFilter.incrementAndGet();
            case "tool_calls", "function_call" -> finishToolCalls.incrementAndGet();
            default -> finishOther.incrementAndGet();
        }
    }

    private void recordFailureType(LlmFailureType failureType) {
        switch (failureType) {
            case TIMEOUT -> failureTimeout.incrementAndGet();
            case HTTP_ERROR -> failureHttpError.incrementAndGet();
            case INTERRUPTED -> failureInterrupted.incrementAndGet();
            case RESPONSE_PARSE_ERROR -> failureResponseParseError.incrementAndGet();
            case PROVIDER_ERROR -> failureProviderError.incrementAndGet();
            case UNKNOWN -> failureUnknown.incrementAndGet();
        }
    }

    private long safe(long value) {
        return Math.max(0, value);
    }

    private double hitRate(long hitTokens, long missTokens) {
        long inputTokens = hitTokens + missTokens;
        return inputTokens == 0 ? 0.0 : (double) hitTokens / inputTokens;
    }
}
