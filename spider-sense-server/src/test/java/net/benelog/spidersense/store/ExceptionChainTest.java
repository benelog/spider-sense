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

    /**
     * A JDBC driver throws from frames of its own, as H2 does: two bugs in two methods are two
     * groups however the driver names its exception, and the driver's frames are no code.
     */
    @Test
    void aDriversOwnFramesAreNotTheApplicationsWhateverTheDriver() {
        String duplicateKey = """
                org.springframework.dao.DataIntegrityViolationException: could not execute statement
                \tat org.springframework.orm.jpa.EntityManagerFactoryUtils.convert(EntityManagerFactoryUtils.java:360)
                \tat shop.OrderService.place(OrderService.java:30)
                Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint
                \tat org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2725)
                \tat org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:2412)
                \tat org.postgresql.jdbc.PgStatement.executeInternal(PgStatement.java:517)
                \tat com.zaxxer.hikari.pool.ProxyPreparedStatement.executeUpdate(ProxyPreparedStatement.java:61)
                \tat shop.OrderRepository.insert(OrderRepository.java:41)
                \t... 12 more""";
        String badGrammar = duplicateKey.replace("shop.OrderRepository.insert(OrderRepository.java:41)",
                "shop.ReportRepository.revenue(ReportRepository.java:27)");

        assertThat(ExceptionChain.parse(duplicateKey).innermost(ExceptionChain::notFramework))
                .isEqualTo("shop.OrderRepository.insert(OrderRepository.java:41)");
        assertThat(Ids.errorId("shop", "X", "a", duplicateKey))
                .isNotEqualTo(Ids.errorId("shop", "X", "a", badGrammar));

        for (String frame : java.util.List.of(
                "com.mysql.cj.jdbc.exceptions.SQLError.createSQLException(SQLError.java:121)",
                "org.mariadb.jdbc.export.ExceptionFactory.createException(ExceptionFactory.java:300)",
                "oracle.jdbc.driver.T4CTTIoer11.processError(T4CTTIoer11.java:629)",
                "com.microsoft.sqlserver.jdbc.SQLServerException.makeFromDatabaseError(SQLServerException.java:265)",
                "org.sqlite.core.DB.newSQLException(DB.java:1179)",
                "org.hsqldb.jdbc.JDBCUtil.sqlException(Unknown Source)",
                "com.fasterxml.jackson.databind.exc.MismatchedInputException.from(MismatchedInputException.java:59)",
                "com.google.gson.Gson.fromJson(Gson.java:1227)",
                "kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)")) {
            assertThat(ExceptionChain.notFramework(frame)).as(frame).isFalse();
        }
    }

    /** A lazy load outside its session throws in the entity's generated proxy; the group is the caller's line. */
    @Test
    void aGeneratedClassIsNoApplicationFrameOfTheGroupKey() {
        String lazy = """
                org.hibernate.LazyInitializationException: could not initialize proxy [orders.domain.Product#7] - no Session
                \tat org.hibernate.proxy.AbstractLazyInitializer.initialize(AbstractLazyInitializer.java:165)
                \tat orders.domain.Product$HibernateProxy.getName(Unknown Source)
                \tat orders.service.OrderService.describe(OrderService.java:91)
                \tat orders.service.OrderService$$SpringCGLIB$$0.describe(<generated>)""";

        assertThat(ExceptionChain.parse(lazy).innermost(ExceptionChain::notFramework))
                .isEqualTo("orders.service.OrderService.describe(OrderService.java:91)");
        assertThat(ExceptionChain.notFramework("orders.domain.Product$HibernateProxy.getName(Unknown Source)"))
                .isFalse();
        assertThat(ExceptionChain.notFramework("orders.service.OrderService$$SpringCGLIB$$0.describe(<generated>)"))
                .isFalse();
        assertThat(ExceptionChain.notFramework("orders.Thing.run(<generated>)")).isFalse();
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

    /** The module or class loader in front goes, and so does a hidden class's address, but not its name. */
    @Test
    void aFrameKeepsTheNameOfAHiddenClassAndDropsItsAddress() {
        assertThat(ExceptionChain.frame("java.base/java.lang.Thread.run(Thread.java:1583)"))
                .isEqualTo("java.lang.Thread.run(Thread.java:1583)");
        assertThat(ExceptionChain.frame("app//orders.OrderService.place(OrderService.java:30)"))
                .isEqualTo("orders.OrderService.place(OrderService.java:30)");
        assertThat(ExceptionChain.frame("com.acme.Foo$$Lambda/0x000073e4a00a2000.run(Unknown Source)"))
                .isEqualTo("com.acme.Foo$$Lambda.run(Unknown Source)");
        assertThat(ExceptionChain.frame("com.acme.Foo$$Lambda$14/0x0000000800c02000.apply(Unknown Source)"))
                .as("JDK 17").isEqualTo("com.acme.Foo$$Lambda$14.apply(Unknown Source)");
        assertThat(ExceptionChain.frame(
                "java.base/java.lang.invoke.LambdaForm$DMH/0x0000000800c0c400.invokeVirtual(LambdaForm$DMH)"))
                .isEqualTo("java.lang.invoke.LambdaForm$DMH.invokeVirtual(LambdaForm$DMH)");
    }
}
