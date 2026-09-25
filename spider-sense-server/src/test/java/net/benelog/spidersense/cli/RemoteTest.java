package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

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
        assertThat(Remote.verdict(null)).as("no header passes").isEqualTo(Cli.OK);
    }

    @Test
    void anErrorIsTheJsonErrorElseATextBodyElseTheStatusLine() {
        assertThat(Remote.message(400, "application/json", "{\"error\": \"unknown finding: f1\"}"))
                .isEqualTo("unknown finding: f1");
        assertThat(Remote.message(400, "text/plain; charset=utf-8", " only SELECT is allowed\n"))
                .isEqualTo("only SELECT is allowed");
        assertThat(Remote.message(502, "text/html", "  ")).isEqualTo("HTTP 502");
        assertThat(Remote.message(500, "application/json", "{\"detail\": 1}")).isEqualTo("HTTP 500: {\"detail\": 1}");
        assertThat(Remote.message(503, null, "Service Unavailable")).isEqualTo("HTTP 503: Service Unavailable");
        assertThat(Remote.message(500, null, null)).isEqualTo("HTTP 500");
    }
}
