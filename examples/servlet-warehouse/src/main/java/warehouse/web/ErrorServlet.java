package warehouse.web;

import java.io.IOException;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Where Tomcat's error pages land, so a 404, a 409 and a 500 all come back as
 * JSON rather than as Tomcat's HTML report.
 *
 * <p>Everything it knows arrives in the request attributes the container sets:
 * the status code, the message {@code sendError} was given, and, for a 500, the
 * exception that got away. The status has to be set again here, because this is
 * a fresh dispatch.
 */
public class ErrorServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer value ? value : 500;

        Object thrown = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        String message = (String) request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        if ((message == null || message.isBlank()) && thrown instanceof Throwable throwable) {
            message = rootCause(throwable).getMessage();
        }

        Out.json(response, status, """
                {"status":%d,"message":%s}""".formatted(status,
                Out.quote(message == null || message.isBlank() ? "error " + status : message)));
    }

    /** Tomcat wraps what the servlet threw in a ServletException; the message we want is inside. */
    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        Throwable next = cause.getCause();
        // A throwable that is its own cause would loop for ever, so the walk stops there.
        while (next != null && !next.equals(cause)) {
            cause = next;
            next = cause.getCause();
        }
        return cause;
    }
}
