package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 提取本轮 Surefire 的失败证据，不以自编测试替代独立业务判卷。 */
final class SurefireFailureFeedback {
    private static final Pattern FRAME = Pattern.compile(
            "(?m)^\\s*at ([\\w.$]+)\\.([\\w$<>]+)\\(([^:()]+\\.java):(\\d+)\\)");
    private static final long MAX_REPORT_BYTES = 4L * 1024 * 1024;
    private static final int MAX_FEEDBACK_CHARS = 6_000;

    private SurefireFailureFeedback() { }

    /** 记录执行前的报告版本，避免把旧测试报告当成当前失败。 */
    static Map<Path, FileTime> snapshot(Path root) {
        Map<Path, FileTime> reports = new LinkedHashMap<>();
        Path directory = root.resolve("target/surefire-reports");
        if (!Files.isDirectory(directory)) return reports;
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("TEST-.+\\.xml"))
                    .sorted().limit(100).toList()) {
                // 生成项目可能创建链接；读取诊断文件也不能越出候选工作区。
                if (Files.isRegularFile(path) && path.toRealPath().startsWith(root.toRealPath())) {
                    reports.put(path, Files.getLastModifiedTime(path));
                }
            }
        } catch (Exception ignored) {
            // 诊断缺项不能覆盖 Maven 的真实退出状态，调用者仍保留原日志。
        }
        return reports;
    }

    /** 只读取新建或本轮更新的报告，保留实际失败消息和首个项目源码栈帧。 */
    static String read(Path root, Map<Path, FileTime> before) {
        List<Failure> failures = new ArrayList<>();
        for (var entry : snapshot(root).entrySet()) {
            if (Objects.equals(before.get(entry.getKey()), entry.getValue())) continue;
            try {
                if (Files.size(entry.getKey()) > MAX_REPORT_BYTES) continue;
                var builder = secureFactory().newDocumentBuilder();
                builder.setErrorHandler(new DefaultHandler());
                var document = builder.parse(entry.getKey().toFile());
                NodeList cases = document.getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    Element test = (Element) cases.item(index);
                    Element problem = child(test, "failure");
                    if (problem == null) problem = child(test, "error");
                    if (problem == null) continue;
                    String trace = problem.getTextContent();
                    String location = firstProjectFrame(root, trace);
                    failures.add(new Failure(test.getAttribute("classname") + "." + test.getAttribute("name"),
                            problem.getTagName(), bounded(problem.getAttribute("message"), 700), location,
                            traceCauses(trace)));
                }
            } catch (Exception ignored) {
                // 非法或未写完的 XML 只失去结构化诊断，不改变真实验证结论。
            }
        }
        if (failures.isEmpty()) return "";
        StringBuilder result = new StringBuilder("Surefire failure evidence (fresh reports; compare with the public contract):\n");
        result.append("Failed/error test cases: ").append(failures.size()).append('\n');
        // 上下文失败的长包装消息会淹没实际缺失 Bean 等原因，先交付原始异常链末端的证据。
        failures.stream().flatMap(f -> f.causes().stream()).distinct().limit(3).forEach(cause ->
                result.append("Observed cause from exception trace: ").append(cause).append('\n'));
        Map<String, Integer> shared = new LinkedHashMap<>();
        failures.stream().filter(f -> !f.location().isBlank())
                .forEach(f -> shared.merge(f.location(), 1, Integer::sum));
        shared.entrySet().stream().filter(e -> e.getValue() > 1).limit(2).forEach(entry ->
                result.append("Repeated first project frame: ").append(entry.getKey())
                        .append(" (").append(entry.getValue()).append(" cases). Inspect this shared location/precondition before treating all as independent business failures.\n"));
        for (Failure failure : failures.stream().limit(5).toList()) {
            result.append("- ").append(failure.test()).append(" [").append(failure.kind()).append("]\n")
                    .append("  actual assertion/exception: ").append(failure.message()).append('\n');
            if (!failure.location().isBlank()) result.append("  first project frame / related file: ")
                    .append(failure.location()).append('\n');
        }
        if (failures.size() > 5) result.append("More failures remain in target/surefire-reports and the full verification log.\n");
        result.append("Check production code, valid fixture inputs and test initialization against the contract; do not weaken valid assertions.");
        return bounded(result.toString(), MAX_FEEDBACK_CHARS);
    }

    /** XML 由生成测试产生，禁止外部实体和 DTD，避免诊断读取本机凭据或联网。 */
    private static DocumentBuilderFactory secureFactory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** 仅提取测试用例的直接失败节点，忽略属性和系统输出。 */
    private static Element child(Element test, String name) {
        for (var node = test.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }

    /** 根据栈帧的包名定位真实源码，不用 Controller、业务名称或报错关键词猜根因。 */
    private static String firstProjectFrame(Path root, String trace) {
        var matcher = FRAME.matcher(trace);
        while (matcher.find()) {
            String className = matcher.group(1).split("\\$")[0];
            String packagePath = className.contains(".")
                    ? className.substring(0, className.lastIndexOf('.') + 1).replace('.', '/') : "";
            for (String sourceRoot : List.of("src/test/java/", "src/main/java/")) {
                String relative = sourceRoot + packagePath + matcher.group(3);
                if (Files.isRegularFile(root.resolve(relative))) {
                    return relative + ":" + matcher.group(4) + " (" + matcher.group(2) + ")";
                }
            }
        }
        return "";
    }

    /** 限制模型可见诊断体积，同时注明完整报告仍可核对。 */
    private static String bounded(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n[diagnostic truncated; full report retained]";
    }

    /** 读取真实异常链，优先保留最内层原因；不根据业务名或失败数量推断根因。 */
    private static List<String> traceCauses(String trace) {
        List<String> causes = trace.lines().map(String::strip)
                .filter(line -> line.startsWith("Caused by:")).distinct().toList();
        List<String> result = new ArrayList<>();
        for (int index = causes.size() - 1; index >= Math.max(0, causes.size() - 3); index--) {
            result.add(bounded(causes.get(index), 900));
        }
        return result;
    }

    private record Failure(String test, String kind, String message, String location, List<String> causes) { }
}
