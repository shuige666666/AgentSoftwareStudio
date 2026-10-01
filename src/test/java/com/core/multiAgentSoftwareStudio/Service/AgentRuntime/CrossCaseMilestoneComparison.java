package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Generation.*;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.*;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import java.nio.file.Path;
import java.util.*;

/** 第四步仅扩充两类固定案例；共用现有对照引擎，不由单元测试发起远程调用。 */
public final class CrossCaseMilestoneComparison {
    /** 每个新案例各一组；参数只能选择预检或固定的一组，不提供无限重试编号。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("poll", "note", "preflight-poll", "preflight-note").contains(args[1]))
            throw new IllegalArgumentException("Usage: CrossCaseMilestoneComparison <log-root> <poll|note|preflight-poll|preflight-note>");
        boolean poll = args[1].endsWith("poll");
        var spec = new TaskMilestoneComparison.CaseSpec(poll ? "poll-workflow-behavior-v1" : "note-form-behavior-v1",
                Path.of("src/test/resources/benchmark", poll ? "poll-workflow-v1" : "note-form-v1"),
                poll ? "poll_cross" : "note_cross", poll ? "poll-base" : "note-base",
                poll ? "src/test/java/com/example/polls/PollBaselineTest.java" : "src/test/java/com/example/notes/NoteBaselineTest.java",
                poll ? "Create/query/results; vote persistence, identity per poll, duplicate rejection, invalid request state preservation"
                        : "List/form, submit, escaped HTML, invalid input preservation, detail/delete and missing resources",
                poll ? CrossCaseMilestoneComparison::pollData : CrossCaseMilestoneComparison::noteData);
        TaskMilestoneComparison.run(Path.of(args[0]), args[1], spec, poll
                ? List.of(DevelopmentMilestonePolicy.ORDERED, DevelopmentMilestonePolicy.ADVISORY)
                : List.of(DevelopmentMilestonePolicy.ADVISORY, DevelopmentMilestonePolicy.ORDERED));
    }

    /** 固定投票需求，两个待交付能力共用投票状态和同一个端点。 */
    static SoftwareStudioWorkflowData pollData(Path root, DevelopmentMilestonePolicy policy) {
        String request = "Java17 Spring Boot poll REST API; preserve existing create/query/results and valid tests. "
                + "POST /api/polls JSON {question,options}:201 {id,question,options}; invalid question or options:400. "
                + "GET /api/polls/{id}:200 poll or404; GET /api/polls/{id}/results:200 {counts:{option:integer}} or404. "
                + "Implement POST /api/polls/{id}/votes JSON {voterId,option}:200 on first valid vote; missing poll404. "
                + "Persist votes in counts. Reject null/blank identity or unknown option400, duplicate voter in same poll409. "
                + "Rejected requests do not change counts or consume identity; same voter may vote in another poll. "
                + "Keep meaningful baseline tests, add behavior tests and update README. Use existing memory store, port8080, no extra features.";
        var data = base(root, policy, "poll-workflow-behavior-v1", request, "com.example.polls", "PollsApplication", "PollController", "PollStore");
        var create = endpoint("POST", "/api/polls", "com.example.polls.PollRequest", "", "201 or400");
        var query = endpoint("GET", "/api/polls/{id}", "", "", "200 or404");
        var results = endpoint("GET", "/api/polls/{id}/results", "", "", "200 counts or404");
        var vote = endpoint("POST", "/api/polls/{id}/votes", "com.example.polls.VoteRequest", "", "200 persist; unknown poll404; invalid identity/option400; duplicate409; rejected state unchanged");
        var basicVote = endpoint("POST", "/api/polls/{id}/votes", "com.example.polls.VoteRequest", "", "200 persist valid first vote; unknown poll404; invalid identity/option400");
        data.contract = contract(List.of(create, query, results, vote), List.of());
        plan(data, List.of(slice(data, "poll-base", "Existing poll creation and results", List.of(create, query, results), List.of(), List.of()),
                slice(data, "poll-vote", "Accept and count valid votes", List.of(basicVote), List.of(), List.of("poll-base")),
                slice(data, "poll-identity", "Reject duplicate votes without changing state", List.of(vote), List.of(), List.of("poll-vote"))));
        return data;
    }

