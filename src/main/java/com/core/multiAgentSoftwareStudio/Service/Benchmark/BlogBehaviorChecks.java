package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 迁移已核对的博客表单公开契约；只作为版本化判卷，不参与通用项目的任务划分。 */
final class BlogBehaviorChecks {
    private static final Pattern ARTICLE_LINK = Pattern.compile("href=[\"']/articles/(\\d+)[\"']");
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    private final List<BenchmarkBehaviorFinding> findings = new ArrayList<>();
    private final Map<String, String> dependencies = new LinkedHashMap<>();
    private final List<String> needsArticle = new ArrayList<>();
    private String articleId = "";

    BlogBehaviorChecks(URI base) { this.base = base; }

    /** 顺序验证 CRUD、评论、搜索和错误状态；依赖失败不能变成删除或 404 成功证据。 */
    BenchmarkBehaviorResult evaluate() {
        Reply list = request("GET", "/articles", Map.of());
        check("list-page", list, list.status == 200 && list.body.toLowerCase().contains("<html"), "200; 可使用的列表 HTML");
        Reply form = request("GET", "/articles/new", Map.of());
        check("create-form", form, form.status == 200 && field(form, "title") && field(form, "content"), "200; title/content 表单字段");
        Reply created = request("POST", "/articles", Map.of("title", "Acceptance Alpha", "content", "UniqueAlphaContent"));
        check("create-redirect", created, redirect(created, "/articles"), "302/303; Location=/articles");
        list = request("GET", "/articles", Map.of());
        check("created-visible", list, list.status == 200 && list.body.contains("Acceptance Alpha"), "200; 显示新文章标题");
        // 从真实页面发现 ID；不假定从 1 开始，避免历史数据或实现分配方式造成误判。
        var links = ARTICLE_LINK.matcher(list.body);
        if (links.find()) articleId = links.group(1);
        String article = "/articles/" + (articleId.isBlank() ? "__unresolved__" : articleId);
        Reply detail = articleRequest("GET", article, Map.of());
        articleCheck("detail-content-comment-form", detail, detail.status == 200 && detail.body.contains("UniqueAlphaContent")
                && field(detail, "nickname") && detail.body.contains(article + "/comments"), "200; 文章内容及 nickname 评论表单");
        Reply edit = articleRequest("GET", article + "/edit", Map.of());
        articleCheck("edit-prefilled", edit, edit.status == 200 && edit.body.contains("Acceptance Alpha")
                && edit.body.contains("UniqueAlphaContent"), "200; 编辑表单预填标题和内容");
        Reply update = articleRequest("POST", article, Map.of("title", "Acceptance Beta", "content", "UniqueBetaContent"));
        articleCheck("update-redirect", update, redirect(update, article), "302/303; Location=" + article);
        Reply updated = articleRequest("GET", article, Map.of());
        articleCheck("updated-persisted", updated, updated.status == 200 && updated.body.contains("Acceptance Beta")
                && updated.body.contains("UniqueBetaContent") && !updated.body.contains("UniqueAlphaContent"), "200; 修改已保存且旧内容消失");
        Reply comment = articleRequest("POST", article + "/comments", Map.of("nickname", "Reviewer", "content", "Independent comment content"));
        articleCheck("comment-redirect", comment, redirect(comment, article), "302/303; Location=" + article);
        Reply commented = articleRequest("GET", article, Map.of());
        articleCheck("comment-persisted", commented, commented.status == 200 && commented.body.contains("Independent comment content")
                && commented.body.contains("Reviewer"), "200; 评论内容和昵称已保存");
        Reply search = request("GET", "/articles?keyword=UniqueBetaContent", Map.of());
        check("search-match", search, search.status == 200 && search.body.contains("Acceptance Beta"), "200; 按内容搜索命中修改后的文章");
        search = request("GET", "/articles?keyword=AbsentSearchToken", Map.of());
        check("search-excludes", search, search.status == 200 && !search.body.contains("Acceptance Beta"), "200; 无匹配时排除已有文章");
        Reply invalid = request("POST", "/articles", Map.of("title", "bad", "content", ""));
        check("invalid-create-400", invalid, invalid.status == 400 && field(invalid, "title"), "400; 保留创建表单");
        invalid = articleRequest("POST", article, Map.of("title", "bad", "content", ""));
        articleCheck("invalid-update-400", invalid, invalid.status == 400, "400; 非法修改被拒绝");
        Reply preserved = articleRequest("GET", article, Map.of());
        articleCheck("invalid-update-preserves-state", preserved, preserved.status == 200 && preserved.body.contains("UniqueBetaContent"), "200; 非法修改不改变原内容");
        invalid = articleRequest("POST", article + "/comments", Map.of("nickname", "x", "content", "bad"));
        articleCheck("invalid-comment-400", invalid, invalid.status == 400, "400; 非法昵称或评论内容被拒绝");
        for (String path : List.of("/articles/99999999", "/articles/99999999/edit")) {
            Reply missing = request("GET", path, Map.of());
            check("missing-GET-" + path, missing, missing.status == 404, "404; 不存在的文章");
        }
        for (String path : List.of("/articles/99999999", "/articles/99999999/delete", "/articles/99999999/comments")) {
            Reply missing = request("POST", path, Map.of("title", "Valid title", "content", "Valid missing content", "nickname", "Reviewer"));
            check("missing-POST-" + path, missing, missing.status == 404, "404; 不存在的文章");
        }
        Reply deleted = articleRequest("POST", article + "/delete", Map.of());
        articleCheck("delete-redirect", deleted, redirect(deleted, "/articles"), "302/303; Location=/articles");
        Reply gone = articleRequest("GET", article, Map.of());
        articleCheck("delete-effective", gone, gone.status == 404, "404; 已删除文章不可查询");
        Reply finalList = request("GET", "/articles", Map.of());
        check("deleted-not-listed", finalList, finalList.status == 200 && !finalList.body.contains("Acceptance Beta"), "200; 已删除文章不再列出");
        // 与既有 24 项独立脚本保持同一依赖口径，缺少正向能力时 404 不能算通过。
        for (String name : List.of("search-match", "search-excludes", "invalid-update-preserves-state")) dependencies.put(name, "updated-persisted");
        dependencies.put("missing-GET-/articles/99999999", "detail-content-comment-form");
        dependencies.put("missing-GET-/articles/99999999/edit", "edit-prefilled");
        dependencies.put("missing-POST-/articles/99999999", "update-redirect");
        dependencies.put("missing-POST-/articles/99999999/delete", "delete-redirect");
        dependencies.put("missing-POST-/articles/99999999/comments", "comment-redirect");
        for (String name : List.of("delete-effective", "deleted-not-listed")) dependencies.put(name, "delete-redirect");
        var observed = findings.stream().collect(Collectors.toMap(BenchmarkBehaviorFinding::behavior, BenchmarkBehaviorFinding::passed));
        var resolved = findings.stream().map(finding -> {
            String prerequisite = articleId.isBlank() && needsArticle.contains(finding.behavior()) ? "created-visible/文章链接"
                    : dependencies.get(finding.behavior());
            if (prerequisite != null && !observed.getOrDefault(prerequisite, false)) {
                return new BenchmarkBehaviorFinding(finding.behavior(), false, "Blocked by " + prerequisite,
                        "BLOCKED", finding.request(), finding.expected());
            }
            return finding;
        }).toList();
        return BenchmarkBehaviorResult.fromFindings(resolved);
    }

