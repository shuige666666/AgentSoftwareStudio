package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningAgentTest {
    private static final String REQUIREMENTS = """
            {"projectName":"投票","projectGoal":"创建和计票",
             "userStories":["B1: 创建投票","B2: 使用 voterId 投票且拒绝重复投票"],
             "techStack":["Spring Boot"],"businessRules":["重复投票返回 409"],
             "assumptions":[],"openQuestions":[]}
            """;
    private static final String DESIGN = """
            {"structure":{"rootPackage":"com.example.polls","projectType":"SPRING_BOOT",
              "mainClassName":"com.example.polls.PollsApplication",
              "files":[{"fileName":"PollController.java",
                "filePath":"src/main/java/com/example/polls/PollController.java",
                "layer":"controller","batchName":"poll-voting",
                "functionalityDescription":"创建和投票","keyMethods":[],"dependsOn":[]}]},
             "contract":{"endpoints":[{"method":"POST","path":"/api/polls",
               "requestDto":"CreatePollRequest","responseDto":"PollResponse",
               "implementedBy":"PollController","description":"创建投票",
               "requestFields":{"question":"string"},"responseFields":{"id":"string"},
               "errorResponses":{},"behaviorIds":["B1"]},
               {"method":"POST","path":"/api/polls/{id}/votes",
               "requestDto":"VoteRequest","responseDto":"","implementedBy":"PollController",
               "description":"投票并防重复","requestFields":{"voterId":"string","option":"string"},
               "responseFields":{},"errorResponses":{"409":"重复投票"},"behaviorIds":["B2"]}],
               "views":[],"frontendCalls":[],"additionalFiles":[]},
             "domainDesign":"Poll 保存选项和 voterId 集合；投票先检查身份再计票。",
             "behaviorCoverage":{"B1":["POST /api/polls"],"B2":["POST /api/polls/{id}/votes"]}}
            """;

    /**
     * 原始需求必须同时传到两步规划，且模型请求使用真实 system/user 角色。
     */
    @Test
    void keepsOriginalRequestAndMapsEachBehaviorToPublicContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyList())).thenReturn(reply(REQUIREMENTS), reply(DESIGN));
        PlanningAgent agent = new PlanningAgent(model,
                new LangGraphPromptExecutor(new LlmUsageMetricsService()), new ObjectMapper());
        String original = "创建投票，并使用 voterId 投票且拒绝重复投票";

        var requirements = agent.summarizeRequirements(original);
        var design = agent.design(original, requirements);

        assertEquals(2, requirements.userStories().size());
        assertEquals(2, design.behaviorCoverage().size());
        assertEquals("POST /api/polls/{id}/votes", design.behaviorCoverage().get("B2").getFirst());
        assertTrue(design.contract().endpoints().get(1).behaviorIds().contains("B2"));
        assertEquals("409", design.contract().endpoints().get(1).errorResponses().keySet().iterator().next());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ChatMessage>> calls = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model, times(2)).generate(calls.capture());
        for (List<ChatMessage> messages : calls.getAllValues()) {
            assertTrue(messages.get(0) instanceof SystemMessage);
            assertTrue(messages.get(1) instanceof UserMessage);
            assertTrue(messages.get(1).text().contains(original));
        }
    }

    /**
     * 缺失公开路径或方法应返回对应字段，不得静默采用根路径。
     */
    @Test
    void reportsMissingPublicMethodAndPathWithoutRootDefaults() throws Exception {
        ApiEndpointContract endpoint = new ApiEndpointContract(null, null, "", "", "", "");
        FrontendCallContract call = new FrontendCallContract("app.js", null, null, "");
        assertEquals("", endpoint.method());
        assertEquals("", endpoint.path());
        assertEquals("", call.method());
        assertEquals("", call.path());

        String incomplete = DESIGN.replace("\"method\":\"POST\",\"path\":\"/api/polls\"",
                "\"method\":\"\",\"path\":\"\"");
        var design = new ObjectMapper().readValue(incomplete,
                com.core.multiAgentSoftwareStudio.Model.Generation.PlanningDesign.class);
        var requirements = new ObjectMapper().readValue(REQUIREMENTS,
                com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument.class);
        assertTrue(PlanningAgent.designProblems(design, requirements)
                .contains("contract.endpoints.method/path"));
        String incompleteFrontend = DESIGN.replace("\"frontendCalls\":[]",
                "\"frontendCalls\":[{\"sourceFile\":\"app.js\",\"method\":\"\",\"path\":\"\",\"description\":\"\"}]");
        var frontendDesign = new ObjectMapper().readValue(incompleteFrontend,
                com.core.multiAgentSoftwareStudio.Model.Generation.PlanningDesign.class);
        assertTrue(PlanningAgent.designProblems(frontendDesign, requirements)
                .contains("contract.frontendCalls.method/path"));
    }

    /**
     * 第二步遗漏某项必需行为时，只重做设计并保留完整原始需求。
     */
    @Test
    void repairsMissingBehaviorCoverageOnce() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        String incomplete = DESIGN.replace("\"B2\":[\"POST /api/polls/{id}/votes\"]", "");
        incomplete = incomplete.replace("\"B1\":[\"POST /api/polls\"],}",
                "\"B1\":[\"POST /api/polls\"]}");
        when(model.generate(anyList())).thenReturn(reply(REQUIREMENTS), reply(incomplete), reply(DESIGN));
        PlanningAgent agent = new PlanningAgent(model,
                new LangGraphPromptExecutor(new LlmUsageMetricsService()), new ObjectMapper());

        var requirements = agent.summarizeRequirements("创建和投票");
        var design = agent.design("创建和投票", requirements);

        assertEquals(2, design.behaviorCoverage().size());
        verify(model, times(3)).generate(anyList());
    }

    private Response<AiMessage> reply(String json) {
        return Response.from(AiMessage.from(json));
    }
}
