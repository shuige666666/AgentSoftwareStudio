package com.core.multiAgentSoftwareStudio.Controller;

import com.core.multiAgentSoftwareStudio.Service.SoftwareStudioService;
import com.core.multiAgentSoftwareStudio.Pojo.Result.Result;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunReport;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunRequest;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkRunnerService;
import com.core.multiAgentSoftwareStudio.Service.Preview.PreviewStatus;
import com.core.multiAgentSoftwareStudio.Service.Preview.ProjectPreviewService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.WorkflowExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ProjectPreviewService projectPreviewService;

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
                WorkflowExecutionResult result = softwareStudioService.generateProjectStream(message, (log) -> {
                    try {
                        emitter.send(SseEmitter.event().data(log));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                if (result.platformSuccess() && result.projectPath() != null) {
                    try {
                        String projectId = projectPreviewService.registerSuccessfulProject(result.projectPath());
                        emitter.send(SseEmitter.event()
                                .name("generation-complete")
                                .data(Map.of("success", true, "projectId", projectId, "previewAvailable", true)));
                    } catch (IllegalArgumentException previewUnavailable) {
                        // 非 Spring Boot 项目仍然算生成成功，只是不展示当前类型不支持的预览入口。
                        emitter.send(SseEmitter.event()
                                .name("generation-complete")
                                .data(Map.of("success", true, "previewAvailable", false)));
                    }
                } else {
                    emitter.send(SseEmitter.event()
                            .name("generation-complete")
                            .data(Map.of("success", false, "previewAvailable", false)));
                }
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
     * 异步启动已经通过生成工作流的 Spring Boot 项目预览。
     */
    @PostMapping("/projects/{projectId}/preview")
    public Result<PreviewStatus> startPreview(@PathVariable String projectId) {
        try {
            return Result.success(projectPreviewService.startPreview(projectId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查询预览容器状态，供前端轮询启动结果和异常退出。
     */
    @GetMapping("/projects/{projectId}/preview")
    public Result<PreviewStatus> previewStatus(@PathVariable String projectId) {
        try {
            return Result.success(projectPreviewService.getStatus(projectId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 显式停止预览容器并释放宿主机端口。
     */
    @DeleteMapping("/projects/{projectId}/preview")
    public Result<PreviewStatus> stopPreview(@PathVariable String projectId) {
        try {
            return Result.success(projectPreviewService.stopPreview(projectId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
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
