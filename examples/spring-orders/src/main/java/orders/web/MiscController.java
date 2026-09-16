package orders.web;

import java.util.concurrent.ThreadLocalRandom;

import orders.web.Dtos.OkView;
import orders.web.Dtos.SlowView;
import orders.web.Dtos.StatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiscController {

    private static final Logger log = LoggerFactory.getLogger(MiscController.class);

    /** Fails one time in five; the exception is left to propagate so the trace shows a 500. */
    @GetMapping("/api/flaky")
    public OkView flaky() {
        if (ThreadLocalRandom.current().nextInt(100) < 20) {
            log.warn("Flaky endpoint failing this time: payment gateway timeout");
            throw new IllegalStateException("Payment gateway timeout");
        }
        return new OkView(true);
    }

    /** Sleeps, touches no database, so the span is one long block of nothing. */
    @GetMapping("/api/slow")
    public SlowView slow(@RequestParam(defaultValue = "1200") long ms) {
        long pause = Math.clamp(ms, 0, 30_000);
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new SlowView(pause);
    }

    @GetMapping("/api/health")
    public StatusView health() {
        return new StatusView("ok");
    }
}