    /** 无可解析 ID 时不发送伪造资源请求，后续检查明确记为阻塞。 */
    private Reply articleRequest(String method, String path, Map<String, String> form) {
        return articleId.isBlank() ? new Reply(0, "没有从列表获取文章 ID", "", method + " " + path) : request(method, path, form);
    }

    /** 使用真实 HTTP 表单与会话，禁用自动重定向以保留公开状态码。 */
    private Reply request(String method, String path, Map<String, String> form) {
        String input = form.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
        String request = method + " " + path + ("POST".equals(method) ? "; form=" + input : "");
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));
            if ("POST".equals(method)) builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(input));
            else builder.GET();
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String location = response.headers().firstValue("location").orElse("").replaceAll(";jsessionid=[^?/#]+", "");
            return new Reply(response.statusCode(), response.body(), location, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, "HTTP 请求被中断", "", request);
        } catch (Exception e) {
            return new Reply(0, e.getClass().getSimpleName() + ": " + e.getMessage(), "", request);
        }
    }

    /** 保存实际响应及预期，完整 HTML 由候选服务可重复观察，反馈只保留有界正文。 */
    private void check(String name, Reply response, boolean passed, String expected) {
        String body = response.body.replaceAll("\\s+", " ");
        findings.add(new BenchmarkBehaviorFinding(name, passed, "HTTP " + response.status + "; Location=" + response.location
                + "; body=" + body.substring(0, Math.min(300, body.length())), passed ? "PASS" : "FAIL", response.request, expected));
    }

    private void articleCheck(String name, Reply response, boolean passed, String expected) {
        needsArticle.add(name);
        check(name, response, passed, expected);
    }

    /** 页面表单允许单引号、双引号及空格，不绑定某个模板排版。 */
    private boolean field(Reply response, String name) {
        return Pattern.compile("name\\s*=\\s*[\"']" + Pattern.quote(name) + "[\"']").matcher(response.body).find();
    }

    private boolean redirect(Reply response, String path) {
        return (response.status == 302 || response.status == 303) && !response.location.isBlank()
                && base.resolve(response.location).getPath().equals(path);
    }

    private String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private record Reply(int status, String body, String location, String request) { }
}
