package net.benelog.spidersense.api;

import java.util.LinkedHashMap;
import java.util.Map;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * The endpoints that exist for an agent rather than for the UI: findings, marks,
 * compare and check.
 *
 * <p>The handlers are thin on purpose. Everything they answer comes from
 * {@link Reports}, because the CLI answers the same questions from the same
 * database with no server running, and two implementations of "what are the
 * findings" would eventually disagree (agent.md).
 */
public final class AgentApi {

    /**
     * The verdict of {@code /api/check}, beside the body: {@code true},
     * {@code false} or {@code none}.
     *
     * <p>The CLI turns a check into an exit code and prints the Markdown, and one
     * request should answer both: without this header it would have to fetch the
     * JSON as well, or parse prose for a word (api.md).
     */
    public static final String PASS_HEADER = "X-Spider-Sense-Pass";

    private static final int FINDINGS = 20;
    private static final int FINDINGS_MAX = 100;
    private static final int MARKS = 50;
    private static final int MARKS_MAX = 500;

    private final Reports reports;
    private final Params params;

    public AgentApi(Reports reports) {
        this.reports = reports;
        this.params = new Params(reports.selectors());
    }

    public void register(App app) {
        app.get("/api/findings", "What is worth fixing in this window", this::findings);
        app.get("/api/marks", "Named moments", this::marks);
        app.post("/api/marks", "Record a named moment", this::mark);
        app.get("/api/compare", "Two windows side by side", this::compare);
        app.get("/api/check", "Thresholds as a verdict", this::check);
        app.post("/api/sql", "Read-only SQL over the store", this::sql);

        // A selector is either the caller's mistake or a question about data, and an
        // agent reacts differently to the two; both reach here from every endpoint.
        app.exception(Selectors.BadSelector.class,
                (req, e) -> WebResponse.json(Codecs.error(e.getMessage())).status(HttpStatus.BAD_REQUEST));
        app.exception(Selectors.UnknownMark.class,
                (req, e) -> WebResponse.json(Codecs.error(e.getMessage())).status(HttpStatus.NOT_FOUND));
    }

    public WebResponse findings(WebRequest req) {
        Window window = params.window(req);
        return Params.answer(req, reports.findings(window, Params.service(req),
                Params.limit(req, FINDINGS, FINDINGS_MAX), Params.full(req)));
    }

    public WebResponse marks(WebRequest req) {
        return Params.answer(req, reports.marks(Params.limit(req, MARKS, MARKS_MAX)));
    }

    public WebResponse mark(WebRequest req) {
        Json.JsonObject body = req.bodyJson().asObject();
        String name = body.optString("name", null);
        String note = body.optString("note", null);
        String service = body.optString("service", null);
        Long at = body.has("at") && !body.get("at").isNull() ? body.getLong("at") : null;
        Marks.Mark mark;
        try {
            mark = reports.markStore().create(name, service, note, at);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Params.answer(req, reports.mark(mark)).status(HttpStatus.CREATED);
    }

    /**
     * {@code before} and {@code after} are required: a comparison with one window
     * is not a comparison, and guessing the other one would answer a question
     * nobody asked.
     */
    public WebResponse compare(WebRequest req) {
        String before = req.queryParamOrNull("before");
        String after = req.queryParamOrNull("after");
        if (before == null || after == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "compare needs both before and after, as marks or time selectors");
        }
        String service = Params.service(req);
        Selectors selectors = params.selectors();
        long now = System.currentTimeMillis();
        String until = req.queryParamOrNull("until");
        long untilAt = until == null ? now : selectors.resolve(until, now, service);
        long afterAt = selectors.resolve(after, untilAt, service);
        long beforeAt = selectors.resolve(before, afterAt, service);
        return Params.answer(req, reports.compare(beforeAt, afterAt, untilAt, service, Params.full(req)));
    }

    /**
     * The escape hatch: one read-only statement, and its rows.
     *
     * <p>A {@code POST} because a statement is a body and not a query parameter,
     * and the only endpoint here whose errors are part of the answer: a refused
     * statement or one H2 would not run is a {@code 400} naming the reason, in the
     * format the request asked for, because an agent that asked for Markdown
     * cannot read a JSON error it did not expect (agent.md).
     */
    public WebResponse sql(WebRequest req) {
        Json.JsonObject body = req.bodyJson().asObject();
        String statement = body.optString("sql", null);
        int limit = ReadOnlyQuery.LIMIT;
        if (body.has("limit") && !body.get("limit").isNull()) {
            limit = (int) body.getLong("limit");
        }
        if (limit < 1) {
            return Params.problem(req, "limit must be at least 1");
        }
        try {
            return Params.answer(req, reports.sql(statement,
                    Math.min(limit, ReadOnlyQuery.LIMIT_MAX), Params.full(req)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Params.problem(req, e.getMessage());
        }
    }

    public WebResponse check(WebRequest req) {
        Window window = params.window(req);
        Map<String, Double> rules = new LinkedHashMap<>();
        for (String rule : Check.RULES) {
            if (req.queryParamOrNull(rule) != null) {
                rules.put(rule, req.queryParam(rule, Double::parseDouble));
            }
        }
        Reports.Report report = reports.check(window, Params.service(req),
                req.queryParamOrNull("endpoint"), rules);
        Json.JsonValue pass = report.json().asObject().get("pass");
        return Params.answer(req, report)
                .header(PASS_HEADER, pass.isNull() ? "none" : String.valueOf(pass.asBoolean()));
    }
}
