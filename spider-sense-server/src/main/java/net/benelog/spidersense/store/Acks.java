package net.benelog.spidersense.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * What a reader has decided about a finding: accepted it, or fixed it
 * (findings.adoc#acknowledgements and findings.adoc#resolutions).
 *
 * <p>A report endpoint that is slow by design sits at the top of every
 * {@code findings} answer and hides the new problem under it. An acknowledgement
 * takes it out of the way without hiding it: a finding id is stable across
 * windows, so one row is enough, and the ranking puts the acknowledged findings
 * last instead of dropping them.
 *
 * <p>A resolution is the same row with {@code resolved} set: "I fixed this; tell
 * me if it comes back". The two differ in one thing only, what a recurrence does
 * — an acknowledged finding that recurs stays acknowledged, a resolved one that
 * recurs is a {@code regression} — so they share the table, and the newer
 * decision about a finding replaces the older one.
 *
 * <p>The rows outlive the data they are about. The retention sweeper never
 * touches this table — a known finding stays known, and the spans that proved it
 * are swept long before the reader changes their mind — while
 * {@code DELETE /api/data} empties it with everything else (storage.adoc).
 */
public final class Acks {

    /**
     * One decision about a finding. {@code note} is optional.
     *
     * @param resolved whether this is a resolution rather than an acknowledgement
     */
    public record Ack(String findingId, long at, @Nullable String note, boolean resolved) {

        /** An acknowledgement. */
        public Ack(String findingId, long at, @Nullable String note) {
            this(findingId, at, note, false);
        }
    }

    /**
     * The most an id may be, which is the column's width.
     *
     * <p>The shape is checked loosely rather than against the {@code kind:hex}
     * grammar: findings.adoc#kinds may add kinds, and an id that matches no
     * finding is an acknowledgement of nothing rather than an error worth
     * refusing.
     */
    public static final int MAX_ID = 64;

    private static final int MAX_NOTE = 1024;

    private final Sql sql;
    private final LongSupplier clock;

    public Acks(Sql sql) {
        this(sql, System::currentTimeMillis);
    }

    /** @param clock what stamps a decision, in epoch milliseconds */
    public Acks(Sql sql, LongSupplier clock) {
        this.sql = sql;
        this.clock = clock;
    }

    /**
     * Acknowledges a finding; acknowledging again, or acknowledging a resolved
     * finding, replaces the row.
     *
     * @throws IllegalArgumentException when the id is blank or too long
     */
    public Ack ack(@Nullable String findingId, @Nullable String note) {
        return decide(findingId, note, false);
    }

    /**
     * Resolves a finding: its next occurrence is a {@code regression}. Resolving
     * again, or resolving an acknowledged finding, replaces the row.
     *
     * @throws IllegalArgumentException when the id is blank or too long
     */
    public Ack resolve(@Nullable String findingId, @Nullable String note) {
        return decide(findingId, note, true);
    }

    private Ack decide(@Nullable String findingId, @Nullable String note, boolean resolved) {
        String id = checked(findingId);
        long at = clock.getAsLong();
        String cutNote = note != null && note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
        sql.update("MERGE INTO ack (finding_id, at_ms, note, resolved) KEY(finding_id) VALUES (?, ?, ?, ?)",
                Arrays.asList(id, at, cutNote, resolved));
        return new Ack(id, at, cutNote, resolved);
    }

    /**
     * Withdraws an acknowledgement.
     *
     * @return whether there was one to withdraw, which is the difference between
     *         {@code 204} and {@code 404} (api.adoc); a resolution is not one
     */
    public boolean unack(@Nullable String findingId) {
        return sql.update("DELETE FROM ack WHERE finding_id = ? AND NOT resolved",
                List.of(checked(findingId))) > 0;
    }

    /**
     * Withdraws a resolution.
     *
     * @return whether there was one to withdraw; an acknowledgement is not one
     */
    public boolean unresolve(@Nullable String findingId) {
        return sql.update("DELETE FROM ack WHERE finding_id = ? AND resolved",
                List.of(checked(findingId))) > 0;
    }

    /** Every acknowledgement, newest first; resolutions are not listed. */
    public List<Ack> all(int limit) {
        return new ArrayList<>(sql.query(
                "SELECT * FROM ack WHERE NOT resolved ORDER BY at_ms DESC, finding_id LIMIT "
                        + Math.max(1, limit),
                List.of(), Acks::map));
    }

    /**
     * The acknowledgements and resolutions of these findings, by id.
     *
     * <p>One statement for the whole page of findings rather than one per row:
     * the ranking has the ids already, and the attachment must not turn a ranked
     * list into twenty queries.
     */
    public Map<String, Ack> byId(@Nullable Collection<String> findingIds) {
        Map<String, Ack> byId = new LinkedHashMap<>();
        if (findingIds == null || findingIds.isEmpty()) {
            return byId;
        }
        List<Object> params = new ArrayList<>(findingIds);
        for (Ack ack : sql.query("SELECT * FROM ack WHERE finding_id IN ("
                + Sql.placeholders(params.size()) + ")", params, Acks::map)) {
            byId.put(ack.findingId(), ack);
        }
        return byId;
    }

    private static String checked(@Nullable String findingId) {
        String id = findingId == null ? null : findingId.trim();
        if (id == null || id.isEmpty() || id.length() > MAX_ID) {
            throw new IllegalArgumentException(
                    "A finding id is 1 to " + MAX_ID + " characters: " + findingId);
        }
        return id;
    }

    private static Ack map(ResultSet rs) throws SQLException {
        return new Ack(rs.getString("finding_id"), rs.getLong("at_ms"), rs.getString("note"),
                rs.getBoolean("resolved"));
    }
}
