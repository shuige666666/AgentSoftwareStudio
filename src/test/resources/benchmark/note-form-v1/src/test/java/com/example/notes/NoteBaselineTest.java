package com.example.notes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest @AutoConfigureMockMvc
class NoteBaselineTest {
    @Autowired MockMvc mvc;
    @Test void listPage() throws Exception {
        mvc.perform(get("/notes")).andExpect(status().isOk()).andExpect(content().string(containsString("/notes/new")));
    }
    @Test void createForm() throws Exception {
        mvc.perform(get("/notes/new")).andExpect(status().isOk()).andExpect(content().string(containsString("name=\"title\"")))
                .andExpect(content().string(containsString("name=\"body\"")));
    }
    @Test void missingDetail() throws Exception {
        mvc.perform(get("/notes/9223372036854775807")).andExpect(status().isNotFound());
    }
}
