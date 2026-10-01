package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import org.jsoup.Jsoup;
import java.net.URI;
import java.util.*;

/** 小型表单项目的外部验收，检查实际 HTML、重定向、持久内容和删除。 */
final class NoteFormBehaviorChecks extends FixedHttpChecks {
    NoteFormBehaviorChecks(URI base) { super(base); }

    /** 从列表中的链接发现 ID，不假设数据库为空或编号从 1 开始。 */
    @Override BenchmarkBehaviorResult evaluate() {
        // 1. 核对列表与可提交的创建表单，随后观察真实保存结果。
        var list = form("GET", "/notes", Map.of());
        check("note.list", list, html(list), "200; HTML 列表");
        var form = form("GET", "/notes/new", Map.of());
        var doc = Jsoup.parse(form.body(), base.toString());
        boolean usableForm = doc.select("form[method]").stream().anyMatch(f -> f.attr("method").equalsIgnoreCase("post")
                && matchesPath(f.attr("action"), "/notes")
                && !f.select("[name=title]").isEmpty() && !f.select("[name=body]").isEmpty());
        check("note.form", form, form.status() == 200 && usableForm, "200; POST /notes 表单含 title/body 字段");
        String title = "Acceptance <Alpha>";
        String body = "Note <script>window.acceptanceProbe=1</script> content";
        var created = form("POST", "/notes", Map.of("title", title, "body", body));
        boolean createdOk = redirect(created, "/notes");
        check("note.create", created, createdOk, "302/303; Location=/notes");
        var listed = form("GET", "/notes", Map.of());
        var listedDoc = Jsoup.parse(listed.body());
        String path = listedDoc.select("a[href]").stream().filter(a -> a.text().equals(title))
                .map(a -> a.attr("href")).filter(h -> h.matches("/notes/\\d+")).findFirst().orElse("");
        boolean visible = createdOk && listed.status() == 200 && !path.isBlank();
        check("note.created.visible", listed, visible, "200; 标题正确转义且可进入详情");
        // 2. 非法提交不能改变已有条目；创建不可用时标记依赖检查阻塞。
        var invalid = form("POST", "/notes", Map.of("title", "", "body", "Rejected marker"));
        var invalidBody = form("POST", "/notes", Map.of("title", "Rejected title", "body", " "));
        check("note.invalid.create", invalid, invalid.status() == 400 && invalidBody.status() == 400
                && !Jsoup.parse(invalid.body()).select("[name=title]").isEmpty(), "400; 空标题或正文返回含字段的表单；" + evidence(invalidBody));
        var preserved = form("GET", "/notes", Map.of());
        check("note.invalid.preserves", preserved, preserved.status() == 200 && preserved.body().equals(listed.body()), "非法提交前后列表完全一致");
        if (!visible) {
            for (String name : List.of("detail", "escaped", "delete.form", "delete", "deleted.detail", "deleted.list", "missing.detail", "missing.delete"))
                blocked("note." + name, "note.created.visible");
            return BenchmarkBehaviorResult.fromFindings(findings);
        }
        var detail = form("GET", path, Map.of());
        // 3. 核对实际 HTML 的正文转义与删除地址，再独立验证删除的持久效果。
        var detailDoc = Jsoup.parse(detail.body());
        check("note.detail", detail, html(detail) && detailDoc.text().contains(title) && detailDoc.text().contains(body), "200; 保存并显示原始标题和正文");
        check("note.escaped", detail, detail.status() == 200 && detailDoc.select("script").stream()
                .noneMatch(s -> s.data().contains("acceptanceProbe")) && detailDoc.text().contains(body), "正文为文本；用户 script 不作为脚本标签输出");
        boolean deleteForm = detailDoc.select("form[method]").stream().anyMatch(f -> f.attr("method").equalsIgnoreCase("post")
                && matchesPath(f.attr("action"), path + "/delete"));
        check("note.delete.form", detail, detail.status() == 200 && deleteForm, "详情含 POST " + path + "/delete 表单");
        var deleted = form("POST", path + "/delete", Map.of());
        boolean deletedOk = redirect(deleted, "/notes");
        check("note.delete", deleted, deletedOk, "302/303; Location=/notes");
        if (deletedOk) {
            var gone = form("GET", path, Map.of());
            check("note.deleted.detail", gone, gone.status() == 404, "404; 已删除条目不可查询");
            var after = form("GET", "/notes", Map.of());
            check("note.deleted.list", after, after.status() == 200 && !Jsoup.parse(after.body()).text().contains(title), "删除后列表无原条目");
            var missingDelete = form("POST", "/notes/9223372036854775807/delete", Map.of());
            check("note.missing.delete", missingDelete, missingDelete.status() == 404, "404; 不存在条目不可删除");
        } else for (String name : List.of("deleted.detail", "deleted.list", "missing.delete")) blocked("note." + name, "note.delete");
        var missing = form("GET", "/notes/9223372036854775807", Map.of());
        check("note.missing.detail", missing, missing.status() == 404, "404; 不存在条目");
        return BenchmarkBehaviorResult.fromFindings(findings);
    }
    private boolean html(Reply reply) { return reply.status() == 200 && reply.body().toLowerCase(Locale.ROOT).contains("<html"); }
}
