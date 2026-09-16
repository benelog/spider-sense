package orders.repo;

import java.time.LocalDateTime;
import java.util.List;

import orders.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Page of ids only, so the collection fetch below can be done in one extra query without in-memory paging. */
    @Query("select o.id from Order o order by o.id desc")
    Page<Long> findIdPage(Pageable pageable);

    /** The fast list endpoint: one query, lines and products fetch-joined, no N+1. */
    @Query("""
            select distinct o from Order o
              join fetch o.customer
              join fetch o.lines l
              join fetch l.product
            where o.id in :ids
            order by o.id desc
            """)
    List<Order> findAllWithLines(@Param("ids") List<Long> ids);

    /**
     * The slow report: a full scan of orders joined with their lines, grouped by status and day.
     * Native so the SQL shows up verbatim in the trace.
     * H2 has no DATE() function; CAST(... AS DATE) is the equivalent.
     */
    @Query(value = """
            SELECT o.status AS status,
                   CAST(o.created_at AS DATE) AS order_day,
                   SUM(o.total) AS revenue,
                   COUNT(DISTINCT o.id) AS order_count,
                   SUM(l.quantity) AS item_count
            FROM orders o
            JOIN order_line l ON l.order_id = o.id
            WHERE o.created_at >= :since
            GROUP BY o.status, CAST(o.created_at AS DATE)
            ORDER BY order_day DESC, status ASC
            """, nativeQuery = true)
    List<Object[]> revenueByStatusAndDay(@Param("since") LocalDateTime since);

    /** Second half of the slow report: top 10 products by quantity sold. */
    @Query(value = """
            SELECT p.id AS product_id,
                   p.sku AS sku,
                   p.name AS name,
                   SUM(l.quantity) AS quantity,
                   SUM(l.quantity * l.unit_price) AS revenue
            FROM order_line l
            JOIN product p ON p.id = l.product_id
            JOIN orders o ON o.id = l.order_id
            WHERE o.created_at >= :since
            GROUP BY p.id, p.sku, p.name
            ORDER BY quantity DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> topProducts(@Param("since") LocalDateTime since);
}
