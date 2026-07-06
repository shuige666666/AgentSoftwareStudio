package com.core.multiAgentSoftwareStudio.Service.Context;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextBuilderServiceTest {

    @Test
    void optimizedRepairContextToleratesNullCodeInUnrelatedFiles() {
        CodeContextBuilderService service = new CodeContextBuilderService(new SourceCodePathService());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/BrokenController.java", "java",
                        "package com.example;\npublic class BrokenController {}"),
                new SourceCode("src/main/java/com/example/EmptyDto.java", "java", null));
        String compilerLog = "/app/src/main/java/com/example/BrokenController.java:[1,8] <identifier> expected";

        String context = assertDoesNotThrow(
                () -> service.buildOptimizedCodeContext(codes, compilerLog, "COMPILATION ERROR"));

        assertTrue(context.contains("BrokenController"));
        assertTrue(context.contains("// [No summary available]"));
    }
}
