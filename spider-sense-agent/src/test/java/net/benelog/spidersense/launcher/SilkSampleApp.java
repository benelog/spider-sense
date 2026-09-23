package net.benelog.spidersense.launcher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;

/**
 * The Spider Silk application SingleJarIT monitors for the route instrumentation of the extension
 * (design.md, "The extension"): two routes, one with a path variable, called for two different ids
 * and for a path no route matches, then long enough for the agent to export them.
 *
 * <p>It names nothing itself. The server spans are the servlet instrumentation's, over one servlet
 * mapped at {@code /*}, so every route the endpoint list shows came from the extension.
 *
 * <p>Compiled by the test source set and never run by JUnit, like SampleApp.
 */
public final class SilkSampleApp {

    private SilkSampleApp() {
    }

    public static void main(String[] args) throws Exception {
        App app = new App();
        app.get("/books", req -> WebResponse.text("books"));
        app.get("/books/{id}", req -> WebResponse.text("book " + req.pathParam("id")));
        app.start(0);

        String base = "http://127.0.0.1:" + app.port();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[] {"/books", "/books/1", "/books/2", "/nowhere"}) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                System.out.println("silk-sample: GET " + path + " -> " + response.statusCode());
            }
        }
        Thread.sleep(Long.getLong("sample.linger.ms", 30000L));
        app.stop();
        System.out.println("silk-sample: done");
    }
}
