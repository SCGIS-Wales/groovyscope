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
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The engine's log: level-aware, written to stderr (which a daemon redirects to its log file) and, when
 * {@code logging.file} is set, appended to that file too. stdout is the LSP channel and is never used.
 * Configured from the {@code logging} section of the configuration (debug | info | warning | error).
 */
public final class EngineLog {

    /** Severity, lowest first. */
    public enum Level { DEBUG, INFO, WARNING, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Level threshold = Level.WARNING;
    private static volatile Path file;
    private static volatile PrintStream stderr = System.err;

    private EngineLog() {
    }

    /** Sets the threshold ({@code null} or unknown keeps warnings) and the optional log file. */
    public static void configure(String level, String logFile) {
        threshold = parse(level);
        file = logFile == null || logFile.isBlank() ? null : Path.of(logFile.replaceFirst("^~", System.getProperty("user.home")));
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
            line.append(System.lineSeparator()).append(trace.toString().stripTrailing());
        }
        String rendered = line.toString();
        synchronized (EngineLog.class) {
            stderr.println(rendered);
            Path target = file;
            if (target != null) {
                try {
                    Files.createDirectories(target.toAbsolutePath().getParent());
                    Files.writeString(target, rendered + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException | RuntimeException e) {
                    stderr.println("groovyscope-engine: cannot write the log file " + target + ": " + e.getMessage());
                    file = null;
                }
            }
        }
    }
}
