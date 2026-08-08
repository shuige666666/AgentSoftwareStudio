package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
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

    public void recordSuccess(String modelName, LlmCallUsage usage, long elapsedMillis) {
        long callIndex = calls.incrementAndGet();
        successfulCalls.incrementAndGet();
        durationMillis.addAndGet(elapsedMillis);

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

    public void recordFailure(String modelName, long elapsedMillis, Exception error) {
        long callIndex = calls.incrementAndGet();
        failedCalls.incrementAndGet();
        durationMillis.addAndGet(elapsedMillis);
        System.out.printf("[LLM用量] 第%d次 阶段=%s 模型=%s 耗时=%dms 调用失败=%s%n",
                callIndex,
                currentCallName(),
                modelName,
                elapsedMillis,
                error == null ? "未知错误" : error.getMessage());
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
                successful > 0 && allSuccessfulCallsHaveCacheMetrics.get());
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
                """,
                snapshot.calls(), snapshot.successfulCalls(), snapshot.failedCalls(), snapshot.durationMillis(),
                inputSummary, snapshot.outputTokens(), snapshot.totalTokens()).stripTrailing();
    }

    private long safe(long value) {
        return Math.max(0, value);
    }

    private double hitRate(long hitTokens, long missTokens) {
        long inputTokens = hitTokens + missTokens;
        return inputTokens == 0 ? 0.0 : (double) hitTokens / inputTokens;
    }
}
