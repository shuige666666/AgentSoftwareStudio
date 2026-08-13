package com.core.multiAgentSoftwareStudio.Service.Source;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceCodePathServiceTest {

    private final SourceCodePathService service = new SourceCodePathService();

    /**
     * 整份 Java 源码的边界引号都被二次转义时，应安全解除多余转义层。
     */
    @Test
    void normalizesGloballyDoubleEscapedJavaQuotes() {
        String broken = "class App { String message = \\\"invalid\\\"; }";

        String normalized = service.normalizeGeneratedCode("src/main/java/App.java", broken);

        assertEquals("class App { String message = \"invalid\"; }", normalized);
    }

    /**
     * 已有正常字符串边界时，必须保留字符串内部的合法引号转义。
     */
    @Test
    void preservesLegitimateEscapedQuotesInsideJavaString() {
        String legitimate = "class App { String json = " + '"' + "{\\\"ok\\\":true}" + '"' + "; }";

        String normalized = service.normalizeGeneratedCode("src/main/java/App.java", legitimate);

        assertEquals(legitimate, normalized);
    }

    /**
     * 整文件修复的公共入口必须拒绝空白内容，避免遗漏调用方再次清空内存源码。
     */
    @Test
    void ignoresBlankWholeFileFix() {
        List<SourceCode> codes = new ArrayList<>(List.of(new SourceCode(
                "src/main/java/com/example/App.java", "java", "package com.example; class App {}")));

        service.applyCodeFix(codes, "src/main/java/com/example/App.java", "  ");

        assertEquals("package com.example; class App {}", codes.getFirst().code());
    }
}