    /** 表单项目按可操作页面分配能力，未来的删除表单只属于删除任务。 */
    static SoftwareStudioWorkflowData noteData(Path root, DevelopmentMilestonePolicy policy) {
        String request = "Java17 Spring Boot Thymeleaf note form app. Preserve existing GET /notes list, GET /notes/new title/body form, "
                + "GET /notes/{id} detail200 or404 and valid baseline tests. Implement POST /notes form-urlencoded title/body: "
                + "302 or303 Location=/notes; persist original nonblank title/body. Null/blank title/body400 with form fields and no state mutation. "
                + "All user text must be escaped in list/detail. Add detail POST /notes/{id}/delete form, and handler: "
                + "302 or303 Location=/notes with actual deletion; missing404. Add meaningful tests, update README. "
                + "Use existing store and port8080. No editing/auth/extra features.";
        var data = base(root, policy, "note-form-behavior-v1", request, "com.example.notes", "NotesApplication", "NoteController", "NoteStore");
        var list = endpoint("GET", "/notes", "", "", "200 notes/list HTML");
        var form = endpoint("GET", "/notes/new", "", "", "200 notes/form HTML; title/body");
        var detail = endpoint("GET", "/notes/{id}", "", "", "200 notes/detail escaped text or404");
        var submit = endpoint("POST", "/notes", "", "", "302/303 /notes; invalid400 with form; state preserved");
        var delete = endpoint("POST", "/notes/{id}/delete", "", "", "302/303 /notes; actual deletion; missing404");
        var listView = view("notes/list", "Escaped titles with detail links");
        var formView = view("notes/form", "POST /notes form; title/body fields");
        var detailView = view("notes/detail", "Escaped title/body");
        var deleteView = view("notes/detail", "Escaped title/body and POST /notes/{id}/delete form");
        data.contract = contract(List.of(list, form, detail, submit, delete), List.of(listView, formView, deleteView));
        plan(data, List.of(slice(data, "note-base", "Existing list, form and detail rendering", List.of(list, form, detail), List.of(listView, formView), List.of()),
                slice(data, "note-submit", "Submit and display saved notes safely", List.of(submit), List.of(detailView), List.of("note-base")),
                slice(data, "note-delete", "Delete through detail form", List.of(delete), List.of(deleteView), List.of("note-submit"))));
        return data;
    }

    /** 构造同一份人工固定规划，不发起规划模型调用。 */
    private static SoftwareStudioWorkflowData base(Path root, DevelopmentMilestonePolicy policy, String id, String request,
                                                  String pkg, String entry, String controller, String store) {
        var data = new SoftwareStudioWorkflowData(request);
        data.projectPath = root.toString(); data.projectProfile = ProjectProfile.java17SpringBoot(); data.behaviorCheckId = id; data.milestonePolicy = policy;
        data.prd = new PrdDocument(id, request, List.of("Preserve existing behavior", "Complete pending capabilities"), List.of("Java17", "SpringBoot3.2.4", "Maven"));
        data.structure = new ProjectStructure(pkg, "SPRING_BOOT", entry, List.of(
                blueprint(pkg, controller, "controller"), blueprint(pkg, store, "service")));
        data.generationPlan = new GenerationPlan(List.of());
        return data;
    }
    private static FileBlueprint blueprint(String pkg, String name, String type) {
        return new FileBlueprint(name + ".java", "src/main/java/" + pkg.replace('.', '/') + "/" + name + ".java", type, "shared",
                "Implement declared behavior with meaningful tests", List.of(), List.of());
    }
    private static void plan(SoftwareStudioWorkflowData data, List<DeliverySlice> slices) {
        data.sliceDeliveryPlan = new SliceDeliveryPlan(slices, data.structure.files().stream().map(FileBlueprint::targetPath).toList(), "controlled-cross-case-v1");
    }
    private static ApiEndpointContract endpoint(String method, String path, String request, String response, String rule) {
        return new ApiEndpointContract(method, path, request, response, "", rule);
    }
    private static ViewContract view(String name, String rule) { return new ViewContract(name, "", "", rule); }
    private static ProjectContract contract(List<ApiEndpointContract> endpoints, List<ViewContract> views) {
        return new ProjectContract(endpoints, views, List.of(), List.of());
    }
    private static DeliverySlice slice(SoftwareStudioWorkflowData data, String id, String goal, List<ApiEndpointContract> endpoints,
                                      List<ViewContract> views, List<String> depends) {
        return new DeliverySlice(id, goal, List.of(goal + "; preserve valid existing tests and behavior"), List.of(), contract(endpoints, views), depends,
                data.structure.files().stream().map(FileBlueprint::targetPath).toList());
    }
}
