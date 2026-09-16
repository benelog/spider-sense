package orders.repo;

import java.util.List;

import orders.domain.Customer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /** Deliberately a leading-wildcard LIKE, but over only 500 rows, so this one stays fast. */
    @Query("select c from Customer c where lower(c.name) like concat('%', :q, '%') order by c.id")
    List<Customer> searchByName(@Param("q") String q, Pageable pageable);
}
