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
import java.util.Objects;
import java.util.Set;

import net.benelog.spidersense.store.SpanRecord;

/**
 * The spans of one trace as a tree, as the trace detail lists them and the trace
 * text draws them (api.adoc#trace, cli.adoc#trace-rendering).
 *
 * <p>A span whose {@code parentSpanId} is absent, or names no span of the trace, is
 * a root, and so is the first member, by start then span id, of a parent cycle: a
 * span that is its own parent, or spans that name each other. The roots and the
 * children of each span are ordered by start, then by span id, so two readings of
 * one trace list its spans alike (cli.adoc#text-rendering). Every span is in the
 * tree once, under the first span of its parent's id the walk reaches.
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
        Set<SpanRecord> placed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SpanRecord root : top) {
            placed.add(root);
            hang(root, byParent, placed);
        }

        // What no root reaches lies on a parent cycle or under one: a span that is its own
        // parent, or spans that name each other. Each cycle becomes a root at its first
        // member by start, then span id, and the rest of it hangs below as usual; the
        // earliest span left says which cycle comes next.
        if (placed.size() < spans.size()) {
            Map<String, SpanRecord> byId = new HashMap<>();
            for (SpanRecord span : spans) {
                byId.putIfAbsent(span.spanId(), span);
            }
            List<SpanRecord> left = new ArrayList<>();
            for (SpanRecord span : spans) {
                if (!placed.contains(span)) {
                    left.add(span);
                }
            }
            left.sort(BY_START);
            for (SpanRecord span : left) {
                if (placed.contains(span)) {
                    continue;
                }
                SpanRecord root = firstOfTheCycle(span, byId, placed);
                top.add(root);
                placed.add(root);
                hang(root, byParent, placed);
            }
            top.sort(BY_START);
        }
        this.roots = Collections.unmodifiableList(top);
    }

    /**
     * The first member, by start then span id, of the parent cycle that climbing the
     * parents from {@code span} runs into.
     */
    private static SpanRecord firstOfTheCycle(SpanRecord span, Map<String, SpanRecord> byId,
            Set<SpanRecord> placed) {
        IdentityHashMap<SpanRecord, Integer> step = new IdentityHashMap<>();
        List<SpanRecord> climbed = new ArrayList<>();
        SpanRecord current = span;
        while (!step.containsKey(current)) {
            step.put(current, climbed.size());
            climbed.add(current);
            String parentId = current.parentSpanId();
            SpanRecord parent = parentId == null ? null : byId.get(parentId);
            if (parent == null || placed.contains(parent)) {
                // A placed parent has taken its children already, so this is only a guard.
                return current;
            }
            current = parent;
        }
        // The climb came back to a span it had passed: from there on, it went round the cycle.
        int from = Objects.requireNonNull(step.get(current));
        return Collections.min(climbed.subList(from, climbed.size()), BY_START);
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
     * name its id, so a second span of the same id finds none left, and none that is
     * placed already, so a cycle stops at its root.
     */
    private void hang(SpanRecord top, Map<String, List<SpanRecord>> byParent,
            Set<SpanRecord> placed) {
        Deque<SpanRecord> stack = new ArrayDeque<>();
        stack.push(top);
        while (!stack.isEmpty()) {
            SpanRecord span = stack.pop();
            List<SpanRecord> kids = byParent.remove(span.spanId());
            if (kids == null) {
                continue;
            }
            kids.removeIf(placed::contains);
            if (kids.isEmpty()) {
                continue;
            }
            kids.sort(BY_START);
            children.put(span, Collections.unmodifiableList(kids));
            for (SpanRecord kid : kids) {
                placed.add(kid);
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
