package com.example.polls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class PollBaselineTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void createAndQuery() throws Exception {
        String response = mvc.perform(post("/api/polls").contentType("application/json")
                .content("{\"question\":\"Q\",\"options\":[\"A\",\"B\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(response).get("id").asLong();
        mvc.perform(get("/api/polls/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.question").value("Q"));
        mvc.perform(get("/api/polls/" + id + "/results")).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.A").value(0)).andExpect(jsonPath("$.counts.B").value(0));
    }
    @Test void rejectInvalidPoll() throws Exception {
        mvc.perform(post("/api/polls").contentType("application/json").content("{\"question\":\"\",\"options\":[\"A\"]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void missingPoll() throws Exception {
        mvc.perform(get("/api/polls/9223372036854775807")).andExpect(status().isNotFound());
        mvc.perform(get("/api/polls/9223372036854775807/results")).andExpect(status().isNotFound());
    }
}
