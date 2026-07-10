package com.core.multiAgentSoftwareStudio.Controller;

import com.core.multiAgentSoftwareStudio.Service.SoftwareStudioService;
import com.core.multiAgentSoftwareStudio.Pojo.Result.Result;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunReport;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunRequest;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/SoftwareStudio")
public class SoftwareController {

    private final SoftwareStudioService softwareStudioService;
    private final BenchmarkRunnerService benchmarkRunnerService;

    @PostMapping("/chat")
    public Result<List<SourceCode>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        List<SourceCode> result =softwareStudioService.generateProject(message);
        return Result.success(result);
    }

    /**
     * 调用流接口
     * @param request
     * @return
     */
    @PostMapping("/stream-chat")
    public SseEmitter streamChat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Set timeout to infinity
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().data("Workflow started."));
                softwareStudioService.generateProjectStream(message, (log) -> {
                    try {
                        emitter.send(SseEmitter.event().data(log));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });

        return emitter;
    }

    /**
     * 查看基准任务，不会调用 LLM。
     */
    @PostMapping("/benchmarks/cases")
    public Result<List<BenchmarkCase>> benchmarkCases() {
        return Result.success(benchmarkRunnerService.listCases());
    }

    /**
     * 显式执行真实 LLM 基准任务；该调用会消耗模型额度并运行生成项目的验证流程。
     */
    @PostMapping("/benchmarks/run")
    public Result<BenchmarkRunReport> runBenchmarks(@RequestBody(required = false) BenchmarkRunRequest request) {
        return Result.success(benchmarkRunnerService.run(request));
    }
}
