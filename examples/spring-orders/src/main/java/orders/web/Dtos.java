package orders.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/** Every JSON shape this service speaks, in one place. */
public final class Dtos {

    private Dtos() {
    }

    public record LineView(Long id, Long productId, String productName, int quantity,
                           BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record EnrichedLineView(Long id, Long productId, String productName, int quantity,
                                   BigDecimal unitPrice, BigDecimal lineTotal,
                                   @Nullable Map<String, Object> book) {
    }

    public record OrderView(Long id, Long customerId, String customerName, String createdAt,
                            String status, BigDecimal total, List<LineView> lines) {
    }

    public record EnrichedOrderView(Long id, Long customerId, String customerName, String createdAt,
                                    String status, BigDecimal total, List<EnrichedLineView> lines) {
    }

    public record PageView(int page, int size, long totalElements, int totalPages, List<OrderView> content) {
    }

    public record CustomerView(Long id, String name, String email) {
    }

    public record RevenueRow(@Nullable String status, @Nullable String day, BigDecimal revenue,
                             long orders, long items) {
    }

    public record TopProductRow(@Nullable Long productId, @Nullable String sku, @Nullable String name,
                                long quantity, BigDecimal revenue) {
    }

    public record RevenueReport(int days, long queryMillis, List<RevenueRow> byStatusAndDay,
                                List<TopProductRow> topProducts) {
    }

    public record CreateOrderLine(@NotNull(message = "productId is required") Long productId,
                                  @Min(value = 1, message = "quantity must be at least 1")
                                  @Max(value = 100, message = "quantity must be at most 100") int quantity) {
    }

    public record CreateOrderRequest(@NotNull(message = "customerId is required") Long customerId,
                                     @NotEmpty(message = "at least one line is required")
                                     List<@Valid CreateOrderLine> lines) {
    }

    public record ErrorView(int status, String error, @Nullable String message) {
    }

    public record OkView(boolean ok) {
    }

    public record StatusView(String status) {
    }

    public record SlowView(long sleptMs) {
    }
}
