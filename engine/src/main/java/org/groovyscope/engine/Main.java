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
package org.groovyscope.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.daemon.DaemonServer;
import org.groovyscope.engine.daemon.WorkspaceRegistry;
import org.groovyscope.engine.lsp.LspServerRunner;
import org.groovyscope.engine.version.EngineVersion;

/** Command line entry point of the engine JAR. */
public final class Main {

    /** Environment variable through which the Python front end hands over the effective configuration. */
    public static final String CONFIG_ENV = "GROOVYSCOPE_CONFIG_JSON";

    static final String USAGE = String.join(System.lineSeparator(),
            "usage: groovyscope-engine <command> [options]",
            "",
            "commands:",
            "  lsp --stdio [--config <file.json>]",
            "                 serve the Language Server Protocol on stdin/stdout; the configuration is read",
            "                 from --config or the " + CONFIG_ENV + " environment variable (JSON)",
            "  daemon --socket <path> [--idle-seconds <n>] [--cache-dir <dir>] [--config <file.json>]",
            "                 serve LSP to every client of a Unix domain socket; exit after <n> idle seconds",
            "  --cache-dir <dir>   (lsp and daemon) keep a per-workspace symbol cache under <dir>",
            "  --version      print the engine version",
            "  --help         print this message",
            "");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err, Paths.get(""), System::exit, System.getenv()));
    }

    /**
     * Runs the CLI without touching process-global state, so it can be exercised in tests.
     *
     * @return the exit code
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Path cwd,
            IntConsumer exitHandler, Map<String, String> environment) {
        List<String> arguments = Arrays.asList(args);
        if (arguments.isEmpty() || arguments.contains("--help") || arguments.contains("-h")) {
            out.print(USAGE);
            return 0;
        }
        if (arguments.equals(List.of("--version"))) {
            out.println(EngineVersion.current());
            return 0;
        }
        if (arguments.size() >= 2 && arguments.get(0).equals("lsp") && arguments.get(1).equals("--stdio")) {
            Options parsed;
            try {
                parsed = parseOptions(arguments.subList(2, arguments.size()), environment, cwd);
            } catch (IllegalArgumentException | IOException e) {
                err.println("groovyscope-engine: " + e.getMessage());
                return 2;
            }
            return serveLsp(in, out, err, cwd, exitHandler, parsed.config, parsed.cacheDir);
        }
        if (arguments.get(0).equals("daemon")) {
            return runDaemon(arguments.subList(1, arguments.size()), err, cwd, environment);
        }
        err.println("groovyscope-engine: unknown arguments: " + String.join(" ", arguments));
        err.print(USAGE);
        return 2;
    }

    static EngineConfig configFrom(List<String> options, Map<String, String> environment, Path cwd) throws IOException {
        return parseOptions(options, environment, cwd).config;
    }

    /** Parsed daemon/LSP options: configuration plus the daemon socket and idle timeout. */
    record Options(EngineConfig config, Path socket, long idleSeconds, Path cacheDir) {
    }

    static final long DEFAULT_IDLE_SECONDS = 3600;

    static Options parseOptions(List<String> options, Map<String, String> environment, Path cwd) throws IOException {
        List<String> remaining = new ArrayList<>(options);
        String json = environment == null ? null : environment.get(CONFIG_ENV);
        String configHash = EngineConfig.hashOf(json);
        Path socket = null;
        Path cacheDir = null;
        long idleSeconds = DEFAULT_IDLE_SECONDS;
        while (!remaining.isEmpty()) {
            String option = remaining.remove(0);
            switch (option) {
                case "--config" -> json = Files.readString(cwd.resolve(value(remaining, option)), StandardCharsets.UTF_8);
                case "--socket" -> socket = cwd.resolve(value(remaining, option));
                case "--cache-dir" -> cacheDir = cwd.resolve(value(remaining, option));
                case "--idle-seconds" -> {
                    try {
                        idleSeconds = Long.parseLong(value(remaining, option));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--idle-seconds needs a whole number", e);
                    }
                }
                default -> throw new IllegalArgumentException("unknown option: " + option);
            }
        }
        configureLogging(json);
        return new Options(EngineConfig.fromJson(json).withConfigHash(configHash), socket, idleSeconds, cacheDir);
    }

    /** Applies {@code logging.level} and {@code logging.file} from the configuration JSON to the engine log. */
    static void configureLogging(String json) {
        String level = null;
        String file = null;
        if (json != null && !json.isBlank()) {
            try {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
                com.google.gson.JsonObject logging = parsed.isJsonObject() && parsed.getAsJsonObject().has("logging")
                        && parsed.getAsJsonObject().get("logging").isJsonObject()
                        ? parsed.getAsJsonObject().getAsJsonObject("logging") : null;
                if (logging != null) {
                    level = logging.has("level") && logging.get("level").isJsonPrimitive()
                            ? logging.get("level").getAsString() : null;
                    file = logging.has("file") && logging.get("file").isJsonPrimitive()
                            ? logging.get("file").getAsString() : null;
                }
            } catch (com.google.gson.JsonParseException | IllegalStateException e) {
                // EngineConfig.fromJson reports a malformed configuration
            }
        }
        org.groovyscope.engine.log.EngineLog.configure(level, file);
        org.groovyscope.engine.log.EngineLog.info("engine %s, Java %s (%s) at %s, log level %s",
                EngineVersion.current(), System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("java.home"), org.groovyscope.engine.log.EngineLog.threshold());
    }

    private static String value(List<String> remaining, String option) {
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return remaining.remove(0);
    }

    private static int runDaemon(List<String> options, PrintStream err, Path cwd, Map<String, String> environment) {
        Options parsed;
        try {
            parsed = parseOptions(options, environment, cwd);
        } catch (IllegalArgumentException | IOException e) {
            err.println("groovyscope-engine: " + e.getMessage());
            return 2;
        }
        if (parsed.socket == null) {
            err.println("groovyscope-engine: daemon needs --socket <path>");
            return 2;
        }
        CountDownLatch exited = new CountDownLatch(1);
        try (DaemonServer server = new DaemonServer(parsed.socket, parsed.config,
                java.time.Duration.ofSeconds(parsed.idleSeconds), exited::countDown, parsed.cacheDir)) {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "groovyscope-daemon-cleanup"));
            exited.await();
            return 0;
        } catch (IOException e) {
            err.println("groovyscope-engine: cannot listen on " + parsed.socket + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static int serveLsp(InputStream in, PrintStream out, PrintStream err, Path cwd,
            IntConsumer exitHandler, EngineConfig config, Path cacheDir) {
        // stdout is the protocol channel: anything that still prints to System.out must go to stderr.
        PrintStream previousOut = System.out;
        System.setOut(err);
        try {
            LspServerRunner.serve(in, out, cwd, exitHandler, config, new WorkspaceRegistry(config, cacheDir));
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("groovyscope-engine: interrupted");
            return 130;
        } finally {
            System.setOut(previousOut);
        }
    }
}
