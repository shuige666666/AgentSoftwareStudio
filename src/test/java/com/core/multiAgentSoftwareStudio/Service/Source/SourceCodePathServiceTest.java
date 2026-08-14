package com.core.multiAgentSoftwareStudio.Service.Source;

import org.junit.jupiter.api.Test;


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

}
