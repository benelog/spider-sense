package orders.web;

import java.util.List;

import orders.service.OrderService;
import orders.web.Dtos.CustomerView;
import orders.web.Dtos.RevenueReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final OrderService service;

    public ReportController(OrderService service) {
        this.service = service;
    }

    /** Slow on purpose: two grouping queries over all seeded orders and lines. */
    @GetMapping("/api/reports/revenue")
    public RevenueReport revenue(@RequestParam(defaultValue = "90") int days) {
        return service.revenue(Math.clamp(days, 1, 3650));
    }

    /** Fast, for contrast with the report above. */
    @GetMapping("/api/customers/search")
    public List<CustomerView> searchCustomers(@RequestParam(defaultValue = "") String q,
                                              @RequestParam(defaultValue = "20") int limit) {
        return service.searchCustomers(q, Math.clamp(limit, 1, 200));
    }
}
