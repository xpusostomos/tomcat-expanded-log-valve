/*
 * Copyright 2026 Chris Bitmead
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xpusostomos.tomcat.valves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.servlet.MultipartConfigElement;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests running the valve inside an embedded Tomcat 9.
 */
class ExpandedAccessLogValveTest {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private Path logsDir;
    private Tomcat tomcat;

    @AfterEach
    void tearDown() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
            tomcat = null;
        }
    }

    // ------------------------------------------------------------- Test cases

    @Test
    @Timeout(30)
    void defaultConfigurationBehavesLikeAccessLogValve() throws Exception {
        start(valve -> {
        });
        get(port(), "/hello");
        // The stock valve logs nothing unless `pattern` is explicitly set; same here
        Thread.sleep(500);
        assertEquals(0, readLogLines().size());
    }

    @Test
    @Timeout(30)
    void startLineIsRenderedBeforeEndLine() throws Exception {
        start(valve -> {
            valve.setPattern("%r %s");
            valve.setPatternBeg1("BEG %m %U");
            valve.setPatternEnd1("END %s");
        });
        get(port(), "/hello");
        List<String> lines = awaitLog(l -> l.size() == 3);
        assertTrue(lines.get(0).startsWith("BEG GET /hello"), "unexpected: " + lines);
        assertTrue(lines.get(1).equals("GET /hello HTTP/1.1 200"), "unexpected main line: " + lines);
        assertTrue(lines.get(2).equals("END 200"), "unexpected end line: " + lines);
    }

    @Test
    @Timeout(30)
    void threeEndPatternsRenderInOrder() throws Exception {
        start(valve -> {
            valve.setPattern("");
            valve.setPatternEnd1("END1 %s");
            valve.setPatternEnd2("END2 %s");
            valve.setPatternEnd3("END3 %s");
        });
        get(port(), "/hello");
        awaitLog(lines -> lines.size() == 3
                && lines.get(0).startsWith("END1 200")
                && lines.get(1).startsWith("END2 200")
                && lines.get(2).startsWith("END3 200"));
    }

    @Test
    @Timeout(30)
    void formParametersAreRendered() throws Exception {
        start(valve -> valve.setPatternEnd1("PARAMS %P"));
        post(port(), "/form", FORM_CONTENT_TYPE, "a=hello&b=world%20x");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.contains("PARAMS a=hello&b=world x")));
    }

    @Test
    @Timeout(30)
    void formParametersMultiValued() throws Exception {
        start(valve -> valve.setPatternEnd1("PARAMS %P"));
        post(port(), "/form", FORM_CONTENT_TYPE, "a=1&a=2");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.contains("PARAMS a=1&a=2")));
    }

    @Test
    @Timeout(30)
    void noFormParametersRenderAsDash() throws Exception {
        start(valve -> valve.setPatternEnd1("PARAMS %P"));
        get(port(), "/hello");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.equals("PARAMS -")));
    }

    @Test
    @Timeout(30)
    void formParametersCanBePlacedInMainPattern() throws Exception {
        start(valve -> valve.setPattern("%r %P"));
        post(port(), "/form", FORM_CONTENT_TYPE, "a=hello");
        awaitLog(lines -> lines.stream()
                .anyMatch(line -> line.contains("POST /form HTTP/1.1") && line.contains("a=hello")));
        assertEquals(1, readLogLines().size());
    }

    @Test
    @Timeout(30)
    void jsonBodyIsCaptured() throws Exception {
        start(valve -> valve.setPatternEnd1("BODY %J"));
        post(port(), "/json", JSON_CONTENT_TYPE, "{\"k\":\"v\"}");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.contains("BODY {\\\"k\\\":\\\"v\\\"}")));
    }

    @Test
    @Timeout(30)
    void bodyIsNotCapturedForOtherContentTypes() throws Exception {
        start(valve -> valve.setPatternEnd1("BODY %J"));
        post(port(), "/text", "text/plain", "some text");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.equals("BODY -")));
    }

    @Test
    @Timeout(30)
    void vendorJsonTypesAreCapturedByDefault() throws Exception {
        start(valve -> valve.setPatternEnd1("BODY %J"));
        post(port(), "/json", "application/vnd.api+json", "{\"a\":1}");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.contains("BODY {\\\"a\\\":1}")));
    }

    @Test
    @Timeout(30)
    void customBodyContentTypesAreHonoured() throws Exception {
        start(valve -> {
            valve.setBodyContentTypes("text/plain");
            valve.setPatternEnd1("BODY %J");
        });
        post(port(), "/text", "text/plain; charset=utf-8", "some text");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.equals("BODY some text")));
    }

    @Test
    @Timeout(30)
    void unreadBodyIsReported() throws Exception {
        start(valve -> valve.setPatternEnd1("BODY %J"));
        post(port(), "/json-unread", JSON_CONTENT_TYPE, "{\"a\":1}");
        awaitLog(lines -> lines.stream().anyMatch(line -> line.equals("BODY (not read)")));
    }

    @Test
    @Timeout(30)
    void bodyIsTruncatedAtMaxBodyLogSize() throws Exception {
        start(valve -> {
            valve.setPatternEnd1("BODY %J");
            valve.setMaxBodyLogSize(10);
        });
        String body = "A".repeat(60);
        post(port(), "/json", JSON_CONTENT_TYPE, body);
        awaitLog(lines -> lines.stream().anyMatch(line -> line.equals("BODY AAAAAAAAAA...[truncated]")));
    }

    @Test
    @Timeout(30)
    void bodyEscapesPreventLineInjection() throws Exception {
        start(valve -> valve.setPatternEnd1("BODY %J"));
        String body = "{\"msg\":\"quote \" backslash \\ newline \nEND-OF-MESSAGE\"}";
        post(port(), "/json", JSON_CONTENT_TYPE, body);
        List<String> lines = awaitLog(l -> l.stream()
                .anyMatch(line -> line.startsWith("BODY ") && line.contains("quote \\\" backslash \\\\ newline \\n")));
        // The forged "line" must not exist; everything stayed on one physical line
        assertFalse(lines.stream().anyMatch(line -> line.trim().equals("line2 \"q\"")));
        assertEquals(1, lines.size()); // only the BODY line; no main pattern set
    }

    @Test
    @Timeout(30)
    void formParametersAvailableInStartPattern() throws Exception {
        start(valve -> {
            valve.setPattern("");
            valve.setPatternBeg1("BEGP %P");
        });
        post(port(), "/form", FORM_CONTENT_TYPE, "a=hello");
        List<String> lines = awaitLog(l -> !l.isEmpty() && l.get(0).startsWith("BEGP a=hello"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("a=hello") && line.startsWith("PARAMS")));
    }

    @Test
    @Timeout(30)
    void asyncRequestIsLoggedOnceWithBody() throws Exception {
        start(valve -> {
            valve.setPattern("");
            valve.setPatternBeg1("BEG %m %U");
            valve.setPatternEnd1("END %J");
        });
        String body = "{\"a\":1}";
        String response = post(port(), "/asyncjson", JSON_CONTENT_TYPE, body);
        assertTrue(response.startsWith("async:"), "unexpected response: " + response);
        List<String> lines = awaitLog(l -> l.stream()
                .anyMatch(line -> line.startsWith("END ") && line.contains("{\\\"a\\\":1}")));
        assertEquals(1, lines.stream().filter(line -> line.startsWith("BEG")).count(), "exactly one start line");
        assertEquals(1, lines.stream().filter(line -> line.startsWith("END")).count(), "exactly one end line");
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("BEG POST /asyncjson")));
    }

    @Test
    @Timeout(30)
    void multipartFormFieldsAreCaptured() throws Exception {
        start(valve -> valve.setPatternEnd1("PARAMS %P"));
        String boundary = "xpusoBoundary123";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue123\r\n--"
                + boundary + "--\r\n";
        String response = post(port(), "/multipart", "multipart/form-data; boundary=" + boundary, body);
        assertTrue(response.contains("parts=1"), "unexpected response: " + response);
        awaitLog(lines -> lines.stream().anyMatch(line -> line.contains("PARAMS field=value123")));
    }

    @Test
    @Timeout(30)
    void emptyMainPatternProducesNoBlankLines() throws Exception {
        start(valve -> {
            valve.setPattern("");
            valve.setPatternEnd1("END");
        });
        get(port(), "/hello");
        awaitLog(lines -> lines.size() == 1 && lines.get(0).equals("END"));
        assertFalse(readLogLines().stream().anyMatch(String::isEmpty));
    }

    // ---------------------------------------------------------------- Helpers

    private Tomcat start(Consumer<ExpandedAccessLogValve> valveConfig) throws Exception {
        Path base = Files.createTempDirectory("xpuso-valve-test");
        logsDir = base.resolve("logs");
        Files.createDirectories(logsDir);

        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        tomcat.getConnector();

        Context context = tomcat.addContext("", base.toString());
        Wrapper servlet = Tomcat.addServlet(context, "test", new TestServlet());
        servlet.setAsyncSupported(true);
        ((org.apache.catalina.core.StandardWrapper) servlet).setMultipartConfigElement(new MultipartConfigElement(""));
        context.addServletMappingDecoded("/*", "test");

        ExpandedAccessLogValve valve = new ExpandedAccessLogValve();
        valve.setDirectory(logsDir.toString());
        valve.setRotatable(false);
        valve.setSuffix(".log");
        valve.setBuffered(false);
        valveConfig.accept(valve);
        tomcat.getHost().getPipeline().addValve(valve);

        tomcat.start();
        return tomcat;
    }

    private int port() {
        return tomcat.getConnector().getLocalPort();
    }

    private List<String> awaitLog(Predicate<List<String>> condition) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        List<String> lines = List.of();
        while (System.currentTimeMillis() < deadline) {
            lines = readLogLines();
            if (condition.test(lines)) {
                return lines;
            }
            Thread.sleep(50);
        }
        fail("Log condition not met within timeout. Log contents: " + lines);
        return lines;
    }

    private List<String> readLogLines() throws Exception {
        try (Stream<Path> files = Files.list(logsDir)) {
            Path file = files.filter(p -> p.getFileName().toString().endsWith(".log")).findFirst().orElse(null);
            if (file == null) {
                return List.of();
            }
            return Files.readAllLines(file);
        }
    }

    private static String get(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String post(int port, String path, String contentType, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}