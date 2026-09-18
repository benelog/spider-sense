package warehouse.web;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One line per request on stdout, and a header so a response can be recognised
 * as having come through here.
 *
 * <p>It never swallows what the servlet threw: the log line is written in a
 * {@code finally}, so an {@code IllegalStateException} from {@code /api/flaky}
 * carries on to Tomcat, which is what turns it into a 500 through the error
 * page. In the trace this filter is inside the server span, not beside it.
 */
public class RequestLogFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        out.setHeader("X-Warehouse", "1");

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String query = http.getQueryString();
            System.out.printf("%-4s %-40s %3d %5d ms%n",
                    http.getMethod(),
                    query == null ? http.getRequestURI() : http.getRequestURI() + "?" + query,
                    out.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }
}
