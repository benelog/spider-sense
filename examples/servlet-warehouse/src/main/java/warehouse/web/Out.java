package warehouse.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The whole of this application's view layer: two escapers and two writers.
 * There is no template engine and no JSON library on the classpath on purpose,
 * because the point of this example is what the Servlet API and the JDBC driver
 * look like to the agent with nothing in between.
 */
public final class Out {

    private Out() {
    }

    /** Writes a JSON body with the given status. */
    public static void json(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    public static void json(HttpServletResponse response, String body) throws IOException {
        json(response, HttpServletResponse.SC_OK, body);
    }

    public static void html(HttpServletResponse response, String body) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <title>servlet-warehouse</title>
                <style>
                  body { font: 15px/1.5 system-ui, sans-serif; margin: 2rem; max-width: 60rem; }
                  table { border-collapse: collapse; width: 100%; }
                  th, td { border-bottom: 1px solid #ddd; padding: .35rem .6rem; text-align: left; }
                  th { background: #f4f4f4; }
                  code { background: #f4f4f4; padding: 0 .2rem; }
                </style>
                </head><body>
                """ + body + "</body></html>");
    }

    /** The five characters that would otherwise end the string, the attribute or the element. */
    public static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** JSON string escaping, enough for the values this application produces. */
    public static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
