package net.benelog.spidersense.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.benelog.spidersense.store.SpanRecord;

/**
 * The spans of one trace as a tree, as the trace detail lists them and the trace
 * text draws them (api.adoc#trace, cli.adoc#trace-rendering).
 *
 * <p>A span whose {@code parentSpanId} is absent, or names no span of the trace, is
 * a root. The roots and the children of each span are ordered by start, then by
 * span id, so two readings of one trace list its spans alike
 * (cli.adoc#text-rendering). Every span is in the tree once, under the first span
 * of its parent's id the walk reaches.
 *
 * <p>It is built and walked with loops rather than recursion: a recursive method
 * under {@code @WithSpan} nests a trace deeper than the call stack goes.
 */
public final class SpanTree {

    private static final Comparator<SpanRecord> BY_START =
            Comparator.comparingLong(SpanRecord::startNanos).thenComparing(SpanRecord::spanId);

    private final List<SpanRecord> roots;
    /** By identity: a trace can hold two spans equal in every field, and each is a node of its own. */
    private final IdentityHashMap<SpanRecord, List<SpanRecord>> children = new IdentityHashMap<>();

    private SpanTree(List<SpanRecord> spans) {
        Set<String> ids = new HashSet<>();
        for (SpanRecord span : spans) {
            ids.add(span.spanId());
        }
        List<SpanRecord> top = new ArrayList<>();
        Map<String, List<SpanRecord>> byParent = new HashMap<>();
        for (SpanRecord span : spans) {
            String parent = span.parentSpanId();
            if (parent == null || !ids.contains(parent)) {
                top.add(span);
            } else {
                byParent.computeIfAbsent(parent, id -> new ArrayList<>()).add(span);
            }
        }
        top.sort(BY_START);
        for (SpanRecord root : top) {
            hang(root, byParent);
        }
        this.roots = Collections.unmodifiableList(top);
    }

    /** The tree of these spans, in whatever order they come. */
    public static SpanTree of(List<SpanRecord> spans) {
        return new SpanTree(spans);
    }

    /** The spans with no parent in the trace, by start then span id. */
    public List<SpanRecord> roots() {
        return roots;
    }

    /** The children of a span of this tree, by start then span id; empty for a leaf. */
    public List<SpanRecord> children(SpanRecord span) {
        return children.getOrDefault(span, List.of());
    }

    /** Every span once, parents before children and each group by start: the waterfall's order. */
    public List<SpanRecord> ordered() {
        List<SpanRecord> ordered = new ArrayList<>();
        Deque<SpanRecord> stack = new ArrayDeque<>();
        pushReversed(stack, roots);
        while (!stack.isEmpty()) {
            SpanRecord span = stack.pop();
            ordered.add(span);
            pushReversed(stack, children(span));
        }
        return ordered;
    }

    /**
     * Hangs everything below {@code top} under it: each span takes the children that
     * name its id, so a second span of the same id finds none left.
     */
    private void hang(SpanRecord top, Map<String, List<SpanRecord>> byParent) {
        Deque<SpanRecord> stack = new ArrayDeque<>();
        stack.push(top);
        while (!stack.isEmpty()) {
            SpanRecord span = stack.pop();
            List<SpanRecord> kids = byParent.remove(span.spanId());
            if (kids == null) {
                continue;
            }
            kids.sort(BY_START);
            children.put(span, Collections.unmodifiableList(kids));
            for (SpanRecord kid : kids) {
                stack.push(kid);
            }
        }
    }

    private static void pushReversed(Deque<SpanRecord> stack, List<SpanRecord> spans) {
        for (int i = spans.size() - 1; i >= 0; i--) {
            stack.push(spans.get(i));
        }
    }
}
