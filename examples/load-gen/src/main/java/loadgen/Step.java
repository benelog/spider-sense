package loadgen;

import java.net.URI;

/**
 * One HTTP call.
 * A path may contain the placeholder {@code {id}}, which the runner replaces with the id
 * returned by the previous step of the same scenario (the create - pay - ship sequence).
 */
public record Step(Target target, String method, String path, String body) {

    public static final String ID_PLACEHOLDER = "{id}";

    public static Step get(Target target, String path) {
        return new Step(target, "GET", path, null);
    }

    public static Step post(Target target, String path, String body) {
        return new Step(target, "POST", path, body);
    }

    public boolean needsId() {
        return path.contains(ID_PLACEHOLDER);
    }

    public Step withId(long id) {
        return needsId() ? new Step(target, method, path.replace(ID_PLACEHOLDER, Long.toString(id)), body) : this;
    }

    public URI uri(String bookstoreBase, String ordersBase) {
        String base = target == Target.BOOKSTORE ? bookstoreBase : ordersBase;
        return URI.create(base + path);
    }
}
