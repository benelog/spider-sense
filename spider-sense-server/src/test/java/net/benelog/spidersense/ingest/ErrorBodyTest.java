package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorBodyTest {

    @Test
    void theMessageIsSaidAndABlankOneGivesWayToTheFallback() {
        assertThat(ErrorBody.json("No such trace: ff", "Not found").toJson())
                .isEqualTo("{\"error\":\"No such trace: ff\"}");
        assertThat(ErrorBody.json(null, "Bad request").toJson()).isEqualTo("{\"error\":\"Bad request\"}");
        assertThat(ErrorBody.message(" ", "Bad request")).isEqualTo("Bad request");
    }
}
