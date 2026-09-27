package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessMilestoneRefinerTest {
    private final BusinessMilestoneRefiner refiner = new BusinessMilestoneRefiner();

    /** 一个 Controller 承载完整 CRUD 时先验收创建查询，后验收修改删除。 */
    @Test
    void splitsLargeCrudAndRedirectsLaterDependencies() {
        var article = slice("article-crud", List.of(
                endpoint("GET", "/articles"), endpoint("GET", "/articles/new"),
                endpoint("POST", "/articles"), endpoint("GET", "/articles/{id}"),
                endpoint("GET", "/articles/{id}/edit"), endpoint("POST", "/articles/{id}"),
                endpoint("POST", "/articles/{id}/delete")), List.of());
        var comment = slice("comment-submit", List.of(endpoint("POST", "/articles/{id}/comments")),
                List.of("article-crud"));

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(article, comment), List.of(), "test"));

        assertEquals(List.of("article-crud-create-query", "article-crud-change-actions", "comment-submit"),
                refined.slices().stream().map(DeliverySlice::id).toList());
        assertEquals(4, refined.slices().get(0).contract().endpoints().size());
        assertEquals(3, refined.slices().get(1).contract().endpoints().size());
        assertEquals(List.of("article-crud-create-query"), refined.slices().get(1).dependsOnSlices());
        assertEquals(List.of("article-crud-change-actions"), refined.slices().get(2).dependsOnSlices());
    }

    /** 简单资源没有两个有意义的阶段时继续作为一个任务。 */
    @Test
    void keepsSmallResourceAsSingleMilestone() {
        var shortlink = slice("shortlink", List.of(endpoint("POST", "/api/links"),
                endpoint("GET", "/{code}"), endpoint("GET", "/api/links/{code}")), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(shortlink), List.of(), "test"));

        assertEquals(List.of("shortlink"), refined.slices().stream().map(DeliverySlice::id).toList());
    }

    /** 路径参数名称不同的前端调用仍随对应动作进入后续任务。 */
    @Test
    void preservesFrontendCallWithDifferentPathParameterName() {
        var endpoints = List.of(endpoint("POST", "/polls"), endpoint("GET", "/polls"),
                endpoint("POST", "/polls/{id}/votes"), endpoint("GET", "/polls/{id}/results"));
        var call = new FrontendCallContract("src/main/resources/static/app.js", "POST",
                "/polls/${pollId}/votes", "cast vote");
        var slice = new DeliverySlice("poll", "poll", List.of(), List.of(),
                new ProjectContract(endpoints, List.of(), List.of(call), List.of()), List.of(), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(slice), List.of(), "test"));

        assertEquals(2, refined.slices().size());
        assertEquals(List.of(call), refined.slices().get(1).contract().frontendCalls());
    }

    /** 无法归属的前端交互不能在拆分时从阶段契约中消失。 */
    @Test
    void keepsOriginalTaskWhenFrontendCallCannotBeAssigned() {
        var endpoints = List.of(endpoint("POST", "/polls"), endpoint("GET", "/polls"),
                endpoint("POST", "/polls/{id}/votes"), endpoint("GET", "/polls/{id}/results"));
        var call = new FrontendCallContract("src/main/resources/static/app.js", "POST", "/unknown", "unknown");
        var slice = new DeliverySlice("poll", "poll", List.of(), List.of(),
                new ProjectContract(endpoints, List.of(), List.of(call), List.of()), List.of(), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(slice), List.of(), "test"));

        assertEquals(List.of("poll"), refined.slices().stream().map(DeliverySlice::id).toList());
        assertEquals(List.of(call), refined.slices().getFirst().contract().frontendCalls());
    }

    /** 多页面资源按可核对的模板返回值拆开列表创建与详情，后续修改仍依赖详情。 */
    @Test
    void splitsLargePageResourceAtDetailViewBoundary() {
        var endpoints = List.of(
                new ApiEndpointContract("GET", "/", "", "articles/list.html", "ArticleController", ""),
                new ApiEndpointContract("GET", "/articles/new", "", "articles/form.html", "ArticleController", ""),
                endpoint("POST", "/articles"),
                new ApiEndpointContract("GET", "/articles/{id}", "", "articles/detail.html", "ArticleController", ""),
                endpoint("GET", "/articles/{id}/edit"), endpoint("POST", "/articles/{id}"));
        var views = List.of(
                new ViewContract("list", "src/main/resources/templates/articles/list.html", "ArticleController", ""),
                new ViewContract("form", "src/main/resources/templates/articles/form.html", "ArticleController", ""),
                new ViewContract("detail", "src/main/resources/templates/articles/detail.html", "ArticleController", ""),
                new ViewContract("error", "src/main/resources/templates/error.html", "GlobalExceptionHandler", ""));
        var article = new DeliverySlice("article", "article", List.of(), List.of(),
                new ProjectContract(endpoints, views, List.of(), List.of()), List.of(), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(article), List.of(), "test"));

        assertEquals(List.of("article-create-list", "article-detail-query", "article-change-actions"),
                refined.slices().stream().map(DeliverySlice::id).toList());
        assertEquals(3, refined.slices().get(0).contract().endpoints().size());
        assertEquals(3, refined.slices().get(0).contract().views().size());
        assertEquals(List.of("/articles/{id}"), refined.slices().get(1).contract().endpoints().stream()
                .map(ApiEndpointContract::path).toList());
        assertEquals(List.of("detail"), refined.slices().get(1).contract().views().stream()
                .map(ViewContract::name).toList());
        assertEquals(List.of("article-detail-query"), refined.slices().get(2).dependsOnSlices());
    }

    /** 页面按明确响应归属交付，编辑页和动作接口不得提前放进创建任务。 */
    @Test
    void assignsCreateDetailAndEditPagesToTheirEndpointMilestones() {
        var endpoints = List.of(
                new ApiEndpointContract("GET", "/articles", "", "articles/list.html view", "ArticleController", ""),
                new ApiEndpointContract("GET", "/articles/new", "", "articles/create.html view", "ArticleController", ""),
                new ApiEndpointContract("POST", "/articles", "", "articles/create.html on error", "ArticleController", ""),
                new ApiEndpointContract("GET", "/articles/{id}", "", "articles/detail.html view", "ArticleController", ""),
                new ApiEndpointContract("GET", "/articles/{id}/edit", "", "articles/edit.html view", "ArticleController", ""),
                new ApiEndpointContract("POST", "/articles/{id}", "", "articles/edit.html on error", "ArticleController", ""));
        var views = List.of(
                view("list"), view("create"), view("detail"), view("edit"));
        var article = new DeliverySlice("article", "article", List.of(), List.of(),
                new ProjectContract(endpoints, views, List.of(), List.of()), List.of(), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(article), List.of(), "test"));

        assertEquals(List.of("article-create-list", "article-detail-query", "article-change-actions"),
                refined.slices().stream().map(DeliverySlice::id).toList());
        assertEquals(List.of("list", "create"), refined.slices().get(0).contract().views().stream()
                .map(ViewContract::name).toList());
        assertEquals(List.of("detail"), refined.slices().get(1).contract().views().stream()
                .map(ViewContract::name).toList());
        assertEquals(List.of("edit"), refined.slices().get(2).contract().views().stream()
                .map(ViewContract::name).toList());
    }

    /** 无法从接口契约确定同一 Controller 的页面用途时，不强行拆分。 */
    @Test
    void keepsCoarseTaskWhenBusinessViewHasNoEndpointOwner() {
        var endpoints = List.of(
                new ApiEndpointContract("GET", "/articles", "", "", "ArticleController", ""),
                new ApiEndpointContract("POST", "/articles", "", "", "ArticleController", ""),
                new ApiEndpointContract("GET", "/articles/{id}/edit", "", "", "ArticleController", ""),
                new ApiEndpointContract("POST", "/articles/{id}", "", "", "ArticleController", ""));
        var article = new DeliverySlice("article", "article", List.of(), List.of(),
                new ProjectContract(endpoints, List.of(view("edit")), List.of(), List.of()),
                List.of(), List.of());

        var refined = refiner.refine(new SliceDeliveryPlan(List.of(article), List.of(), "test"));

        assertEquals(List.of("article"), refined.slices().stream().map(DeliverySlice::id).toList());
    }

    private ViewContract view(String name) {
        return new ViewContract(name, "src/main/resources/templates/articles/" + name + ".html",
                "ArticleController", "");
    }

    private DeliverySlice slice(String id, List<ApiEndpointContract> endpoints, List<String> dependencies) {
        return new DeliverySlice(id, id, List.of(), List.of(),
                new ProjectContract(endpoints, List.of(), List.of(), List.of()), dependencies, List.of());
    }

    private ApiEndpointContract endpoint(String method, String path) {
        return new ApiEndpointContract(method, path, "", "", "", "");
    }
}
