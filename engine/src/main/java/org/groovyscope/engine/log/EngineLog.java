/*
 * Copyright 2026 SCGIS-Wales and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.groovyscope.engine.log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The engine's log: level-aware, written to stderr (which a daemon redirects to its log file) and, when
 * {@code logging.file} is set, appended to that file too. stdout is the LSP channel and is never used.
 * Configured from the {@code logging} section of the configuration (debug | info | warning | error).
 *
 * <p>The file must sit under the cache directory or the system temporary directory (anywhere else, such as a shell
 * start-up file a hostile repository names, is refused), is never opened through a symbolic link, is created
 * readable by its owner only, stays open between records and is rotated past {@code daemon.logMaxBytes} (one
 * previous file kept). Control characters in a record (a file name with a newline in it) are escaped, so each record
 * is one line.
 */
public final class EngineLog {

    /** Severity, lowest first. */
    public enum Level { DEBUG, INFO, WARNING, ERROR }

    public static final long DEFAULT_MAX_BYTES = 5_000_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Level threshold = Level.WARNING;
    private static volatile PrintStream stderr = System.err;
    private static Path file;
    private static OutputStream stream;
    private static long size;
    private static long maxBytes = DEFAULT_MAX_BYTES;

    private EngineLog() {
    }

    /** Sets the threshold ({@code null} or unknown keeps warnings) and the optional log file. */
    public static void configure(String level, String logFile) {
        configure(level, logFile, null, DEFAULT_MAX_BYTES);
    }

    /**
     * The same, with the daemon's cache directory (where a log file may live, besides the default cache and the
     * system temporary directory) and the size past which the file is rotated.
     */
    public static synchronized void configure(String level, String logFile, Path cacheDir, long rotateAt) {
        threshold = parse(level);
        closeFile();
        maxBytes = rotateAt > 0 ? rotateAt : DEFAULT_MAX_BYTES;
        if (logFile == null || logFile.isBlank()) {
            return;
        }
        Path candidate = Path.of(logFile.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath()
                .normalize();
        if (!allowed(candidate, allowedBases(cacheDir))) {
            stderr.println("groovyscope-engine: logging.file " + escape(logFile) + " is refused: a log file must be "
                    + "under the groovyscope cache or the temporary directory");
            return;
        }
        file = candidate;
    }

    /** Where a log file may be written: the given cache directory, the default cache and the temporary directory. */
    static List<Path> allowedBases(Path cacheDir) {
        List<Path> bases = new ArrayList<>();
        if (cacheDir != null) {
            bases.add(cacheDir);
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path home = Path.of(System.getProperty("user.home"));
        bases.add(xdg != null && !xdg.isBlank() ? Path.of(xdg, "groovyscope") : home.resolve(".cache/groovyscope"));
        bases.add(home.resolve("Library/Caches/groovyscope"));
        bases.add(Path.of(System.getProperty("java.io.tmpdir")));
        return bases;
    }

    /** True when {@code candidate} is inside one of {@code bases}, also once symbolic links are resolved. */
    static boolean allowed(Path candidate, List<Path> bases) {
        for (Path base : bases) {
            Path normal = base.toAbsolutePath().normalize();
            if (!candidate.startsWith(normal)) {
                continue;
            }
            try {
                Path real = normal.toRealPath();
                Path parent = candidate.getParent();
                // The closest existing ancestor, resolved, must stay inside the base resolved.
                while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                    parent = parent.getParent();
                }
                if (parent != null && parent.toRealPath().startsWith(real)) {
                    return true;
                }
            } catch (IOException e) {
                // a base that does not exist yet holds nothing; try the next
            }
        }
        return false;
    }

    /** For tests: where stderr output goes. */
    public static void redirect(PrintStream stream) {
        stderr = stream;
    }

    public static Level parse(String level) {
        if (level == null) {
            return Level.WARNING;
        }
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "debug" -> Level.DEBUG;
            case "info" -> Level.INFO;
            case "error" -> Level.ERROR;
            default -> Level.WARNING;
        };
    }

    public static boolean enabled(Level level) {
        return level.compareTo(threshold) >= 0;
    }

    public static Level threshold() {
        return threshold;
    }

    public static void debug(String message, Object... arguments) {
        log(Level.DEBUG, null, message, arguments);
    }

    public static void info(String message, Object... arguments) {
        log(Level.INFO, null, message, arguments);
    }

    public static void warn(String message, Object... arguments) {
        log(Level.WARNING, null, message, arguments);
    }

    public static void error(Throwable failure, String message, Object... arguments) {
        log(Level.ERROR, failure, message, arguments);
    }

    /** Logs at {@code level}; {@code message} uses {@link String#format} placeholders. */
    public static void log(Level level, Throwable failure, String message, Object... arguments) {
        if (!enabled(level)) {
            return;
        }
        String text = arguments.length == 0 ? message : String.format(Locale.ROOT, message, arguments);
        StringBuilder line = new StringBuilder()
                .append(LocalDateTime.now().format(TIME)).append(' ').append(level).append(" [")
                .append(Thread.currentThread().getName()).append("] groovyscope-engine: ").append(text);
        if (failure != null) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            line.append('\n').append(trace.toString().stripTrailing());
        }
        String rendered = escape(line.toString());
        synchronized (EngineLog.class) {
            stderr.println(rendered);
            if (file != null) {
                write(rendered + System.lineSeparator());
            }
        }
    }

    /** Control characters (CR, LF, the rest of C0 and DEL) as escapes; a tab stays. */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || (c >= 0x20 && c != 0x7f)) {
                out.append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else {
                out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
            }
        }
        return out.toString();
    }

    private static void write(String record) {
        byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
        try {
            if (stream != null && size + bytes.length > maxBytes) {
                rotate();
            }
            if (stream == null) {
                open();
            }
            stream.write(bytes);
            stream.flush();
            size += bytes.length;
        } catch (IOException | RuntimeException e) {
            stderr.println("groovyscope-engine: cannot write the log file " + file + ": " + escape(String.valueOf(
                    e.getMessage())));
            closeFile();
            file = null;
        }
    }

    private static void open() throws IOException {
        Files.createDirectories(file.getParent());
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        FileChannel channel = FileChannel.open(file, options, attributes);
        size = channel.size();
        stream = Channels.newOutputStream(channel);
    }

    private static void rotate() throws IOException {
        closeFile();
        Path previous = file.resolveSibling(file.getFileName() + ".1");
        Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    private static void closeFile() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // the next open reports a lasting problem
            }
        }
        stream = null;
        size = 0;
    }

    /** For tests: the log file in use, or null. */
    static synchronized Path file() {
        return file;
    }
}
