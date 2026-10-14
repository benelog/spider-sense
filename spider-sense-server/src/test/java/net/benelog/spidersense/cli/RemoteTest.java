package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** When a Spider Sense is gone and when it is only slow (cli.adoc). */
class RemoteTest {

    private static final String BASE = "http://127.0.0.1:4000/";

    @Test
    void anAnswerThatDidNotComeIsABusyServerNotAMissingOne() {
        RuntimeException failure = Remote.failure(new HttpTimeoutException("request timed out"), BASE);

        assertThat(failure).isInstanceOf(Remote.Busy.class);
        assertThat(failure).hasMessage("the Spider Sense at http://127.0.0.1:4000 did not answer within 2 minutes");
    }

    @Test
    void aConnectionNotMadeOrRefusedIsNoServerAtAll() {
        assertThat(Remote.failure(new HttpConnectTimeoutException("connect timed out"), BASE))
                .isInstanceOf(Remote.Unreachable.class);
        assertThat(Remote.failure(new ConnectException("Connection refused"), BASE))
                .isInstanceOf(Remote.Unreachable.class)
                .hasMessage("ConnectException: Connection refused");
    }

    /** The line every command says it with, and tail adds its own ending to (cli.adoc). */
    @Test
    void noServerIsSaidWithTheReason() {
        Remote.Unreachable unreachable = new Remote.Unreachable("ConnectException: Connection refused");

        assertThat(unreachable.line("http://127.0.0.1:4000"))
                .isEqualTo("no Spider Sense at http://127.0.0.1:4000 (ConnectException: Connection refused)");
        assertThat(Remote.reason(new HttpConnectTimeoutException(null))).isEqualTo("HttpConnectTimeoutException");
    }

    /** Something that answered, only not as a Spider Sense, is asked about its port (cli.adoc#fallback). */
    @Test
    void aServerThatIsNotASpiderSenseIsAskedAboutItsPort() {
        assertThat(Remote.Unreachable.answered(404, "/api/status?format=text").line("http://127.0.0.1:8082"))
                .isEqualTo("no Spider Sense at http://127.0.0.1:8082 (HTTP 404 for /api/status);"
                        + " is that the application's port?");
    }

    @Test
    void aBaseUrlWithoutASchemeIsHttp() {
        assertThat(Remote.base("127.0.0.1:4000/")).isEqualTo("http://127.0.0.1:4000");
        assertThat(Remote.base("localhost:4000")).isEqualTo("http://localhost:4000");
        assertThat(Remote.base("https://box:4000")).isEqualTo("https://box:4000");
    }

    /** A path would be a prefix of every request, and its 404 a missing trace (cli.adoc#options). */
    @Test
    void aBaseUrlWithAPathIsAUsageError() {
        for (String url : List.of("http://127.0.0.1:4000/api", "127.0.0.1:4000/ui/", "http://box:4000?x=1")) {
            assertThatThrownBy(() -> Remote.base(url)).as(url).isInstanceOf(Options.Usage.class)
                    .hasMessage("--url is a base URL, such as http://127.0.0.1:4000, with no path: " + url);
        }
        assertThat(Remote.base("http://127.0.0.1:4000/")).isEqualTo("http://127.0.0.1:4000");
    }

    @Test
    void aBaseUrlLosesItsTrailingSlashes() {
        assertThat(Remote.trimSlash(" http://box:4000// ")).isEqualTo("http://box:4000");
        assertThat(Remote.trimSlash("http://box:4000")).isEqualTo("http://box:4000");
    }

    /** The exit code of check comes from the header, never from the prose (cli.adoc#exit-codes). */
    @Test
    void theVerdictHeaderIsTheExitCode() {
        assertThat(Remote.verdict("true")).isEqualTo(Cli.OK);
        assertThat(Remote.verdict("false")).isEqualTo(Cli.CHECK_FAILED);
        assertThat(Remote.verdict("no-requests")).isEqualTo(Cli.NO_REQUESTS);
        assertThat(Remote.verdict(null)).as("no header is no verdict, never a pass").isEqualTo(Cli.USAGE);
    }

