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
import java.util.function.IntConsumer;
import org.groovyscope.engine.config.EngineConfig;
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
            EngineConfig config;
            try {
                config = configFrom(arguments.subList(2, arguments.size()), environment, cwd);
            } catch (IllegalArgumentException | IOException e) {
                err.println("groovyscope-engine: " + e.getMessage());
                return 2;
            }
            return serveLsp(in, out, err, cwd, exitHandler, config);
        }
        err.println("groovyscope-engine: unknown arguments: " + String.join(" ", arguments));
        err.print(USAGE);
        return 2;
    }

    static EngineConfig configFrom(List<String> options, Map<String, String> environment, Path cwd) throws IOException {
        List<String> remaining = new ArrayList<>(options);
        String json = environment == null ? null : environment.get(CONFIG_ENV);
        while (!remaining.isEmpty()) {
            String option = remaining.remove(0);
            if (option.equals("--config")) {
                if (remaining.isEmpty()) {
                    throw new IllegalArgumentException("--config needs a file path");
                }
                json = Files.readString(cwd.resolve(remaining.remove(0)), StandardCharsets.UTF_8);
            } else {
                throw new IllegalArgumentException("unknown option: " + option);
            }
        }
        return EngineConfig.fromJson(json);
    }

    private static int serveLsp(InputStream in, PrintStream out, PrintStream err, Path cwd,
            IntConsumer exitHandler, EngineConfig config) {
        // stdout is the protocol channel: anything that still prints to System.out must go to stderr.
        PrintStream previousOut = System.out;
        System.setOut(err);
        try {
            LspServerRunner.serve(in, out, cwd, exitHandler, config);
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
