package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import java.net.URI;
import java.util.List;

/** 新版投票判卷包含身份、非法选项及状态不变，旧版规则保持原样。 */
final class PollWorkflowBehaviorChecks extends FixedHttpChecks {
    PollWorkflowBehaviorChecks(URI base) { super(base); }

    /** 从返回 ID 建立独立投票，再观察每次操作后的计数。 */
    @Override BenchmarkBehaviorResult evaluate() {
        // 1. 创建独立投票并从真实响应取得身份，避免依赖空库或固定编号。
        var created = jsonRequest("POST", "/api/polls", "{\"question\":\"Acceptance poll\",\"options\":[\"A\",\"B\"]}");
        String id = json(created).path("id").asText("");
        boolean usable = created.status() == 201 && !id.isBlank();
        check("poll.create", created, usable, "201; JSON 含非空 id");
        if (!usable) {
            for (String name : List.of("initial", "vote.first", "result.first", "vote.duplicate", "result.afterDuplicate", "vote.duplicate.repeat", "result.afterRepeatedDuplicate",
                    "vote.invalid.option", "vote.invalid.identity", "result.afterInvalid", "vote.second", "result.final", "identity.otherPoll", "missing.vote", "missing.result"))
                blocked("poll." + name, "poll.create");
            return BenchmarkBehaviorResult.fromFindings(findings);
        }
        String poll = "/api/polls/" + id;
        // 2. 顺序观察投票、连续拒绝和非法请求后的状态，不只检查单个状态码。
        var initial = jsonRequest("GET", poll + "/results", null);
        check("poll.initial", initial, counts(initial, 0, 0), "200; counts={A:0,B:0}");
        boolean firstVotePassed = vote("poll.vote.first", poll, "alice", "A", 200);
        var first = jsonRequest("GET", poll + "/results", null);
        check("poll.result.first", first, counts(first, 1, 0), "200; counts={A:1,B:0}");
        vote("poll.vote.duplicate", poll, "alice", "B", 409);
        var duplicate = jsonRequest("GET", poll + "/results", null);
        check("poll.result.afterDuplicate", duplicate, counts(duplicate, 1, 0), "重复投票后状态不变");
        // 被拒绝的重复请求不能撤销原身份；只验一次409会漏掉“下次又能投”的缺陷。
        vote("poll.vote.duplicate.repeat", poll, "alice", "A", 409);
        var repeated = jsonRequest("GET", poll + "/results", null);
        check("poll.result.afterRepeatedDuplicate", repeated, counts(repeated, 1, 0), "连续拒绝后仍为 counts={A:1,B:0}");
        vote("poll.vote.invalid.option", poll, "carol", "C", 400);
        var blank = jsonRequest("POST", poll + "/votes", "{\"voterId\":\" \",\"option\":\"A\"}");
        var nullIdentity = jsonRequest("POST", poll + "/votes", "{\"voterId\":null,\"option\":\"A\"}");
        check("poll.vote.invalid.identity", blank, blank.status() == 400 && nullIdentity.status() == 400,
                "400; 空白/null 身份均拒绝；" + evidence(nullIdentity));
        var invalid = jsonRequest("GET", poll + "/results", null);
        check("poll.result.afterInvalid", invalid, counts(invalid, 1, 0), "非法身份和选项不增加计数");
        // 同一个身份的非法请求不应占用它的投票资格。
        vote("poll.vote.second", poll, "carol", "B", 200);
        var last = jsonRequest("GET", poll + "/results", null);
        check("poll.result.final", last, counts(last, 1, 1), "200; counts={A:1,B:1}");
        var other = jsonRequest("POST", "/api/polls", "{\"question\":\"Other poll\",\"options\":[\"A\",\"B\"]}");
        // 3. 身份去重限定在单个投票内，并在正向能力存在后判断缺失资源。
        String otherId = json(other).path("id").asText("");
        if (other.status() == 201 && !otherId.isBlank()) {
            var otherVote = jsonRequest("POST", "/api/polls/" + otherId + "/votes", "{\"voterId\":\"alice\",\"option\":\"B\"}");
            var otherCounts = jsonRequest("GET", "/api/polls/" + otherId + "/results", null);
            check("poll.identity.otherPoll", otherVote, otherVote.status() == 200 && counts(otherCounts, 0, 1),
                    "同一身份可在另一投票投票；" + evidence(otherCounts));
        } else blocked("poll.identity.otherPoll", "other poll creation");
        // 没有投票端点时也会返回404，不能把它算作资源不存在的正确处理。
        if (firstVotePassed) vote("poll.missing.vote", "/api/polls/9223372036854775807", "bob", "A", 404);
        else blocked("poll.missing.vote", "poll.vote.first");
        var missing = jsonRequest("GET", "/api/polls/9223372036854775807/results", null);
        check("poll.missing.result", missing, missing.status() == 404, "404; 不存在的投票");
        return BenchmarkBehaviorResult.fromFindings(findings);
    }
    private boolean vote(String name, String poll, String voter, String option, int status) {
        var reply = jsonRequest("POST", poll + "/votes", "{\"voterId\":\"" + voter + "\",\"option\":\"" + option + "\"}");
        check(name, reply, reply.status() == status, "HTTP " + status);
        return reply.status() == status;
    }
    private boolean counts(Reply reply, int a, int b) {
        return reply.status() == 200 && json(reply).path("counts").path("A").asInt(-1) == a
                && json(reply).path("counts").path("B").asInt(-1) == b;
    }
}
