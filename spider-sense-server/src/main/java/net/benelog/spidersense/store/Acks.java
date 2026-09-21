package net.benelog.spidersense.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Findings a reader has seen and accepted (agent.md, "Acknowledgements").
 *
 * <p>A report endpoint that is slow by design sits at the top of every
 * {@code findings} answer and hides the new problem under it. An acknowledgement
 * takes it out of the way without hiding it: a finding id is stable across
 * windows, so one row is enough, and the ranking puts the acknowledged findings
 * last instead of dropping them.
 *
 * <p>The rows outlive the data they are about. The retention sweeper never
 * touches this table — a known finding stays known, and the spans that proved it
 * are swept long before the reader changes their mind — while
 * {@code DELETE /api/data} empties it with everything else (storage.md).
 */
public final class Acks {

    /** One acknowledged finding. {@code note} is optional. */
    public record Ack(String findingId, long at, @Nullable String note) {
    }

    /**
     * The most an id may be, which is the column's width.
     *
     * <p>The shape is checked loosely rather than against the {@code kind:hex}
     * grammar: the kinds are agent.md's to add to, and an id that matches no
     * finding is an acknowledgement of nothing rather than an error worth
     * refusing.
     */
    public static final int MAX_ID = 64;

    private static final int MAX_NOTE = 1024;

    private final Sql sql;

    public Acks(Sql sql) {
        this.sql = sql;
    }

    /**
     * Acknowledges a finding; acknowledging again replaces the row.
     *
     * @throws IllegalArgumentException when the id is blank or too long
     */
    public Ack ack(@Nullable String findingId, @Nullable String note) {
        String id = checked(findingId);
        long at = System.currentTimeMillis();
        String cutNote = note != null && note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
        sql.update("MERGE INTO ack (finding_id, at_ms, note) KEY(finding_id) VALUES (?, ?, ?)",
                Arrays.asList(id, at, cutNote));
        return new Ack(id, at, cutNote);
    }

    /**
     * Withdraws an acknowledgement.
     *
     * @return whether there was one to withdraw, which is the difference between
     *         {@code 204} and {@code 404} (api.md)
     */
    public boolean unack(@Nullable String findingId) {
        return sql.update("DELETE FROM ack WHERE finding_id = ?",
                List.of(checked(findingId))) > 0;
    }

    /** Every acknowledgement, newest first. */
    public List<Ack> all(int limit) {
        return new ArrayList<>(sql.query(
                "SELECT * FROM ack ORDER BY at_ms DESC, finding_id LIMIT " + Math.max(1, limit),
                List.of(), Acks::map));
    }

    /**
     * The acknowledgements of these findings, by id.
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
        return new Ack(rs.getString("finding_id"), rs.getLong("at_ms"), rs.getString("note"));
    }
}
