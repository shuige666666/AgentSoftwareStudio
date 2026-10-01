package com.example.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TaskBaselineTest {
    @Autowired MockMvc mvc;
    @Autowired TaskStore store;

    /** 已有创建与查询能力使用返回 ID，不依赖共享测试状态。 */
    @Test void createsAndQueriesStoredTask() throws Exception {
        Task task = store.create("Existing capability");
        mvc.perform(get("/api/tasks/" + task.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(task.title())).andExpect(jsonPath("$.completed").value(false));
        mvc.perform(post("/api/tasks").contentType("application/json").content("{\"title\":\"Created over HTTP\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Created over HTTP")).andExpect(jsonPath("$.completed").value(false));
    }
    /** 非法创建不改变已有状态。 */
    @Test void rejectsBlankTitleWithoutWriting() throws Exception {
        int before = store.list().size();
        mvc.perform(post("/api/tasks").contentType("application/json").content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
        assertEquals(before, store.list().size());
    }
    /** 不存在资源的既有契约保持 404。 */
    @Test void missingTaskIs404() throws Exception {
        mvc.perform(get("/api/tasks/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    }
}
