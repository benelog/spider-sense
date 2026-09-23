package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.source.SourceRoots;

/** The suspect-change line under a code frame, against a repository made for the test. */
class SuspectChangeTest {

    /** 2026-09-20T00:00:00Z, the commit's author date. */
    private static final long COMMITTED = 1_789_862_400L;

    @TempDir
    Path repo;

    private static boolean git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString(),
                "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void write(String relative, String text) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static final String FINDINGS = """
            # findings  2026-09-23T10:00:00+09:00 → 10:15:00  (15m, all services, 3 requests)

            1. error:1a2b3c4d5e6f — 3 errors in 3 requests
               count 3
               orders.OrderService.load(OrderService.java:2)
               orders.Draft.save(Draft.java:1)
               orders.Staged.run(Staged.java:1)
               orders.Missing.run(Missing.java:1)
               traces: 4bf92f3577b34da6a3ce929d0e0e4736
            """;

    @Test
    void committedLinesNameTheirCommitAndUncommittedFilesSaySo() throws IOException {
        assumeTrue(git(repo, "init", "-q"), "git is not available");
        write("src/main/java/orders/OrderService.java", "class OrderService {\n  void load() {}\n}\n");
        write("src/main/java/orders/Staged.java", "class Staged {}\n");
        assertThat(git(repo, "add", "src/main/java/orders/OrderService.java")).isTrue();
        String date = "@" + COMMITTED + " +0000";
        assertThat(git(repo, "-c", "core.hooksPath=/dev/null", "commit", "-q",
                "--date=" + date, "-m", "Load orders by id")).isTrue();
        assertThat(git(repo, "add", "src/main/java/orders/Staged.java")).isTrue();
        write("src/main/java/orders/Draft.java", "class Draft {}\n");

        long now = (COMMITTED + 2 * 60 * 60) * 1000;
        SuspectChange suspects = SuspectChange.in(repo, SourceRoots.of(null, repo), now);

        assertThat(suspects).isNotNull();
        String annotated = suspects.annotate(FINDINGS);
        assertThat(annotated).matches("(?s).*"
                + "   orders\\.OrderService\\.load\\(OrderService\\.java:2\\)\n"
                + "     changed in [0-9a-f]{7} \\(2 hours ago\\): Load orders by id\n"
                + "   orders\\.Draft\\.save\\(Draft\\.java:1\\)\n"
                + "     uncommitted\n"
                + "   orders\\.Staged\\.run\\(Staged\\.java:1\\)\n"
                + "     uncommitted\n"
                + "   orders\\.Missing\\.run\\(Missing\\.java:1\\)\n"
                + "   traces: .*");
    }

    @Test
    void aModifiedLineIsUncommittedEvenWhenTheFileWasCommitted() throws IOException {
        assumeTrue(git(repo, "init", "-q"), "git is not available");
        write("src/main/java/orders/OrderService.java", "class OrderService {\n  void load() {}\n}\n");
        assertThat(git(repo, "add", ".")).isTrue();
        assertThat(git(repo, "commit", "-q", "-m", "First")).isTrue();
        write("src/main/java/orders/OrderService.java", "class OrderService {\n  void load(int id) {}\n}\n");

        SuspectChange suspects = SuspectChange.in(repo, SourceRoots.of(null, repo),
                System.currentTimeMillis());

        assertThat(suspects.noteFor("orders.OrderService.load(OrderService.java:2)"))
                .isEqualTo("uncommitted");
    }

    @Test
    void outsideARepositoryThereIsNothingToSay() {
        assertThat(SuspectChange.in(repo, SourceRoots.of(null, repo), 0L)).isNull();
    }

    @Test
    void theAgeIsTheLargestWholeUnit() {
        assertThat(SuspectChange.age(5_000)).isEqualTo("5 seconds ago");
        assertThat(SuspectChange.age(60_000)).isEqualTo("1 minute ago");
        assertThat(SuspectChange.age(2 * 60 * 60 * 1000L)).isEqualTo("2 hours ago");
        assertThat(SuspectChange.age(3 * 24 * 60 * 60 * 1000L)).isEqualTo("3 days ago");
        assertThat(SuspectChange.age(21 * 24 * 60 * 60 * 1000L)).isEqualTo("3 weeks ago");
        assertThat(SuspectChange.age(90 * 24 * 60 * 60 * 1000L)).isEqualTo("3 months ago");
        assertThat(SuspectChange.age(800 * 24 * 60 * 60 * 1000L)).isEqualTo("2 years ago");
    }
}
