package warehouse.web;

import java.util.concurrent.ThreadLocalRandom;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fails about three times in ten, and nothing catches it.
 *
 * <p>The exception goes past the filter, out of {@code service()}, and into
 * Tomcat, which answers 500 through the error page. That is the path a real
 * unhandled failure takes on this stack, and the span carries the exception the
 * error page was given.
 */
public class FlakyServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        if (ThreadLocalRandom.current().nextInt(100) < 30) {
            throw new IllegalStateException("Label printer offline");
        }
        Out.json(response, """
                {"ok":true}""");
    }
}
