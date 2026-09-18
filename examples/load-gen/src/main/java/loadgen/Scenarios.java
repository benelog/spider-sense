package loadgen;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The weighted scenario table.
 * Weights are relative, not percentages; they add up to {@link #TOTAL_WEIGHT}.
 * The paths are the routes silk-bookstore, spring-orders and servlet-warehouse actually
 * publish, and the random ids stay inside what those three applications seed.
 */
public final class Scenarios {

    /** silk-bookstore seeds this many books and this many authors. */
    public static final int BOOKS = 200_000;
    public static final int AUTHORS = 200;
    /** Only the first 100 books have reviews, and /books/{id} runs its N+1 only when there are reviews. */
    public static final int REVIEWED_BOOKS = 100;
    /** spring-orders seeds this many orders, customers and products. */
    public static final int ORDERS = 50_000;
    public static final int CUSTOMERS = 500;
    public static final int PRODUCTS = 200;
    /** servlet-warehouse seeds this many items, of which only the first few hundred have movements. */
    public static final int WAREHOUSE_ITEMS = 400_000;
    public static final int WAREHOUSE_ITEMS_WITH_MOVEMENTS = 200;

    /** Words that really occur in silk-bookstore's seeded titles, so a search has rows to return. */
    private static final String[] WORDS = {
            "dragon", "lighthouse", "cartographer", "orchard", "ledger", "harbour", "signal",
            "archive", "meridian", "clockwork", "frontier", "compass", "chorus", "atlas",
            "silent", "crimson", "hollow", "gilded", "northern", "patient", "restless",
            "salt", "winter", "amber", "iron", "quiet", "distant", "broken", "bright"
    };

    /** Words that really occur in servlet-warehouse's seeded item names. */
    private static final String[] WAREHOUSE_WORDS = {
            "steel", "oak", "copper", "matte", "glass", "woven", "carbon", "nylon", "brass", "cedar",
            "bracket", "hinge", "valve", "gasket", "spindle", "bearing", "sprocket", "flange",
            "coupling", "washer"
    };

    private static final List<Scenario> ALL = List.of(
            // --- silk-bookstore: 57 of the 109 weight ---
            new Scenario("bookstore.book-page", 20,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/books/" + reviewedBookId(r)))),
            new Scenario("bookstore.api-book", 15,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/api/books/" + bookId(r)))),
            new Scenario("bookstore.search", 8,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/books?q=" + word(r)))),
            new Scenario("bookstore.stats", 2,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/api/books/stats"))),
            new Scenario("bookstore.slow", 2,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/api/slow?ms=" + (400 + r.nextInt(800))))),
            new Scenario("bookstore.flaky", 5,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/api/flaky"))),
            new Scenario("bookstore.missing", 2,
                    r -> List.of(Step.get(Target.BOOKSTORE, "/api/books/" + bookId(r) + "/missing"))),
            new Scenario("bookstore.post-review", 3,
                    r -> List.of(Step.post(Target.BOOKSTORE, "/api/reviews",
                            // one review in ten carries a rating outside 1..5, so the app answers 400
                            "{\"bookId\":" + (1 + r.nextInt(REVIEWED_BOOKS))
                                    + ",\"authorId\":" + (1 + r.nextInt(AUTHORS))
                                    + ",\"rating\":" + (r.nextInt(10) == 0 ? 9 : 1 + r.nextInt(5))
                                    + ",\"body\":\"" + word(r) + " " + word(r) + ", from load-gen\"}"))),

            // --- spring-orders: 52 of the 109 weight ---
            new Scenario("orders.page", 15,
                    r -> List.of(Step.get(Target.ORDERS, "/api/orders?page=" + r.nextInt(50) + "&size=20"))),
            new Scenario("orders.by-id", 10,
                    r -> List.of(Step.get(Target.ORDERS, "/api/orders/" + orderId(r)))),
            new Scenario("orders.enriched", 8,
                    r -> List.of(Step.get(Target.ORDERS, "/api/orders/" + orderId(r) + "/enriched"))),
            new Scenario("orders.revenue-report", 2,
                    r -> List.of(Step.get(Target.ORDERS, "/api/reports/revenue?days=" + (7 + r.nextInt(84))))),
            new Scenario("orders.customer-search", 5,
                    r -> List.of(Step.get(Target.ORDERS, "/api/customers/search?q=" + letter(r)))),
            new Scenario("orders.checkout", 4, Scenarios::checkout),
            new Scenario("orders.pay-shipped", 2,
                    r -> List.of(Step.post(Target.ORDERS, "/api/orders/" + orderId(r) + "/pay", ""))),
            new Scenario("orders.flaky", 5,
                    r -> List.of(Step.get(Target.ORDERS, "/api/flaky"))),
            new Scenario("orders.slow", 1,
                    r -> List.of(Step.get(Target.ORDERS, "/api/slow?ms=" + (600 + r.nextInt(1200))))),

            new Scenario("warehouse.item-page", 12,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/items/" + sku(movedItemId(r))))),
            new Scenario("warehouse.stock", 10,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/api/stock/" + sku(itemId(r))))),
            new Scenario("warehouse.search", 5,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/items?q=" + warehouseWord(r)))),
            new Scenario("warehouse.flaky", 4,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/api/flaky"))),
            new Scenario("warehouse.async", 3,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/api/async?ms=" + (300 + r.nextInt(1600))))),
            new Scenario("warehouse.movement", 3,
                    r -> List.of(Step.form(Target.WAREHOUSE, "/api/movements",
                            "sku=" + sku(itemId(r)) + "&delta=" + (-50 + r.nextInt(71)) + "&note=load-gen"))),
            new Scenario("warehouse.report", 2,
                    r -> List.of(Step.get(Target.WAREHOUSE, "/api/report")))
    );

    public static final int TOTAL_WEIGHT = ALL.stream().mapToInt(Scenario::weight).sum();

    private Scenarios() {
    }

    public static List<Scenario> all() {
        return ALL;
    }

    /** Picks a scenario with probability proportional to its weight. */
    public static Scenario pick(Random random) {
        int roll = random.nextInt(TOTAL_WEIGHT);
        for (Scenario scenario : ALL) {
            roll -= scenario.weight();
            if (roll < 0) {
                return scenario;
            }
        }
        return ALL.get(ALL.size() - 1);
    }

    /** Create an order, then pay it, then ship it; the last two steps need the id from the first response. */
    private static List<Step> checkout(Random r) {
        int lines = 1 + r.nextInt(3);
        StringBuilder body = new StringBuilder("{\"customerId\":").append(customerId(r)).append(",\"lines\":[");
        for (int i = 0; i < lines; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"productId\":").append(productId(r))
                    .append(",\"quantity\":").append(1 + r.nextInt(3)).append('}');
        }
        body.append("]}");
        return List.of(
                Step.post(Target.ORDERS, "/api/orders", body.toString()),
                Step.post(Target.ORDERS, "/api/orders/" + Step.ID_PLACEHOLDER + "/pay", ""),
                Step.post(Target.ORDERS, "/api/orders/" + Step.ID_PLACEHOLDER + "/ship", ""));
    }

    private static int bookId(Random r) {
        return 1 + r.nextInt(BOOKS);
    }

    /** Four times in five a book that has reviews, so the book page really does its N+1. */
    private static int reviewedBookId(Random r) {
        return r.nextInt(5) == 0 ? bookId(r) : 1 + r.nextInt(REVIEWED_BOOKS);
    }

    private static int orderId(Random r) {
        return 1 + r.nextInt(ORDERS);
    }

    private static int customerId(Random r) {
        return 1 + r.nextInt(CUSTOMERS);
    }

    private static int productId(Random r) {
        return 1 + r.nextInt(PRODUCTS);
    }

    private static int itemId(Random r) {
        return 1 + r.nextInt(WAREHOUSE_ITEMS);
    }

    /** Four times in five an item that has movements, so the item page really does its N+1. */
    private static int movedItemId(Random r) {
        return r.nextInt(5) == 0 ? itemId(r) : 1 + r.nextInt(WAREHOUSE_ITEMS_WITH_MOVEMENTS);
    }

    /** The warehouse addresses an item by its stock keeping unit, not by its number. */
    public static String sku(int id) {
        return String.format(Locale.ROOT, "SKU-%06d", id);
    }

    private static String warehouseWord(Random r) {
        return WAREHOUSE_WORDS[r.nextInt(WAREHOUSE_WORDS.length)];
    }

    private static String word(Random r) {
        return WORDS[r.nextInt(WORDS.length)];
    }

    private static String letter(Random r) {
        return String.valueOf((char) ('a' + r.nextInt(26)));
    }
}
