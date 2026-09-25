package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The light parse of findings.adoc#schema, over the statements the instrumentations actually
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

    /** The agent's sanitizer keeps comments; a hint or a trailing note names no column. */
    @Test
    void theWordsOfACommentAreNoColumns() {
        assertThat(predicates("select * from items where id = ? -- trailing comment here"))
                .containsExactly("items.id");
        assertThat(predicates("select * from items where id = ? /* hint */ and sku = ?"))
                .containsExactly("items.id", "items.sku");
        assertThat(predicates("select * from items where id = ?\n-- a note\nand sku = ? /*traceparent=?*/"))
                .containsExactly("items.id", "items.sku");
        assertThat(predicates("select * from items where name = '-- not a comment' and sku = ?"))
                .containsExactly("items.name", "items.sku");
    }

    /** MySQL's "#" runs to the end of the line, and PostgreSQL nests block comments. */
    @Test
    void aHashCommentAndANestedBlockCommentAreNoColumns() {
        assertThat(predicates("select * from items where id = ? # mysql comment here"))
                .containsExactly("items.id");
        assertThat(predicates("select * from items where id = ? #note\nand sku = ?"))
                .containsExactly("items.id", "items.sku");
        assertThat(predicates("select * from items where id = ? /* outer /* inner */ tail */ and sku = ?"))
                .containsExactly("items.id", "items.sku");
        assertThat(predicates("select * from items where data #>> ? = ? and sku = ?"))
                .as("PostgreSQL's #>> is an operator, not a comment")
                .containsExactly("items.data", "items.sku");
    }

    @Test
    void aSelectListAliasInTheOrderByIsNoColumn() {
        assertThat(predicates("select id, name, price * qty as total from items where name = ? order by total desc"))
                .containsExactly("items.name");
    }

    @Test
    void aSetOperatorEndsTheWhereItFollows() {
        assertThat(predicates("select id from items where name = ? except select id from items where sku = ?"))
                .containsExactly("items.name", "items.sku");
        assertThat(predicates("select id from items where name = ? intersect select id from items"))
                .containsExactly("items.name");
    }

    /** A keyword that is also a column's name is that column when a comparison follows it. */
    @Test
    void aColumnNamedByAKeywordIsReadInAComparison() {
        assertThat(predicates("select * from events where date = ? and time > ? and first is null"))
                .containsExactly("events.date", "events.time", "events.first");
        assertThat(predicates("select * from events where kind = ? order by at_ms nulls first"))
                .containsExactly("events.kind", "events.at_ms");
        assertThat(predicates("select * from events where created > current_date - interval ? and kind = ?"))
                .containsExactly("events.created", "events.kind");
    }

    /** An interval's unit, a time zone and a full-text modifier are words of the expression. */
    @Test
    void anIntervalUnitATimeZoneAndAFullTextModifierAreNoColumns() {
        assertThat(predicates("select * from orders where created_at > date_sub(now(), interval ? day)"))
                .containsExactly("orders.created_at");
        assertThat(predicates("select * from orders o where o.created_at >= now() - interval ? hour"
                + " and o.status = ?"))
                .containsExactly("orders.created_at", "orders.status");
        assertThat(predicates("select * from orders where created_at > now() - interval 7 days"
                + " and due_at < now() + interval ? day to second and paid_at > interval (? * 2) year_month"))
                .containsExactly("orders.created_at", "orders.due_at", "orders.paid_at");
        assertThat(predicates("select o.id from orders o where o.created_at at time zone ? > ?"
                + " and o.status = ?"))
                .containsExactly("orders.created_at", "orders.status");
        assertThat(predicates("select * from orders where created_at at time zone 'UTC' = ? and id = ?"))
                .containsExactly("orders.created_at", "orders.id");
        assertThat(predicates("select * from orders where match(name) against (? in boolean mode)"
                + " and status = ?"))
                .containsExactly("orders.name", "orders.status");
        assertThat(predicates("select * from orders where match(name) against"
                + " (? in natural language mode with query expansion)"))
                .containsExactly("orders.name");
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
    void aPredicateAfterAClosedSubqueryIsStillAPredicate() {
        String statement = "select * from items i where i.id in (select l.item_id from lines l)"
                + " and i.name = ?";

        assertThat(SqlShape.of(statement).readable()).isTrue();
        assertThat(tables(statement)).containsExactly("items", "lines");
        assertThat(predicates(statement)).as("the where the subquery interrupted goes on after its )")
                .containsExactly("items.id", "items.name");
    }

    @Test
    void aNestedSubqueryRestoresEachRegionItInterrupted() {
        String statement = "select * from items i where i.id in (select l.item_id from lines l"
                + " where l.order_id in (select o.id from orders o) and l.quantity > ?)"
                + " and i.name = ? order by i.created_at";

        assertThat(predicates(statement)).containsExactly("items.id", "lines.order_id",
                "lines.quantity", "items.name", "items.created_at");
    }

    @Test
    void aSubqueryInTheSelectListLeavesTheSelectListOutsideItsWhere() {
        String statement = "select (select max(l.quantity) from lines l where l.order_id = ?) as top,"
                + " i.name from items i where i.category = ?";

        assertThat(SqlShape.of(statement).readable())
                .as("i.name after the ) is a selected column, not a predicate naming an unseen alias")
                .isTrue();
        assertThat(predicates(statement)).containsExactly("lines.order_id", "items.category");
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

    /** A locking clause names no table: its "update" opens no table list, its "of" names an alias. */
    @Test
    void aLockingClauseNamesNoTable() {
        String skipLocked = "select * from orders where id = ? for update skip locked";
        assertThat(tables(skipLocked)).containsExactly("orders");
        assertThat(predicates(skipLocked)).containsExactly("orders.id");

        String hibernate = "select o1_0.id from orders o1_0 where o1_0.status=? for no key update of o1_0";
        assertThat(SqlShape.of(hibernate).readable()).isTrue();
        assertThat(tables(hibernate)).containsExactly("orders");
        assertThat(predicates(hibernate)).containsExactly("orders.status");

        assertThat(tables("select * from orders where id = ? for update nowait")).containsExactly("orders");
        assertThat(tables("select * from orders where id = ? for share")).containsExactly("orders");

        String queue = "update jobs set state = ? where id = (select j.id from jobs j where j.state = ?"
                + " order by j.id limit ? for update skip locked) returning id";
        assertThat(tables(queue)).containsExactly("jobs");
        assertThat(predicates(queue)).containsExactly("jobs.id", "jobs.state");
    }

    /** Inside extract, trim, substring and overlay, "from" and "for" separate arguments. */
    @Test
    void aFromInsideAFunctionSeparatesItsArguments() {
        String extract = "select * from orders where extract(year from created_at) = ?";
        assertThat(tables(extract)).containsExactly("orders");
        assertThat(predicates(extract)).containsExactly("orders.created_at");

        String trim = "select * from orders where trim(both from name) = ?";
        assertThat(tables(trim)).containsExactly("orders");
        assertThat(predicates(trim)).containsExactly("orders.name");

        assertThat(predicates("select * from orders where substring(code from ? for ?) = ?"
                + " and overlay(name placing ? from ? for ?) = ?"))
                .containsExactly("orders.code", "orders.name");

        String selected = "select extract(month from o.created_at), o.id from orders o where o.status = ?";
        assertThat(tables(selected)).containsExactly("orders");
        assertThat(predicates(selected)).containsExactly("orders.status");

        assertThat(predicates("select * from orders where timestampdiff(day, created_at, now()) > ?"
                + " and dateadd(hour, ?, paid_at) < ?"))
                .containsExactly("orders.created_at", "orders.paid_at");
    }

    /** PostgreSQL's array constructor and a subscript are no bracket-quoted names. */
    @Test
    void anArrayConstructorIsNoColumn() {
        String any = "select * from orders o where o.id = any(array[?, ?])";
        assertThat(SqlShape.of(any).readable()).isTrue();
        assertThat(predicates(any)).containsExactly("orders.id");
        assertThat(predicates("select * from orders o where o.tags && array[?] and o.status = ?"))
                .containsExactly("orders.tags", "orders.status");
        assertThat(predicates("select * from orders where tags[1] = ? and codes && ?::text[]"))
                .containsExactly("orders.tags", "orders.codes");
        assertThat(predicates("select * from [Order Details] where [Unit Price] > ?"))
                .containsExactly("order details.unit price");
    }

    @Test
    void aMultiWordCastTypeIsNotAColumn() {
        assertThat(predicates("select * from items where created > cast(? as timestamp with time zone)"
                + " and name = ?"))
                .containsExactly("items.created", "items.name");
        assertThat(predicates("select * from items where price > cast(? as double precision)"))
                .containsExactly("items.price");
        assertThat(predicates("select * from items where cast(code as varchar(10)) = ? and id = ?"))
                .containsExactly("items.code", "items.id");
    }

    @Test
    void theTypeOfADoubleColonCastIsNotAColumn() {
        assertThat(predicates("select * from items where id = ?::uuid and name = ?"))
                .containsExactly("items.id", "items.name");
        assertThat(predicates("select * from items where created_at::date = ?"
                + " and price > ?::numeric(10, 2) and tags && ?::text[]"
                + " and updated_at < ?::timestamp with time zone order by name::text"))
                .containsExactly("items.created_at", "items.price", "items.tags", "items.updated_at",
                        "items.name");
    }

    @Test
    void anUpsertsConflictTargetIsNeitherAPredicateNorAColumnNamedConflict() {
        String postgres = "insert into items (id, name) values (?, ?)"
                + " on conflict (id) do update set name = excluded.name";
        assertThat(SqlShape.of(postgres).readable()).isTrue();
        assertThat(tables(postgres)).containsExactly("items");
        assertThat(predicates(postgres)).isEmpty();

        String constraint = "insert into items (id, name) values (?, ?)"
                + " on conflict on constraint items_pkey do nothing";
        assertThat(SqlShape.of(constraint).readable()).isTrue();
        assertThat(predicates(constraint)).isEmpty();

        String guarded = "insert into items (id, stock) values (?, ?)"
                + " on conflict (id) do update set stock = ? where items.stock < ?";
        assertThat(predicates(guarded)).containsExactly("items.stock");

        String mysql = "insert into items (id, stock) values (?, ?)"
                + " on duplicate key update stock = stock + values(stock)";
        assertThat(SqlShape.of(mysql).readable()).isTrue();
        assertThat(tables(mysql)).containsExactly("items");
        assertThat(predicates(mysql)).isEmpty();
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
