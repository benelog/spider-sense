package orders.web;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** One hand-written HTML page, so there is no template engine on the classpath. */
@Controller
public class IndexController {

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>spring-orders</title>
              <style>
                :root { color-scheme: light dark; }
                body { font: 15px/1.6 system-ui, sans-serif; margin: 2rem auto; max-width: 52rem; padding: 0 1rem; }
                h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
                p.lead { color: #666; margin-top: 0; }
                table { border-collapse: collapse; width: 100%; margin: 1rem 0 2rem; }
                th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #8884; vertical-align: top; }
                th { font-weight: 600; }
                code { font-family: ui-monospace, monospace; }
                .slow { color: #b4690e; font-weight: 600; }
                .bad { color: #c0392b; font-weight: 600; }
                .fast { color: #2d7a2d; font-weight: 600; }
              </style>
            </head>
            <body>
              <h1>spring-orders</h1>
              <p class="lead">Spring Boot 4.1 + Spring Data JPA + H2, port 8082. Some of this is slow or broken on purpose.</p>
              <table>
                <tr><th>Route</th><th>What it does</th><th>Behaviour</th></tr>
                <tr><td><a href="/api/orders?page=0&amp;size=20"><code>GET /api/orders?page&amp;size</code></a></td>
                    <td>Page of orders, lines fetch-joined</td><td class="fast">fast</td></tr>
                <tr><td><a href="/api/orders/1"><code>GET /api/orders/{id}</code></a></td>
                    <td>One order, lazy loads every product</td><td class="slow">N+1 on purpose</td></tr>
                <tr><td><a href="/api/orders/1/enriched"><code>GET /api/orders/{id}/enriched</code></a></td>
                    <td>Calls silk-bookstore once per line</td><td class="slow">N+1 over HTTP on purpose</td></tr>
                <tr><td><a href="/api/orders/1/enriched-batch"><code>GET /api/orders/{id}/enriched-batch</code></a></td>
                    <td>The same answer, one call for every line</td><td class="fast">the fix, for comparison</td></tr>
                <tr><td><a href="/api/reports/revenue?days=90"><code>GET /api/reports/revenue?days</code></a></td>
                    <td>Two grouping queries over every order</td><td class="slow">slow on purpose</td></tr>
                <tr><td><a href="/api/customers/search?q=a"><code>GET /api/customers/search?q</code></a></td>
                    <td>LIKE over 500 customers</td><td class="fast">fast</td></tr>
                <tr><td><code>POST /api/orders</code></td>
                    <td><code>{"customerId":1,"lines":[{"productId":1,"quantity":2}]}</code></td><td>201, 400 when invalid</td></tr>
                <tr><td><code>POST /api/orders/{id}/pay</code></td>
                    <td>NEW &rarr; PAID</td><td class="bad">409 from any other state</td></tr>
                <tr><td><code>POST /api/orders/{id}/ship</code></td>
                    <td>PAID &rarr; SHIPPED</td><td class="slow">sleeps 300-900 ms</td></tr>
                <tr><td><a href="/api/flaky"><code>GET /api/flaky</code></a></td>
                    <td>Payment gateway</td><td class="bad">500 one time in five</td></tr>
                <tr><td><a href="/api/slow?ms=1200"><code>GET /api/slow?ms</code></a></td>
                    <td>Sleeps, no database</td><td class="slow">1200 ms by default</td></tr>
                <tr><td><a href="/api/health"><code>GET /api/health</code></a></td>
                    <td>Liveness</td><td class="fast">fast</td></tr>
              </table>
              <p>Run it under Spider Sense with
                 <code>-javaagent:spider-sense-0.1.0.jar -Dspidersense.collector=http://localhost:4000</code>
                 and watch the traces at <a href="http://localhost:4000">localhost:4000</a>.</p>
            </body>
            </html>
            """;

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
        return PAGE;
    }
}
