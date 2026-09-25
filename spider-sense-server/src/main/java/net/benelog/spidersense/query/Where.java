package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.benelog.spidersense.store.Sql;
import org.jspecify.annotations.Nullable;

/**
 * A growing {@code WHERE} with its parameters beside it, and the grammar of "a span in
 * the window" every read over spans starts from.
 *
 * <p>The window bound and the optional service are the two conditions a read must
 * never forget: without them it reads the whole table. So they are written here once,
 * and a read adds its own conditions with {@link #and}.
 */
record Where(String sql, List<Object> params) {

    Where(String sql, Object... params) {
        this(sql, Arrays.asList(params));
    }

    Where and(String more, Object... extra) {
        List<Object> combined = new ArrayList<>(params);
        combined.addAll(Arrays.asList(extra));
        return new Where(sql + " AND " + more, combined);
    }

    /** {@code column IN (?, ?, …)} over the values, which must not be empty. */
    Where andIn(String column, Collection<?> values) {
        return and(column + " IN (" + Sql.placeholders(values.size()) + ")", values.toArray());
    }

    /** The spans of the window, of one service or of every one when it is null. */
    static Where window(Window window, @Nullable String service) {
        Where where = new Where("start_ms BETWEEN ? AND ?", window.from(), window.to());
        return service == null ? where : where.and("service = ?", service);
    }

    /** The entry spans of the window: its requests. */
    static Where entries(Window window, @Nullable String service) {
        return window(window, service).and("entry");
    }

    /** The runs of the window's jobs (design.adoc#endpoint-identity). */
    static Where jobs(Window window, @Nullable String service) {
        return window(window, service).and(SpanSql.JOB);
    }
}
