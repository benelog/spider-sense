package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.SpanRecord;
import org.junit.jupiter.api.Test;

/** Which predicate columns an index serves (findings.adoc#schema), over a catalog built by hand. */
class SchemaBlockTest {

    private static Map<String, List<Catalog.Table>> catalog(Catalog.Index... indexes) {
        return Map.of("customers", List.of(new Catalog.Table("public", "customers", List.of(indexes))));
    }

    /** PostgreSQL's driver names an expression index's key part by the expression's text. */
    @Test
    void anExpressionIndexServesTheColumnItIsOver() {
        String statement = "select * from customers where lower(email) = ? and status = ?";
        SchemaBlock block = SchemaBlock.of(statement, catalog(
                new Catalog.Index("customers_pkey", true, List.of("id")),
                new Catalog.Index("idx_customer_email_lower", false, List.of("lower((email)::text)"))));

        assertThat(block).isNotNull();
        assertThat(block.predicates()).containsExactly("customers.email", "customers.status");
        assertThat(block.unindexed()).containsExactly("customers.status");
    }

    /**
     * The store keeps {@value SpanRecord#MAX_STATEMENT} characters of a statement; a cut inside
     * the where clause leaves a stub ({@code … and i}) that reads like a column.
     */
    @Test
    void aStatementTheStoreCutHasNoBlock() {
        String tail = " from customers c1_0 where c1_0.email=? and c1_0.status in (?,?)";
        int cutInTail = tail.indexOf(" and c1_0.status") + " and c".length();
        StringBuilder select = new StringBuilder("select c1_0.id");
        for (int n = 0; select.length() < SpanRecord.MAX_STATEMENT - cutInTail - 20; n++) {
            select.append(",c1_0.column_").append(n);
        }
        select.append(" ".repeat(SpanRecord.MAX_STATEMENT - cutInTail - select.length()));
        String statement = select + tail;
        String stored = statement.substring(0, SpanRecord.MAX_STATEMENT);
        Map<String, List<Catalog.Table>> catalog = catalog(new Catalog.Index("customers_pkey", true, List.of("id")));

        assertThat(stored).endsWith(" and c");
        assertThat(SqlShape.of(stored).predicates()).as("what the cut text would claim")
                .contains(new SqlShape.ColumnRef("customers", "c"));
        assertThat(SchemaBlock.of(stored, catalog)).isNull();
    }

    /** A schema the statement names is the table it reads; another schema's table of that name is not. */
    @Test
    void aTableOfAnotherSchemaIsNotTheStatementsTable() {
        Catalog.Table publicItems = new Catalog.Table("PUBLIC", "ITEMS", List.of(
                new Catalog.Index("PRIMARY_KEY_8", true, List.of("ID")),
                new Catalog.Index("IDX_ITEMS_NAME", false, List.of("NAME"))));
        String statement = "select * from archive.items where name = ? and sku = ?";

        assertThat(SchemaBlock.of(statement, Map.of("items", List.of(publicItems)))).isNull();
        assertThat(SchemaBlock.of("select * from public.items where name = ? and sku = ?",
                Map.of("items", List.of(publicItems))))
                .extracting(SchemaBlock::unindexed).isEqualTo(List.of("items.sku"));
        // A database that reports no schemas (MySQL's are catalogs) cannot say it is another one.
        Catalog.Table unplaced = new Catalog.Table(null, "items", publicItems.indexes());
        assertThat(SchemaBlock.of(statement, Map.of("items", List.of(unplaced))))
                .extracting(SchemaBlock::unindexed).isEqualTo(List.of("items.sku"));
    }

    /** One name in two schemas of one statement: a column named by the table cannot be told apart. */
    @Test
    void oneNameInTwoSchemasOfAStatementHasNoBlock() {
        Map<String, List<Catalog.Table>> catalog = Map.of("items", List.of(
                new Catalog.Table("S1", "ITEMS", List.of(new Catalog.Index("PK1", true, List.of("ID")))),
                new Catalog.Table("S2", "ITEMS", List.of(new Catalog.Index("IDX_SKU", false, List.of("SKU"))))));

        assertThat(SchemaBlock.of("select * from s1.items a, s2.items b where a.id = ? and b.sku = ?", catalog))
                .isNull();
        assertThat(SchemaBlock.of("select * from s1.items a where a.id = ?", catalog))
                .extracting(SchemaBlock::unindexed).isEqualTo(List.of());
    }

    @Test
    void anExpressionIndexLeadsWithTheColumnsItNamesAndNoOthers() {
        assertThat(SchemaBlock.leads("lower((email)::text)", "email")).isTrue();
        assertThat(SchemaBlock.leads("(lower(email))", "email")).isTrue();
        assertThat(SchemaBlock.leads("\"Email\"", "email")).as("a quoted column").isTrue();
        assertThat(SchemaBlock.leads("EMAIL", "email")).isTrue();
        assertThat(SchemaBlock.leads("lower((email)::text)", "lower")).as("the function").isFalse();
        assertThat(SchemaBlock.leads("lower((email)::text)", "text")).as("the type").isFalse();
        assertThat(SchemaBlock.leads("coalesce(name, 'email')", "email")).as("a literal").isFalse();
        assertThat(SchemaBlock.leads("email_domain", "email")).isFalse();
    }
}
