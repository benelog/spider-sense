package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

/** When a Spider Sense is gone and when it is only slow (agent.md, "CLI"). */
class RemoteTest {

    private static final String BASE = "http://127.0.0.1:4000/";

    @Test
    void anAnswerThatDidNotComeIsABusyServerNotAMissingOne() {
        RuntimeException failure = Remote.failure(new HttpTimeoutException("request timed out"), BASE);

        assertThat(failure).isInstanceOf(Remote.Busy.class);
        assertThat(((Remote.Busy) failure).said())
                .isEqualTo("the Spider Sense at http://127.0.0.1:4000 did not answer within 2 minutes");
    }

    @Test
    void aConnectionNotMadeOrRefusedIsNoServerAtAll() {
        assertThat(Remote.failure(new HttpConnectTimeoutException("connect timed out"), BASE))
                .isInstanceOf(Remote.Unreachable.class);
        assertThat(Remote.failure(new ConnectException("Connection refused"), BASE))
                .isInstanceOf(Remote.Unreachable.class)
                .hasMessage("ConnectException: Connection refused");
    }
}
