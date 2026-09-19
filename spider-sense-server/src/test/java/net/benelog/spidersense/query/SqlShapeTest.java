package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The light parse of agent.md, over the statements the instrumentations actually
 * record: Hibernate's aliased selects, a hand-written join, a write.
 */
class SqlShapeTest {

    private static List<String> tables(String statement) {
        List<String> names = new ArrayList<>();
        for (SqlShape.TableRef table : SqlShape.of(statement).tables()) {
            names.add(table.name());
        }
        return names;
    }

    private static List<String> predicates(String statement) {
        List<String> columns = new ArrayList<>();
        for (SqlShape.ColumnRef column : SqlShape.of(statement).predicates()) {
            columns.add(column.table() + "." + column.column());
        }
        return columns;
    }

    @Test
    void anAliasedSelectResolvesItsColumnsThroughTheAliasAndReadsTheOrderBy() {
        String statement = "select i1_0.id,i1_0.name from items i1_0"
                + " where lower(i1_0.name) like ? escape ? order by i1_0.name limit ?";

        assertThat(SqlShape.of(statement).readable()).isTrue();
        assertThat(tables(statement)).containsExactly("items");
        assertThat(predicates(statement)).as("a function does not hide the column it wraps")
                .containsExactly("items.name");
    }

    @Test
    void aBareColumnBelongsToTheOnlyTable() {
        assertThat(predicates("select * from items where category = ? and supplier_id = ?"))
                .containsExactly("items.category", "items.supplier_id");
    }

    @Test
    void aJoinReadsTheOnClauseTheWhereAndTheOrderBy() {
        String statement = "select o1_0.id from orders o1_0 join customer c1_0 on c1_0.id=o1_0.customer_id"
                + " where o1_0.status=? order by o1_0.created_at desc";

        assertThat(tables(statement)).containsExactly("orders", "customer");
        assertThat(predicates(statement)).containsExactly("customer.id", "orders.customer_id",
                "orders.status", "orders.created_at");
    }

    @Test
    void anAggregateInTheOrderByIsNotAPredicate() {
        String statement = "select p.id, sum(l.quantity) from order_line l"
                + " join product p on p.id = l.product_id join orders o on o.id = l.order_id"
                + " where o.created_at >= ? group by p.id order by sum(l.quantity) desc";

        assertThat(predicates(statement)).containsExactly("product.id", "order_line.product_id",
                "orders.id", "order_line.order_id", "orders.created_at");
    }

    @Test
    void aSubqueryInAPredicateAddsItsTableAndItsColumns() {
        String statement = "select * from items where name = ?"
                + " and exists (select 1 from movements m where m.item_id = items.id)";

        assertThat(tables(statement)).containsExactly("items", "movements");
        assertThat(predicates(statement)).containsExactly("items.name", "movements.item_id",
                "items.id");
    }

    @Test
    void aWriteNamesItsTableAndOnlyItsWhereClauseIsAPredicate() {
        assertThat(tables("update items set stock = ? where id = ?")).containsExactly("items");
        assertThat(predicates("update items set stock = ? where id = ?"))
                .containsExactly("items.id");

        String insert = "insert into movements (item_id, delta) values (?, ?)";
        assertThat(SqlShape.of(insert).readable()).isTrue();
        assertThat(tables(insert)).containsExactly("movements");
        assertThat(predicates(insert)).isEmpty();
    }

    @Test
    void aCastInTheSelectListIsNeitherATableNorAPredicate() {
        String statement = "select cast(o.created_at as date) from orders o where o.total > ?";

        assertThat(tables(statement)).containsExactly("orders");
        assertThat(predicates(statement)).containsExactly("orders.total");
    }

    @Test
    void aQualifiedTableKeepsItsSchemaAndAQuotedColumnIsStillAColumn() {
        String statement = "select * from public.items where \"Name\" = ?";
        SqlShape shape = SqlShape.of(statement);

        assertThat(shape.readable()).isTrue();
        assertThat(shape.tables()).singleElement()
                .satisfies(table -> {
                    assertThat(table.name()).isEqualTo("items");
                    assertThat(table.schema()).isEqualTo("public");
                });
        assertThat(predicates(statement)).containsExactly("items.name");
    }

    @Test
    void aShapeThatCannotBeVouchedForSaysSo() {
        assertThat(SqlShape.of("select sleep(?)").readable())
                .as("no table at all").isFalse();
        assertThat(SqlShape.of("select a.x from t1 a, t2 b where x = ?").readable())
                .as("an unqualified column in a join").isFalse();
        assertThat(SqlShape.of("select x.id from (select id from items) x where x.id = ?").readable())
                .as("the alias of a derived table").isFalse();
        assertThat(SqlShape.of(null).readable()).isFalse();
        assertThat(SqlShape.of("  ").readable()).isFalse();
    }
}
