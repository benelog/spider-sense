package orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import orders.domain.Customer;
import orders.domain.Order;
import orders.domain.OrderLine;
import orders.domain.OrderStatus;
import orders.domain.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the database at startup when it is empty.
 * Inserts are batched (see hibernate.jdbc.batch_size / order_inserts in application.properties)
 * and the entity manager is cleared between chunks, so 50 000 orders take seconds, not minutes.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String[] FIRST = {
            "Ada", "Bram", "Cora", "Dara", "Emil", "Fay", "Gus", "Hana", "Ivo", "Jun",
            "Kira", "Liam", "Mina", "Noor", "Otto", "Pia", "Quin", "Rosa", "Sten", "Tova",
            "Uma", "Vera", "Wim", "Xan", "Yara", "Zeno"
    };
    private static final String[] LAST = {
            "Adler", "Boone", "Cerny", "Dubois", "Eriks", "Falk", "Grove", "Hollis", "Ivers", "Jansen",
            "Krause", "Lund", "Moreau", "Nilsen", "Ortiz", "Peeters", "Quist", "Roth", "Silva", "Tanaka",
            "Ulrich", "Vogel", "Walsh", "Xiao", "Yilmaz", "Zima"
    };
    private static final String[] ADJ = {
            "Quiet", "Hollow", "Silver", "Crimson", "Northern", "Forgotten", "Gilded", "Restless",
            "Amber", "Iron", "Pale", "Distant", "Winter", "Salt", "Glass", "Bitter"
    };
    private static final String[] NOUN = {
            "Atlas", "Compass", "Garden", "Harbour", "Lantern", "Machine", "Orchard", "Pattern",
            "Quarry", "Register", "Signal", "Theory", "Vessel", "Window", "Almanac", "Ledger"
    };

    private final TransactionTemplate tx;

    // Spring injects the persistence context after the constructor has run.
    @PersistenceContext
    @SuppressWarnings("NullAway.Init")
    private EntityManager em;

    @Value("${orders.seed.customers:500}")
    private int seedCustomers;

    @Value("${orders.seed.products:200}")
    private int seedProducts;

    @Value("${orders.seed.orders:50000}")
    private int seedOrders;

    public DataSeeder(TransactionTemplate tx) {
        this.tx = tx;
    }

    @Override
    public void run(String... args) {
        Long existing = tx.execute(status ->
                em.createQuery("select count(o) from Order o", Long.class).getSingleResult());
        if (existing != null && existing > 0) {
            log.info("Seed skipped, {} orders already present", existing);
            return;
        }

        long started = System.nanoTime();
        Random random = new Random(20240917L);

        List<Long> customerIds = tx.execute(status -> insertCustomers(random));
        List<Long> productIds = tx.execute(status -> insertProducts(random));
        log.info("Seeded {} customers and {} products", customerIds.size(), productIds.size());

        int chunk = 2000;
        int done = 0;
        while (done < seedOrders) {
            int size = Math.min(chunk, seedOrders - done);
            int offset = done;
            tx.executeWithoutResult(status -> insertOrders(random, customerIds, productIds, offset, size));
            done += size;
        }

        long ms = (System.nanoTime() - started) / 1_000_000;
        log.info("Seeded {} customers, {} products and {} orders in {} ms",
                seedCustomers, seedProducts, seedOrders, ms);
    }

    private List<Long> insertCustomers(Random random) {
        List<Long> ids = new ArrayList<>(seedCustomers);
        for (int i = 0; i < seedCustomers; i++) {
            String name = FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)];
            Customer customer = new Customer(name, "customer" + (i + 1) + "@example.test");
            em.persist(customer);
            if ((i + 1) % 500 == 0) {
                em.flush();
            }
        }
        em.flush();
        return collectIds(ids, "select c.id from Customer c order by c.id");
    }

    private List<Long> insertProducts(Random random) {
        List<Long> ids = new ArrayList<>(seedProducts);
        for (int i = 0; i < seedProducts; i++) {
            String name = "The " + ADJ[random.nextInt(ADJ.length)] + " " + NOUN[random.nextInt(NOUN.length)];
            BigDecimal price = BigDecimal.valueOf(500 + random.nextInt(9500), 2).setScale(2, RoundingMode.HALF_UP);
            em.persist(new Product(String.format("SKU-%05d", i + 1), name, price));
            if ((i + 1) % 500 == 0) {
                em.flush();
            }
        }
        em.flush();
        return collectIds(ids, "select p.id from Product p order by p.id");
    }

    private List<Long> collectIds(List<Long> into, String jpql) {
        into.addAll(em.createQuery(jpql, Long.class).getResultList());
        em.clear();
        return into;
    }

    private void insertOrders(Random random, List<Long> customerIds, List<Long> productIds, int offset, int count) {
        OrderStatus[] statuses = OrderStatus.values();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        for (int i = 0; i < count; i++) {
            Customer customer = em.getReference(Customer.class, customerIds.get(random.nextInt(customerIds.size())));
            LocalDateTime createdAt = now
                    .minusDays(random.nextInt(60))
                    .minusMinutes(random.nextInt(24 * 60));
            OrderStatus status = statuses[weightedStatus(random)];
            Order order = new Order(customer, createdAt, status);

            int lineCount = 1 + random.nextInt(5);
            BigDecimal total = BigDecimal.ZERO;
            for (int l = 0; l < lineCount; l++) {
                Product product = em.getReference(Product.class, productIds.get(random.nextInt(productIds.size())));
                int quantity = 1 + random.nextInt(4);
                BigDecimal unitPrice = BigDecimal.valueOf(500 + random.nextInt(9500), 2);
                OrderLine line = new OrderLine(product, quantity, unitPrice);
                order.addLine(line);
                total = total.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
            order.setTotal(total);
            em.persist(order);

            if ((offset + i + 1) % 500 == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        em.clear();
    }

    /** Mostly SHIPPED/PAID, a few NEW, a handful CANCELLED, so the report has something to group by. */
    private int weightedStatus(Random random) {
        int roll = random.nextInt(100);
        if (roll < 10) {
            return 0; // NEW
        }
        if (roll < 40) {
            return 1; // PAID
        }
        if (roll < 95) {
            return 2; // SHIPPED
        }
        return 3; // CANCELLED
    }
}
