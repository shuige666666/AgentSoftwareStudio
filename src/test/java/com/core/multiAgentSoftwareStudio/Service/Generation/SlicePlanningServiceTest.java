package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlicePlanningServiceTest {

    private final SlicePlanningService service = new SlicePlanningService(new SourceCodePathService());

    /**
     * 同一业务能力跨越多层的文件必须进入同一个切片，并把项目骨架放入首片共享范围。
     */
    @Test
    void groupsVerticalCapabilityAndAttachesBootstrapFiles() {
        var pom = file("pom.xml", "base", "bootstrap", List.of());
        var application = file("src/main/java/com/example/AppApplication.java", "base", "bootstrap", List.of());
        var serviceFile = file("src/main/java/com/example/PollService.java", "service", "poll-voting", List.of());
        var controller = file("src/main/java/com/example/PollController.java", "controller", "poll-voting",
                List.of(serviceFile.targetPath()));
        var resultController = file("src/main/java/com/example/ResultController.java", "controller", "poll-results",
                List.of(serviceFile.targetPath()));
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.AppApplication",
                List.of(pom, application, serviceFile, controller, resultController));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/polls/{id}/votes", "VoteRequest", "Poll", "PollController", "vote"),
                new ApiEndpointContract("GET", "/polls/{id}/results", "", "Poll", "ResultController", "results")),
                List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(2, plan.slices().size());
        assertTrue(plan.slices().getFirst().ownedFiles().contains("pom.xml"));
        assertEquals(2, plan.sharedFiles().size());
        assertTrue(plan.slices().stream().anyMatch(slice -> slice.contract().endpoints().stream()
                .anyMatch(endpoint -> endpoint.path().contains("votes"))));
    }

    /**
     * 架构仍按技术层填写 batchName 时安全退化为单个完整切片，不能继续生成瀑布式层批次。
     */
    @Test
    void collapsesTechnicalLayerBatchesIntoOneFeatureSlice() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/TaskService.java", "service", "service", List.of()),
                file("src/main/java/com/example/TaskController.java", "controller", "controller", List.of())));

        var plan = service.createPlan(structure, null);

        assertEquals(1, plan.slices().size());
        assertEquals(2, plan.slices().getFirst().files().size());
    }

    /**
     * 中文切片名规范化为空时仍应得到唯一 ID，避免修复预算与 Journal 归属混淆。
     */
    @Test
    void assignsUniqueIdsToNonAsciiSliceNames() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/PollController.java", "controller", "投票", List.of()),
                file("src/main/java/com/example/ResultController.java", "controller", "结果", List.of())));

        var plan = service.createPlan(structure, null);

        assertEquals(2, plan.slices().size());
        assertEquals("project-feature", plan.slices().get(0).id());
        assertEquals("project-feature-2", plan.slices().get(1).id());
    }

    /**
     * Controller、Services 等单复数技术层名称都必须折叠，不能形成瀑布式伪切片。
     */
    @Test
    void collapsesPluralAndLayerStyleTechnicalNames() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/TaskController.java", "controller", "Controllers", List.of()),
                file("src/main/java/com/example/TaskService.java", "service", "Service Layer", List.of()),
                file("src/main/java/com/example/TaskRepository.java", "repository", "Repositories", List.of())));

        var plan = service.createPlan(structure, null);

        assertEquals(1, plan.slices().size());
        assertEquals(3, plan.slices().getFirst().files().size());
    }

    /**
     * 综合前端必须延后到所有后端切片之后，REST 子资源切片也必须依赖父资源切片。
     */
    @Test
    void ordersParentResourceBeforeChildAndDefersFrontendIntegration() {
        var pollController = file(
                "src/main/java/com/example/PollController.java", "controller", "poll-creation", List.of());
        var voteController = file(
                "src/main/java/com/example/VoteController.java", "controller", "poll-voting", List.of());
        var appJs = file("src/main/resources/static/app.js", "frontend", "poll-creation", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(pollController, voteController, appJs));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/api/polls", "", "", "PollController", "create"),
                new ApiEndpointContract("POST", "/api/polls/{id}/votes", "", "", "VoteController", "vote")),
                List.of(),
                List.of(new FrontendCallContract(
                        appJs.targetPath(), "POST", "/api/polls/{id}/votes", "vote from browser")),
                List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(List.of("poll-creation", "poll-voting", "frontend-integration"),
                plan.slices().stream().map(slice -> slice.id()).toList());
        assertEquals(List.of("poll-creation"), plan.slices().get(1).dependsOnSlices());
        assertEquals(List.of("poll-creation", "poll-voting"), plan.slices().get(2).dependsOnSlices());
        assertTrue(plan.slices().getFirst().contract().frontendCalls().isEmpty());
        assertEquals(1, plan.slices().get(2).contract().frontendCalls().size());
    }

    /**
     * 契约元数据不完整时，同领域核心能力仍应排在评论、投票等附属能力之前。
     */
    @Test
    void infersBusinessCapabilityOrderWithoutContractOwnerMetadata() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/CommentController.java", "controller", "article-comments", List.of()),
                file("src/main/java/com/example/ArticleController.java", "controller", "article-publishing", List.of())));

        var plan = service.createPlan(structure, null);

        assertEquals(List.of("article-publishing", "article-comments"),
                plan.slices().stream().map(slice -> slice.id()).toList());
        assertEquals(List.of("article-publishing"), plan.slices().get(1).dependsOnSlices());
    }

    /**
     * 一个 Controller 同时承载多个能力时必须合并后端切片，避免后续契约修复提前引用未来 DTO。
     */
    @Test
    void mergesCapabilitiesImplementedByOneController() {
        var controller = file(
                "src/main/java/com/example/PollController.java", "controller", "poll-creation", List.of());
        var creationDto = file(
                "src/main/java/com/example/CreatePollRequest.java", "base", "poll-creation", List.of());
        var listingDto = file(
                "src/main/java/com/example/PollSummary.java", "base", "poll-listing", List.of());
        var voteDto = file(
                "src/main/java/com/example/CastVoteRequest.java", "base", "poll-voting", List.of());
        var appJs = file("src/main/resources/static/app.js", "frontend", "poll-creation", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App",
                List.of(controller, creationDto, listingDto, voteDto, appJs));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/api/polls", "CreatePollRequest", "", "PollController", "create poll"),
                new ApiEndpointContract("GET", "/api/polls", "", "PollSummary", "PollController", "list polls"),
                new ApiEndpointContract("POST", "/api/polls/{id}/vote", "CastVoteRequest", "", "PollController", "vote")),
                List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(2, plan.slices().size());
        assertEquals("poll-backend", plan.slices().getFirst().id());
        assertEquals(4, plan.slices().getFirst().files().size());
        assertEquals(3, plan.slices().getFirst().contract().endpoints().size());
        assertEquals("frontend-integration", plan.slices().get(1).id());
        assertEquals(List.of("poll-backend"), plan.slices().get(1).dependsOnSlices());
    }

    /**
     * 只有一个真实 Controller 时，错误或缺失的契约归属也必须合并相关能力。
     */
    @Test
    void mergesOwnerlessCapabilitiesWhenOnlyOneControllerExists() {
        var controller = file(
                "src/main/java/com/example/PollController.java", "controller", "poll-creation", List.of());
        var resultDto = file(
                "src/main/java/com/example/PollResultResponse.java", "base", "poll-results", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(controller, resultDto));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/api/polls", "", "", "PollController", "create poll"),
                new ApiEndpointContract("GET", "/api/polls/{id}/results", "", "PollResultResponse",
                        "MissingResultsController", "view results")), List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(1, plan.slices().size());
        assertEquals("poll-backend", plan.slices().getFirst().id());
        assertEquals(2, plan.slices().getFirst().contract().endpoints().size());
        assertEquals(List.of(controller, resultDto), plan.slices().getFirst().files());
    }

    /**
     * 领域前缀不一致时，评论等附属能力仍应依赖更基础的管理能力。
     */
    @Test
    void infersFallbackOrderAcrossDifferentDomainPrefixes() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/CommentController.java", "controller", "article-comments", List.of()),
                file("src/main/java/com/example/ArticleController.java", "controller", "blog-management", List.of())));

        var plan = service.createPlan(structure, null);

        assertEquals(List.of("blog-management", "article-comments"),
                plan.slices().stream().map(slice -> slice.id()).toList());
        assertEquals(List.of("blog-management"), plan.slices().get(1).dependsOnSlices());
    }

    /**
     * Thymeleaf 模板属于服务端业务切片，不能和静态 SPA 资源一样被强制拆到最终集成片。
     */
    @Test
    void keepsServerRenderedTemplateWithBusinessSlice() {
        var controller = file(
                "src/main/java/com/example/ArticleController.java", "controller", "article-publishing", List.of());
        var template = file(
                "src/main/resources/templates/article.html", "frontend", "article-publishing", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(controller, template));

        var plan = service.createPlan(structure, null);

        assertEquals(1, plan.slices().size());
        assertEquals(List.of(controller, template), plan.slices().getFirst().files());
    }

    /**
     * 契约没有可靠 implementedBy 时，端点仍应按业务语义进入对应的非首切片。
     */
    @Test
    void assignsOwnerlessAndUnknownOwnerEndpointsByCapability() {
        ProjectStructure structure = new ProjectStructure("com.example", "SPRING_BOOT", "com.example.App", List.of(
                file("src/main/java/com/example/ArticleController.java", "controller", "article-publishing", List.of()),
                file("src/main/java/com/example/CommentController.java", "controller", "article-comments", List.of())));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract(
                        "POST", "/api/articles", "ArticleRequest", "Article", "MissingController", "publish article"),
                new ApiEndpointContract(
                        "POST", "/api/articles/{id}/comments", "CommentRequest", "Comment", "", "add comment")),
                List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(List.of("article-publishing", "article-comments"),
                plan.slices().stream().map(slice -> slice.id()).toList());
        assertEquals(List.of("/api/articles"), plan.slices().get(0).contract().endpoints().stream()
                .map(ApiEndpointContract::path).toList());
        assertEquals(List.of("/api/articles/{id}/comments"), plan.slices().get(1).contract().endpoints().stream()
                .map(ApiEndpointContract::path).toList());
    }

    /**
     * 模型给出的反向 dependsOn 不得和能力排序构成环并把评论切片排到文章核心之前。
     */
    @Test
    void removesReverseCapabilityDependencyBeforeTopologicalSort() {
        var article = file(
                "src/main/java/com/example/ArticleController.java", "controller", "article-management",
                List.of("src/main/java/com/example/CommentController.java"));
        var comment = file(
                "src/main/java/com/example/CommentController.java", "controller", "article-comments",
                List.of(article.targetPath()));
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(comment, article));

        var plan = service.createPlan(structure, null);

        assertEquals(List.of("article-management", "article-comments"),
                plan.slices().stream().map(slice -> slice.id()).toList());
        assertEquals(List.of("article-management"), plan.slices().get(1).dependsOnSlices());
    }

    /**
     * 服务端模板复用多个能力时，当前切片契约不能把未来端点调用提前交给 Developer。
     */
    @Test
    void scopesServerTemplateCallsToCurrentCapability() {
        var articleController = file(
                "src/main/java/com/example/ArticleController.java", "controller", "article-publishing", List.of());
        var commentController = file(
                "src/main/java/com/example/CommentController.java", "controller", "article-commenting", List.of());
        var detailTemplate = file(
                "src/main/resources/templates/article-detail.html", "frontend", "article-commenting", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App",
                List.of(commentController, detailTemplate, articleController));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/articles", "", "", "ArticleController", "publish"),
                new ApiEndpointContract("POST", "/articles/{id}/delete", "", "", "ArticleController", "delete"),
                new ApiEndpointContract("POST", "/articles/{id}/comments", "", "", "CommentController", "comment")),
                List.of(),
                List.of(
                        new FrontendCallContract(detailTemplate.targetPath(), "POST",
                                "/articles/{id}/delete", "delete article"),
                        new FrontendCallContract(detailTemplate.targetPath(), "POST",
                                "/articles/{id}/comments", "add comment")),
                List.of());

        var plan = service.createPlan(structure, contract);
        var comments = plan.slices().stream()
                .filter(slice -> slice.id().equals("article-commenting")).findFirst().orElseThrow();

        assertEquals(List.of("/articles/{id}/comments"), comments.contract().frontendCalls().stream()
                .map(FrontendCallContract::path).toList());
        assertEquals(List.of("article-publishing"), comments.dependsOnSlices());
    }

    /**
     * 返回 VoteResultResponse 的投票动作仍只属于投票切片，不能重复进入结果切片。
     */
    @Test
    void doesNotClassifyVoteResultResponseAsResultsCapability() {
        var voteController = file(
                "src/main/java/com/example/VoteController.java", "controller", "poll-voting", List.of());
        var resultController = file(
                "src/main/java/com/example/ResultController.java", "controller", "poll-results", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(voteController, resultController));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("POST", "/api/polls/{id}/vote", "CastVoteRequest",
                        "VoteResultResponse", "", "cast vote"),
                new ApiEndpointContract("GET", "/api/polls/{id}/results", "",
                        "PollResultsResponse", "", "view results")), List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        var voting = plan.slices().stream().filter(slice -> slice.id().equals("poll-voting")).findFirst().orElseThrow();
        var results = plan.slices().stream().filter(slice -> slice.id().equals("poll-results")).findFirst().orElseThrow();
        assertEquals(List.of("/api/polls/{id}/vote"),
                voting.contract().endpoints().stream().map(ApiEndpointContract::path).toList());
        assertEquals(List.of("/api/polls/{id}/results"),
                results.contract().endpoints().stream().map(ApiEndpointContract::path).toList());
    }

    /**
     * 唯一业务切片显式依赖的基础 DTO 应与消费者同片交付，避免形成未来类型引用。
     */
    @Test
    void coLocatesExplicitSingleConsumerSupportDependency() {
        var metadata = file(
                "src/main/java/com/example/dto/UrlMetadataResponse.java", "base", "url-metadata", List.of());
        var serviceFile = file(
                "src/main/java/com/example/service/UrlService.java", "service", "url-shortening",
                List.of(metadata.targetPath()));
        var controller = file(
                "src/main/java/com/example/controller/UrlController.java", "controller", "url-shortening",
                List.of(serviceFile.targetPath()));
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App",
                List.of(metadata, serviceFile, controller));

        var plan = service.createPlan(structure, null);

        assertEquals(1, plan.slices().size());
        assertTrue(plan.slices().getFirst().ownedFiles().contains(metadata.targetPath()));
        assertTrue(plan.slices().getFirst().ownedFiles().contains(serviceFile.targetPath()));
    }

    /**
     * 契约声明的请求或响应 DTO 应跟随端点实现切片，即使 Architect 没有填写 dependsOn。
     */
    @Test
    void coLocatesContractSupportTypeWithoutExplicitDependency() {
        var metadata = file(
                "src/main/java/com/example/dto/UrlMetadataResponse.java", "dto", "url-metadata", List.of());
        var controller = file(
                "src/main/java/com/example/controller/UrlController.java", "controller", "url-shortening", List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(metadata, controller));
        ProjectContract contract = new ProjectContract(List.of(
                new ApiEndpointContract("GET", "/api/urls/{code}", "", "UrlMetadataResponse",
                        "UrlController", "read metadata")), List.of(), List.of(), List.of());

        var plan = service.createPlan(structure, contract);

        assertEquals(1, plan.slices().size());
        assertTrue(plan.slices().getFirst().ownedFiles().contains(metadata.targetPath()));
        assertTrue(plan.slices().getFirst().ownedFiles().contains(controller.targetPath()));
    }

    /**
     * 蓝图方法签名中的基础类型引用也应形成依赖，防止生成当前切片时引用尚未交付的类型。
     */
    @Test
    void coLocatesBlueprintReferencedSupportTypeWithoutExplicitDependency() {
        var metadata = file(
                "src/main/java/com/example/dto/UrlMetadataResponse.java", "base", "url-metadata", List.of());
        var serviceFile = new FileBlueprint(
                "UrlService.java", "src/main/java/com/example/service/UrlService.java",
                "service", "url-shortening", "查询短链接元数据",
                List.of("UrlMetadataResponse findMetadata(String shortCode)"), List.of());
        ProjectStructure structure = new ProjectStructure(
                "com.example", "SPRING_BOOT", "com.example.App", List.of(metadata, serviceFile));

        var plan = service.createPlan(structure, null);

        assertEquals(1, plan.slices().size());
        assertTrue(plan.slices().getFirst().ownedFiles().contains(metadata.targetPath()));
        assertTrue(plan.slices().getFirst().ownedFiles().contains(serviceFile.targetPath()));
    }

    private FileBlueprint file(String path, String layer, String batchName, List<String> dependencies) {
        return new FileBlueprint(
                java.nio.file.Path.of(path).getFileName().toString(), path, layer, batchName,
                "test", List.of(), dependencies);
    }
}
