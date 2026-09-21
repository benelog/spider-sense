package loadgen;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP call.
 * A path may contain the placeholder {@code {id}}, which the runner replaces with the id
 * returned by the previous step of the same scenario (the create - pay - ship sequence).
 * A step that has a body carries the content type to send it with.
 */
public record Step(Target target, String method, String path, @Nullable String body, @Nullable String contentType) {

    public static final String ID_PLACEHOLDER = "{id}";
    public static final String JSON = "application/json";
    public static final String FORM = "application/x-www-form-urlencoded";

    public static Step get(Target target, String path) {
        return new Step(target, "GET", path, null, null);
    }

    public static Step post(Target target, String path, String body) {
        return new Step(target, "POST", path, body, JSON);
    }

    /** A POST whose body is {@code name=value&…}, which is what a servlet reads as a parameter. */
    public static Step form(Target target, String path, String body) {
        return new Step(target, "POST", path, body, FORM);
    }

    public boolean needsId() {
        return path.contains(ID_PLACEHOLDER);
    }

    public Step withId(long id) {
        return needsId()
                ? new Step(target, method, path.replace(ID_PLACEHOLDER, Long.toString(id)), body, contentType)
                : this;
    }

    public URI uri(Options options) {
        return URI.create(options.baseUrl(target) + path);
    }
}
