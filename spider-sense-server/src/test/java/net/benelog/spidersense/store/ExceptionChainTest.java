package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A stack trace read as its chain of causes, and the error group id read from it. */
class ExceptionChainTest {

    private static final String WRAPPED = """
            org.springframework.dao.DataIntegrityViolationException: could not execute statement [insert into orders]
            \tat org.springframework.orm.jpa.EntityManagerFactoryUtils.convert(EntityManagerFactoryUtils.java:360)
            \tat app//orders.OrderService.place(OrderService.java:30)
            \tSuppressed: java.lang.IllegalStateException: close failed
            \t\tat orders.Pool.close(Pool.java:9)
            \t\tCaused by: java.io.IOException: broken pipe
            \t\t\tat orders.Pool.flush(Pool.java:12)
            Caused by: org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index violation
            line two of the message
            \tat org.h2.message.DbException.get(DbException.java:223)
            \tat orders.OrderRepository.save(OrderRepository.java:41) ~[main/:?]
            \t... 12 more""";

    @Test
    void theChainIsInnermostFirstAndSkipsSuppressedBlocks() {
        ExceptionChain chain = ExceptionChain.parse(WRAPPED);

        assertThat(chain.causes()).extracting(ExceptionChain.Cause::type).containsExactly(
                "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException",
                "org.springframework.dao.DataIntegrityViolationException");
        ExceptionChain.Cause root = chain.causes().get(0);
        assertThat(root.message()).isEqualTo("Unique index violation\nline two of the message");
        assertThat(root.frames()).containsExactly("org.h2.message.DbException.get(DbException.java:223)",
                "orders.OrderRepository.save(OrderRepository.java:41)");
        assertThat(root.more()).isEqualTo(12);
        assertThat(chain.causes().get(1).message()).isEqualTo("could not execute statement [insert into orders]");
        assertThat(chain.rootType()).isEqualTo("org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException");
        assertThat(chain.innermost(ExceptionChain::notFramework))
                .isEqualTo("orders.OrderRepository.save(OrderRepository.java:41)");
    }

    @Test
    void aTraceWithoutAHeaderIsOneSectionWithoutAType() {
        ExceptionChain chain = ExceptionChain.parse("\tat orders.A.run(A.java:1)\n\tat orders.B.run(B.java:2)\n");

        assertThat(chain.causes()).hasSize(1);
        assertThat(chain.rootType()).isNull();
        assertThat(chain.framesInnermostFirst()).containsExactly("orders.A.run(A.java:1)", "orders.B.run(B.java:2)");
        assertThat(ExceptionChain.parse(null).causes()).isEmpty();
        assertThat(ExceptionChain.parse("  ").causes()).isEmpty();
    }

    @Test
    void theGroupKeyIsTheRootCauseTypeAndTheInnermostApplicationFrame() {
        String other = WRAPPED.replace("could not execute statement [insert into orders]", "something else");
        assertThat(Ids.errorId("orders", "X", "a", WRAPPED)).isEqualTo(Ids.errorId("orders", "Y", "b", other));

        // A moved line and a renumbered lambda keep the group; another method does not.
        String moved = WRAPPED.replace("OrderRepository.java:41", "OrderRepository.java:47");
        assertThat(Ids.errorId("orders", "X", "a", moved)).isEqualTo(Ids.errorId("orders", "X", "a", WRAPPED));
        assertThat(Ids.errorId("orders", "X", "a", WRAPPED.replace(".save(", ".lambda$save$0(")))
                .isEqualTo(Ids.errorId("orders", "X", "a", WRAPPED.replace(".save(", ".lambda$save$3(")));
        assertThat(Ids.errorId("orders", "X", "a", WRAPPED.replace(".save(", ".delete(")))
                .isNotEqualTo(Ids.errorId("orders", "X", "a", WRAPPED));
        assertThat(Ids.errorId("billing", "X", "a", WRAPPED)).isNotEqualTo(Ids.errorId("orders", "X", "a", WRAPPED));
    }

    @Test
    void withoutAnApplicationFrameTheKeyIsTheOuterTypeAndTheNormalisedMessage() {
        String framework = "java.lang.IllegalStateException: Order 42 is shipped\n"
                + "\tat org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:1089)";

        assertThat(Ids.errorId("orders", "java.lang.IllegalStateException", "Order 42 is shipped", framework))
                .isEqualTo(Ids.errorId("orders", "java.lang.IllegalStateException", "Order ? is shipped"));
        assertThat(Ids.errorId("orders", "boom", "Order 42 is shipped", null))
                .isEqualTo(Ids.errorId("orders", "boom", "Order ? is shipped"));
    }
}
