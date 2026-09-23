package net.benelog.spidersense.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which file a code frame names, and that no frame reaches a file outside the roots. */
class SourceRootsTest {

    @TempDir
    Path project;

    private Path write(String relative, int lines) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            text.append("line ").append(i).append('\n');
        }
        Files.writeString(file, text);
        return file;
    }

    @Test
    void aFrameResolvesToItsPackageAndFileUnderTheDefaultRoots() throws IOException {
        Path file = write("src/main/java/orders/OrderService.java", 50);

        SourceRoots.Location location = SourceRoots.of(null, project)
                .resolve("orders.OrderService.load(OrderService.java:41)");

        assertThat(location).isNotNull();
        assertThat(location.file()).isEqualTo(file.toRealPath());
        assertThat(location.line()).isEqualTo(41);
    }

    @Test
    void theDefaultAlsoLooksInEveryImmediateSubdirectoryAndInKotlin() throws IOException {
        Path java = write("orders-core/src/main/java/orders/Repo.java", 5);
        Path kotlin = write("orders-web/src/main/kotlin/orders/web/Routes.kt", 5);

        SourceRoots roots = SourceRoots.of(null, project);

        assertThat(roots.resolve("orders.Repo.find(Repo.java:2)").file()).isEqualTo(java.toRealPath());
        assertThat(roots.resolve("orders.web.RoutesKt.get(Routes.kt:3)").file())
                .isEqualTo(kotlin.toRealPath());
    }

    @Test
    void theFirstRootThatHasTheFileWins() throws IOException {
        write("a/orders/Same.java", 3);
        Path second = write("b/orders/Same.java", 3);
        write("b/orders/Other.java", 3);

        SourceRoots roots = SourceRoots.of("a, b", project);

        assertThat(roots.resolve("orders.Same.run(Same.java:1)").file())
                .isEqualTo(project.resolve("a/orders/Same.java").toRealPath());
        assertThat(roots.resolve("orders.Other.run(Other.java:1)").file())
                .isEqualTo(second.resolveSibling("Other.java").toRealPath());
    }

    @Test
    void nestedClassesLambdasAndConstructorsNameTheOuterFile() throws IOException {
        write("src/main/java/orders/OrderService.java", 50);
        SourceRoots roots = SourceRoots.of(null, project);

        assertThat(roots.resolve("orders.OrderService$Loader.lambda$load$0(OrderService.java:12)"))
                .isNotNull();
        assertThat(roots.resolve("orders.OrderService.<init>(OrderService.java:3)")).isNotNull();
    }

    @Test
    void theSnippetIsTheFiveLinesAroundTheLineCutAtTheEdgesOfTheFile() throws IOException {
        write("src/main/java/orders/OrderService.java", 10);
        SourceRoots roots = SourceRoots.of(null, project);

        SourceRoots.Snippet middle = roots.read("orders.OrderService.load(OrderService.java:5)");
        assertThat(middle.start()).isEqualTo(3);
        assertThat(middle.lines()).containsExactly("line 3", "line 4", "line 5", "line 6", "line 7");

        SourceRoots.Snippet top = roots.read("orders.OrderService.load(OrderService.java:1)");
        assertThat(top.start()).isEqualTo(1);
        assertThat(top.lines()).containsExactly("line 1", "line 2", "line 3");

        SourceRoots.Snippet bottom = roots.read("orders.OrderService.load(OrderService.java:10)");
        assertThat(bottom.lines()).containsExactly("line 8", "line 9", "line 10");

        SourceRoots.Snippet past = roots.read("orders.OrderService.load(OrderService.java:99)");
        assertThat(past.lines()).isEmpty();
    }

    @Test
    void aFrameWithoutAFileOrALineDoesNotResolve() throws IOException {
        write("src/main/java/orders/OrderService.java", 5);
        SourceRoots roots = SourceRoots.of(null, project);

        assertThat(roots.resolve("orders.OrderService.load(Unknown Source)")).isNull();
        assertThat(roots.resolve("orders.OrderService.load(Native Method)")).isNull();
        assertThat(roots.resolve("orders.OrderService.load")).isNull();
        assertThat(roots.resolve("orders.Missing.load(Missing.java:4)")).isNull();
        assertThat(roots.resolve(null)).isNull();
    }

    @Test
    void noFrameReachesAFileOutsideTheRoots() throws IOException {
        write("secret.java", 3);
        write("src/main/java/orders/OrderService.java", 3);
        SourceRoots roots = SourceRoots.of(null, project);

        for (String frame : List.of(
                "orders.OrderService.load(../../../../secret.java:1)",
                "...secret.load(secret.java:1)",
                "a..b.C.m(secret.java:1)",
                "x.Y.m(/etc/passwd.java:1)",
                "x.Y.m(passwd:1)",
                "x.Y.m(..%2Fsecret.java:1)",
                "x.Y.m(secret.txt:1)")) {
            assertThat(roots.resolve(frame)).as(frame).isNull();
        }
        assertThat(SourceRoots.parse("orders.OrderService.load(../secret.java:1)")).isNull();
    }

    @Test
    void aSymbolicLinkOutOfARootIsNotFollowed() throws IOException {
        Path outside = write("outside/orders/Leak.java", 3);
        Path dir = project.resolve("src/main/java/orders");
        Files.createDirectories(dir);
        try {
            Files.createSymbolicLink(dir.resolve("Leak.java"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // A file system without links has nothing to guard here.
        }

        assertThat(SourceRoots.of(null, project).resolve("orders.Leak.m(Leak.java:1)")).isNull();
    }

    @Test
    void anEmptyValueNamesNoRootAndAMissingRootIsLeftOut() throws IOException {
        write("src/main/java/orders/OrderService.java", 3);

        assertThat(SourceRoots.of("", project).roots()).isEmpty();
        assertThat(SourceRoots.of("nowhere, src/main/java", project).roots())
                .containsExactly(project.resolve("src/main/java").toRealPath());
    }
}
