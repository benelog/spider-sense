package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/** {@code GET /api/source}: the lines around a frame, and nothing outside the roots. */
class SourceApiTest {

    @TempDir
    Path project;

    private interface Body {
        void run(TestClient client);
    }

    private void serve(Body body) {
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(
                TestStore.config("--source.dirs=" + project.resolve("src/main/java")));
        try {
            WebTest.test(assembly.app(), body::run);
        } finally {
            assembly.store().close();
        }
    }

    private static HttpResponse<String> source(TestClient client, String frame) {
        return client.get("/api/source?frame=" + URLEncoder.encode(frame, StandardCharsets.UTF_8));
    }

    @Test
    void aResolvedFrameAnswersItsFileItsLineAndTheFiveLinesAroundIt() throws IOException {
        Path file = project.resolve("src/main/java/orders/OrderService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a\nb\nc\nd\ne\nf\ng\n");
        String expected = file.toRealPath().toString();

        serve(client -> {
            HttpResponse<String> response = source(client, "orders.OrderService.load(OrderService.java:4)");
            assertThat(response.statusCode()).isEqualTo(200);
            Json.JsonObject body = Json.parse(response.body()).asObject();
            assertThat(body.getString("file")).isEqualTo(expected);
            assertThat(body.getLong("line")).isEqualTo(4);
            assertThat(body.getLong("start")).isEqualTo(2);
            assertThat(body.getArray("lines").values()).extracting(Json.JsonValue::asString)
                    .containsExactly("b", "c", "d", "e", "f");
        });
    }

    @Test
    void aFrameThatDoesNotResolveIsNotFoundAndATraversalIsNoDifferent() throws IOException {
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("Secret.java"), "secret\n");

        serve(client -> {
            assertThat(source(client, "orders.Missing.load(Missing.java:4)").statusCode()).isEqualTo(404);
            assertThat(source(client, "x.Y.m(../../Secret.java:1)").statusCode()).isEqualTo(404);
            assertThat(source(client, "x.Y.m(../../Secret.java:1)").body()).doesNotContain("secret\"");
            assertThat(client.get("/api/source").statusCode()).isEqualTo(400);
        });
    }
}
