package worker;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderGatewayTest {

    /**
     * The gateway is supposed to fail about one send in eight, and it is
     * supposed to fail the same way twice when it is given the same seed.
     */
    @Test
    void failsAboutOneSendInEight() {
        ReminderGateway gateway = new ReminderGateway(1234);
        int failures = 0;
        for (int i = 0; i < 800; i++) {
            try {
                gateway.send(i, "Account " + i);
            } catch (IOException e) {
                failures++;
                assertThat(e).hasMessage("SMTP 451 mailbox busy, try later");
            }
        }
        assertThat(failures).isBetween(60, 140);
    }
}
