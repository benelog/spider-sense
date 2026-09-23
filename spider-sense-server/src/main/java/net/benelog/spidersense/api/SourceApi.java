package net.benelog.spidersense.api;

import net.benelog.spidersense.source.SourceRoots;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * {@code GET /api/source?frame=…}: the lines around a code frame's line, read from
 * the file on request and never stored (api.md).
 *
 * <p>The UI asks once per frame it shows, and the answer is also what makes the
 * frame a link: the absolute path and the line are what an editor URL needs. A
 * frame that does not resolve under {@code spidersense.source.dirs} is a
 * {@code 404}, and the page shows nothing for it.
 */
public final class SourceApi {

    private final SourceRoots roots;

    public SourceApi(SourceRoots roots) {
        this.roots = roots;
    }

    public void register(App app) {
        app.get("/api/source", "The lines around a code frame's line", this::source);
    }

    public WebResponse source(WebRequest req) {
        String frame = req.queryParamOrNull("frame");
        if (frame == null || frame.isBlank()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "source needs a frame");
        }
        SourceRoots.Snippet snippet = roots.read(frame);
        if (snippet == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No source for frame: " + frame);
        }
        return WebResponse.json(Json.obj()
                .put("frame", frame.trim())
                .put("file", snippet.file().toString())
                .put("line", snippet.line())
                .put("start", snippet.start())
                .put("lines", Codecs.strings(snippet.lines())));
    }
}
