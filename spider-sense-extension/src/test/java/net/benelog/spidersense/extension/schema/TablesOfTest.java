package net.benelog.spidersense.extension.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scanner on its own, against the statement shapes the examples and Hibernate actually produce.
 *
 * <p>Every case here is one the catalog lookup depends on: a name it misses is a schema block that
 * never appears, and a name it invents is a catalog query that finds nothing.
 */
class TablesOfTest {

    @Test
    void theTableOfASelectWithAnAlias() {
        assertThat(tablesOf("select i1_0.id from items i1_0 where lower(i1_0.name) like ? escape ?"))
                .containsExactly("items");
    }

    @Test
    void everyTableOfAJoin() {
        assertThat(tablesOf("select l.id from order_line l"
                + " join product p on p.id = l.product_id"
                + " join orders o on o.id = l.order_id"))
                .containsExactly("order_line", "product", "orders");
    }

    @Test
    void theTableOfAnUpdate() {
        assertThat(tablesOf("update items set stock = ? where id = ?")).containsExactly("items");
    }

    @Test
    void theTableOfAnInsert() {
        assertThat(tablesOf("insert into movements (item_id, delta) values (?, ?)"))
                .containsExactly("movements");
    }

    @Test
    void theTableOfADelete() {
        assertThat(tablesOf("delete from reviews where book_id = ?")).containsExactly("reviews");
    }

    @Test
    void aQuotedNameIsUnquotedAndKeptAsWritten() {
        List<IndexCatalog.Word> refs = IndexCatalog.refsOf("select * from \"Items\" i");

        assertThat(refs).extracting(ref -> ref.text).containsExactly("Items");
        assertThat(refs.get(0).quoted).as("quoted, so it is never case-folded").isTrue();
    }

    @Test
    void aQualifierIsKept() {
        assertThat(tablesOf("select * from public.items")).containsExactly("public.items");
    }

    @Test
    void aSubqueryIsNotATableButWhatItSelectsFromIs() {
        assertThat(tablesOf("select * from (select id from items) x where x.id = ?"))
                .containsExactly("items");
    }

    @Test
    void aStatementWithoutATableNamesNone() {
        assertThat(tablesOf("select sleep(?)")).isEmpty();
    }

    @Test
    void aCommaSeparatedListIsEveryTableOfIt() {
        assertThat(tablesOf("select * from a, b where a.id = b.a_id")).containsExactly("a", "b");
    }

    @Test
    void aTableNamedTwiceIsNamedOnce() {
        assertThat(tablesOf("select * from items i join items j on j.parent_id = i.id"))
                .containsExactly("items");
    }

    @Test
    void anAliasWrittenWithAsIsSkipped() {
        assertThat(tablesOf("select * from items as i where i.id = ?")).containsExactly("items");
    }

    @Test
    void whatALiteralSaysIsNotRead() {
        assertThat(tablesOf("select * from items where name = 'from secrets'"))
                .containsExactly("items");
    }

    private static List<String> tablesOf(String sql) {
        return IndexCatalog.tablesOf(sql);
    }
}
