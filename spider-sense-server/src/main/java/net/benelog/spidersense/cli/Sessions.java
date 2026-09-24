package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * Where an exported session document goes and where it comes from, for both
 * modes of {@code export} and {@code import}.
 *
 * <p>One rule decides the encoding and it is the file name: a name ending in
 * {@code .gz} is gzipped, anything else is not (cli.adoc#export-import). A session of a busy
 * hour compresses to about a tenth, and an agent that types the name has said
 * everything it needs to.
 */
final class Sessions {

    private Sessions() {
    }

    static boolean gzipped(@Nullable String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    /**
     * Where {@code export} writes: the named file, gzipped when it says so, or
     * standard output when nothing was named.
     *
     * <p>Standard output is never closed — it is the process's — so a caller that
     * closes what this returns closes only a stream of its own.
     */
    static OutputStream out(@Nullable String name, PrintStream stdout) throws IOException {
        if (name == null) {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    stdout.write(b);
                }

                @Override
                public void write(byte[] bytes, int off, int len) {
                    stdout.write(bytes, off, len);
                }

                @Override
                public void flush() {
                    stdout.flush();
                }
            };
        }
        Path path = Path.of(name);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        OutputStream file = Files.newOutputStream(path);
        return gzipped(name) ? new GZIPOutputStream(file, 8192) : file;
    }

    /**
     * The file as it lies, for the HTTP path: a {@code .gz} travels gzipped, with
     * {@code Content-Encoding: gzip}, rather than being expanded here only for the
     * connection to compress it again.
     */
    static byte[] bytes(String name) {
        try {
            return Files.readAllBytes(existing(name));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + name, e);
        }
    }

    /** The document {@code import} was given, gunzipped when the name says so. */
    static String read(String name) {
        Path path = existing(name);
        try (InputStream in = gzipped(name)
                ? new GZIPInputStream(Files.newInputStream(path))
                : Files.newInputStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** The file, or the message that says which one is missing. */
    private static Path existing(String name) {
        Path path = Path.of(name);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("no such file: " + path);
        }
        return path;
    }
}