    /** A success is a Spider Sense's only as a 2xx of the type asked for (cli.adoc#fallback). */
    @Test
    void anAnswerIsASpiderSensesOnlyAsA2xxOfTheTypeAskedFor() {
        assertThat(Remote.answered(200, "text/markdown; charset=utf-8", Remote.MARKDOWN)).isTrue();
        assertThat(Remote.answered(201, "application/json", Remote.JSON)).isTrue();
        assertThat(Remote.answered(200, "text/html", Remote.MARKDOWN)).as("a dev server's index.html").isFalse();
        assertThat(Remote.answered(200, "application/json", Remote.MARKDOWN)).as("a health page").isFalse();
        assertThat(Remote.answered(301, "text/markdown", Remote.MARKDOWN)).as("a redirect").isFalse();
        assertThat(Remote.answered(200, null, Remote.JSON)).isFalse();
    }

    @Test
    void anErrorIsTheJsonErrorElseATextBodyElseTheStatusLine() {
        assertThat(Remote.message(400, "application/json", "{\"error\": \"unknown finding: f1\"}"))
                .isEqualTo("unknown finding: f1");
        assertThat(Remote.message(413, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":null,"
                + "\"error\":{\"code\":-32600,\"message\":\"Request body is larger than the limit of 1048576 bytes\"}}"))
                .as("the JSON-RPC error of /mcp").isEqualTo("Request body is larger than the limit of 1048576 bytes");
        assertThat(Remote.message(400, "text/plain; charset=utf-8", " only SELECT is allowed\n"))
                .isEqualTo("only SELECT is allowed");
        assertThat(Remote.message(502, "text/html", "  ")).isEqualTo("HTTP 502");
        assertThat(Remote.message(500, "application/json", "{\"detail\": 1}")).isEqualTo("HTTP 500: {\"detail\": 1}");
        assertThat(Remote.message(503, null, "Service Unavailable")).isEqualTo("HTTP 503: Service Unavailable");
        assertThat(Remote.message(500, null, null)).isEqualTo("HTTP 500");
        assertThat(Remote.message(500, "text/html", "<!DOCTYPE HTML>\n<html>\n<body>oops</body>\n</html>\n"))
                .as("a page that is not ours is one line, not the page").isEqualTo("HTTP 500: <!DOCTYPE HTML>");
    }

    /**
     * A Spider Sense's error is its JSON object or its one text line; anything else is some other
     * server at the URL, which is not a missing trace or mark (cli.adoc#exit-codes).
     */
    @Test
    void anErrorIsASpiderSensesOnlyInItsOwnShape() {
        assertThat(Remote.fromSpiderSense(404, "application/json", "{\"error\": \"No such trace: f\"}")).isTrue();
        assertThat(Remote.fromSpiderSense(400, "text/markdown; charset=utf-8", "only SELECT is allowed\n")).isTrue();
        assertThat(Remote.fromSpiderSense(404, "text/plain", "404 page not found\n"))
                .as("only a 400 of ours is a line of text").isFalse();
        assertThat(Remote.fromSpiderSense(404, "text/html", "<!DOCTYPE HTML><html>Error response</html>")).isFalse();
        assertThat(Remote.fromSpiderSense(404, "application/json", "{\"detail\": \"Not Found\"}")).isFalse();
        assertThat(Remote.fromSpiderSense(404, "application/json",
                "{\"timestamp\": \"2026-10-08T00:00:00Z\", \"status\": 404, \"error\": \"Not Found\", \"path\": \"/api/status\"}"))
                .as("a Spring application's error has more than the error").isFalse();
        assertThat(Remote.fromSpiderSense(404, null, "")).isFalse();
        assertThat(Remote.fromSpiderSense(404, null, null)).isFalse();
    }
}
