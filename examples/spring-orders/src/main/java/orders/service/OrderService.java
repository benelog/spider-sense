package orders.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

import orders.domain.Customer;
import orders.domain.Order;
import orders.domain.OrderLine;
import orders.domain.OrderStatus;
import orders.domain.Product;
import orders.repo.CustomerRepository;
import orders.repo.OrderRepository;
import orders.repo.ProductRepository;
import orders.web.Dtos.CreateOrderLine;
import orders.web.Dtos.CreateOrderRequest;
import orders.web.Dtos.CustomerView;
import orders.web.Dtos.LineView;
import orders.web.Dtos.OrderView;
import orders.web.Dtos.PageView;
import orders.web.Dtos.RevenueReport;
import orders.web.Dtos.RevenueRow;
import orders.web.Dtos.TopProductRow;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ProductRepository products;

    public OrderService(OrderRepository orders, CustomerRepository customers, ProductRepository products) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
    }

    /** Fast: one query for the id page, one query that fetch-joins lines, customer and products. */
    @Transactional(readOnly = true)
    public PageView list(int page, int size) {
        Page<Long> ids = orders.findIdPage(PageRequest.of(page, size));
        List<OrderView> content = new ArrayList<>();
        if (!ids.getContent().isEmpty()) {
            for (Order order : orders.findAllWithLines(ids.getContent())) {
                content.add(toView(order));
            }
        }
        return new PageView(page, size, ids.getTotalElements(), ids.getTotalPages(), content);
    }

    /**
     * Slow on purpose: no fetch join anywhere, so this is 1 query for the order,
     * 1 for the customer, 1 for the line collection and 1 per line for the product - the classic N+1.
     */
    @Transactional(readOnly = true)
    public OrderView getWithLazyNPlusOne(long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order " + id + " not found"));
        List<LineView> lines = new ArrayList<>();
        for (OrderLine line : order.getLines()) {
            // Each of these initialises a separate Product proxy: one SELECT per line.
            Product product = line.getProduct();
            lines.add(new LineView(line.getId(), product.getId(), product.getName(),
                    line.getQuantity(), line.getUnitPrice(), line.lineTotal()));
        }
        Customer customer = order.getCustomer();
        return new OrderView(order.getId(), customer.getId(), customer.getName(),
                ISO.format(order.getCreatedAt()), order.getStatus().name(), order.getTotal(), lines);
    }

    /** Same load as above, but limited to the first three lines, which the caller then enriches over HTTP. */
    @Transactional(readOnly = true)
    public OrderView loadForEnrichment(long id, int maxLines) {
        OrderView full = getWithLazyNPlusOne(id);
        List<LineView> limited = full.lines().size() > maxLines
                ? List.copyOf(full.lines().subList(0, maxLines))
                : full.lines();
        return new OrderView(full.id(), full.customerId(), full.customerName(), full.createdAt(),
                full.status(), full.total(), limited);
    }

    @Transactional
    public OrderView create(CreateOrderRequest request) {
        Customer customer = customers.findById(request.customerId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "customerId " + request.customerId() + " does not exist"));

        Order order = new Order(customer, LocalDateTime.now(ZoneId.systemDefault()), OrderStatus.NEW);
        for (CreateOrderLine requested : request.lines()) {
            Product product = products.findById(requested.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "productId " + requested.productId() + " does not exist"));
            order.addLine(new OrderLine(product, requested.quantity(), product.getPrice()));
        }
        order.recomputeTotal();
        Order saved = orders.save(order);
        log.info("Created order {} for customer {} with {} lines, total {}",
                saved.getId(), customer.getId(), saved.getLines().size(), saved.getTotal());
        return toView(saved);
    }

    @Transactional
    public OrderView pay(long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order " + id + " not found"));
        if (order.getStatus() != OrderStatus.NEW) {
            throw new OrderException("Order " + id + " is not payable from " + order.getStatus());
        }
        order.setStatus(OrderStatus.PAID);
        log.info("Paid order {} for {}", id, order.getTotal());
        return toView(order);
    }

    /** Sleeps 300-900 ms inside the transaction to stand in for a carrier call. */
    @Transactional
    public OrderView ship(long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order " + id + " not found"));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new OrderException("Order " + id + " is not shippable from " + order.getStatus());
        }
        long pause = ThreadLocalRandom.current().nextLong(300, 901);
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        order.setStatus(OrderStatus.SHIPPED);
        log.info("Shipped order {} after {} ms with the carrier", id, pause);
        return toView(order);
    }

    @Transactional(readOnly = true)
    public List<CustomerView> searchCustomers(String q, int limit) {
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT);
        return customers.searchByName(needle, PageRequest.of(0, limit)).stream()
                .map(c -> new CustomerView(c.getId(), c.getName(), c.getEmail()))
                .toList();
    }

    /** The deliberately slow one: two grouping queries over every order and every line. */
    @Transactional(readOnly = true)
    public RevenueReport revenue(int days) {
        LocalDateTime since = LocalDateTime.now(ZoneId.systemDefault()).minusDays(days);
        long started = System.nanoTime();

        List<RevenueRow> byStatusAndDay = new ArrayList<>();
        for (Object[] row : orders.revenueByStatusAndDay(since)) {
            byStatusAndDay.add(new RevenueRow(
                    string(row[0]),
                    day(row[1]),
                    decimal(row[2]),
                    number(row[3]),
                    number(row[4])));
        }

        List<TopProductRow> topProducts = new ArrayList<>();
        for (Object[] row : orders.topProducts(since)) {
            topProducts.add(new TopProductRow(
                    row[0] == null ? null : ((Number) row[0]).longValue(),
                    string(row[1]),
                    string(row[2]),
                    number(row[3]),
                    decimal(row[4])));
        }

        long ms = (System.nanoTime() - started) / 1_000_000;
        log.info("Revenue report over {} days: {} groups, {} top products, {} ms",
                days, byStatusAndDay.size(), topProducts.size(), ms);
        return new RevenueReport(days, ms, byStatusAndDay, topProducts);
    }

    private OrderView toView(Order order) {
        List<LineView> lines = order.getLines().stream()
                .map(line -> new LineView(line.getId(), line.getProduct().getId(), line.getProduct().getName(),
                        line.getQuantity(), line.getUnitPrice(), line.lineTotal()))
                .toList();
        Customer customer = order.getCustomer();
        return new OrderView(order.getId(), customer.getId(), customer.getName(),
                ISO.format(order.getCreatedAt()), order.getStatus().name(), order.getTotal(), lines);
    }

    private static @Nullable String string(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static @Nullable String day(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        return value.toString();
    }

    private static long number(@Nullable Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal decimal(@Nullable Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }
}
