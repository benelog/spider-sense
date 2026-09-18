# N+1 and over-fetching in JPA and Hibernate

The `n-plus-one` finding names the repeated statement and the frame that issued it; the statement's shape says which mapping caused it, and the mapping says which fix fits.
Every fix below is proven the same way: the trace after the change shows one statement where `medianRepeats` were, and `compare` shows the endpoint's `dbCallsPerRequest` dropping to a small constant.

## Reading the repeated statement

| Repeated statement | Cause |
|---|---|
| `select … from order_line where order_id = ?` per order | a lazy `@OneToMany` walked in a loop (`order.getLines()`) |
| `select … from product where id = ?` per line | a lazy `@ManyToOne` (`line.getProduct().getName()`), the most common one |
| `select … from author where id = ?` per row, from a `Serializer` or template frame | lazy loading during rendering, often with `open-in-view` keeping the session open into the view |
| `select count(*) …` beside every page | `Page<T>` when only the rows are wanted (`Slice<T>` skips the count) |
| the same `select … where id = ?` twice in one request | two services loading the same entity in separate transactions; one transaction, or pass the entity |

The `code` frame in the finding is the line to change, and it is usually not where the query text lives.

## The fixes, from narrowest to widest

**A fetch join for one use.** The query that serves this endpoint loads the association in the same statement; other uses of the entity are untouched.

```java
@Query("select o from Order o join fetch o.lines l join fetch l.product where o.id = :id")
Optional<Order> findWithLines(@Param("id") long id);
```

With two collections a `join fetch` multiplies rows; use `distinct` (Hibernate 6 de-duplicates without passing it to SQL) or fetch the second collection in a second query.
Paging and `join fetch` on a collection do not combine: Hibernate pages in memory and warns `HHH90003004`; page the ids first, then fetch.

**An entity graph for one repository method.** The same effect without writing the JPQL:

```java
@EntityGraph(attributePaths = {"lines", "lines.product"})
Optional<Order> findById(long id);
```

**Batch fetching for every use.** When the entity is loaded through many paths and each walks the association, Hibernate can load the association for the whole batch of loaded parents in one `in (?, ?, …)` statement:

```properties
spring.jpa.properties.hibernate.default_batch_fetch_size=100
```

or `@BatchSize(size = 100)` on the association.
This turns N statements into N/100, not into one; the finding disappears because the repeat count falls under 5, and `dbCallsPerRequest` in `compare` says how far.

**A projection for a read-only screen.** When the endpoint reads a few fields of many rows, loading entities is the cost, not the association:

```java
public record OrderRow(long id, String customer, BigDecimal total) {}

@Query("select new orders.web.OrderRow(o.id, c.name, o.total) from Order o join o.customer c where o.status = :status")
List<OrderRow> rows(@Param("status") OrderStatus status);
```

Spring Data's interface projections (`interface OrderSummary { long getId(); … }`) do the same over a derived query.
Nothing enters the persistence context, so there is nothing to lazily load.

**A second query with `in`.** In plain JDBC, or when the mapping is not to be touched: collect the ids, load the children with one `where parent_id in (?, ?, …)`, and group them in a map.
JDBC drivers and databases cap the list (Oracle at 1,000, H2 and PostgreSQL by statement size); chunk it.

## What not to do

- `FetchType.EAGER` on the association: it fixes this endpoint and loads the association on every other query of the entity, including the ones that never read it; the N+1 moves rather than disappears, and often becomes a cartesian product.
- `spring.jpa.open-in-view=true` as the fix: it hides the `LazyInitializationException` and keeps the N+1, now inside the view.
- A second-level cache to paper over an N+1: the queries stay, the cache answers them, and the first request after a restart pays all of them.

## Checking

```bash
java -jar "$SENSE" trace <one of the finding's trace ids> --full     # before: the repeated rows, collapsed with ×N
# change, restart, exercise the same endpoint
java -jar "$SENSE" findings --since=start                            # the n-plus-one should be gone
java -jar "$SENSE" compare --before=before --after=after             # dbCallsPerRequest for the endpoint, before and after
```

`spring.jpa.show-sql` and Hibernate's statement logging are not needed for any of this; the statements are in the trace, with their timing, in the order they ran.
