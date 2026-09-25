package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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
