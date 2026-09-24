package orders.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import orders.service.Book;
import orders.service.BookstoreClient;
import orders.service.OrderService;
import orders.web.Dtos.CreateOrderRequest;
import orders.web.Dtos.EnrichedLineView;
import orders.web.Dtos.EnrichedOrderView;
import orders.web.Dtos.LineView;
import orders.web.Dtos.OrderView;
import orders.web.Dtos.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;
    private final BookstoreClient bookstore;

    public OrderController(OrderService service, BookstoreClient bookstore) {
        this.service = service;
        this.bookstore = bookstore;
    }

    @GetMapping
    public PageView list(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        return service.list(Math.max(page, 0), Math.clamp(size, 1, 200));
    }

    @GetMapping("/{id}")
    public OrderView one(@PathVariable long id) {
        return service.getWithLazyNPlusOne(id);
    }

    /**
     * An N+1 over HTTP, on purpose: one call to silk-bookstore per line of the order.
     * A seeded order has two to six lines, so most requests make the five calls that
     * make this an {@code n-plus-one-http} finding.
     * When the bookstore is down every "book" is null and the response is still 200.
     */
    @GetMapping("/{id}/enriched")
    public EnrichedOrderView enriched(@PathVariable long id) {
        OrderView order = service.getWithLazyNPlusOne(id);
        List<EnrichedLineView> lines = new ArrayList<>(order.lines().size());
        for (LineView line : order.lines()) {
            Book book = bookstore.findBook(line.productId());
            lines.add(new EnrichedLineView(line.id(), line.productId(), line.productName(),
                    line.quantity(), line.unitPrice(), line.lineTotal(), book));
        }
        return new EnrichedOrderView(order.id(), order.customerId(), order.customerName(),
                order.createdAt(), order.status(), order.total(), lines);
    }

    /**
     * The same answer with the loop fixed: one call for the whole set of product ids,
     * which is the batch endpoint an {@code n-plus-one-http} finding asks for.
     * It is here so the fix can be measured against the endpoint above with
     * {@code compare}, and it is the shape to copy, not the one above.
     */
    @GetMapping("/{id}/enriched-batch")
    public EnrichedOrderView enrichedBatch(@PathVariable long id) {
        OrderView order = service.getWithLazyNPlusOne(id);
        List<Long> productIds = new ArrayList<>(order.lines().size());
        for (LineView line : order.lines()) {
            productIds.add(line.productId());
        }
        Map<Long, Book> books = bookstore.findBooks(productIds);
        List<EnrichedLineView> lines = new ArrayList<>(order.lines().size());
        for (LineView line : order.lines()) {
            lines.add(new EnrichedLineView(line.id(), line.productId(), line.productName(),
                    line.quantity(), line.unitPrice(), line.lineTotal(),
                    books.get(line.productId())));
        }
        return new EnrichedOrderView(order.id(), order.customerId(), order.customerName(),
                order.createdAt(), order.status(), order.total(), lines);
    }

    @PostMapping
    public ResponseEntity<OrderView> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/{id}/pay")
    public OrderView pay(@PathVariable long id) {
        return service.pay(id);
    }

    @PostMapping("/{id}/ship")
    public OrderView ship(@PathVariable long id) {
        return service.ship(id);
    }
}
