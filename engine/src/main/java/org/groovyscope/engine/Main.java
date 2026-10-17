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

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import org.groovyscope.engine.lsp.LspServerRunner;
import org.groovyscope.engine.version.EngineVersion;

/** Command line entry point of the engine JAR. */
public final class Main {

    static final String USAGE = String.join(System.lineSeparator(),
            "usage: groovyscope-engine <command> [options]",
            "",
            "commands:",
            "  lsp --stdio    serve the Language Server Protocol on stdin/stdout",
            "  --version      print the engine version",
            "  --help         print this message",
            "");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err, Paths.get(""), System::exit));
    }

    /**
     * Runs the CLI without touching process-global state, so it can be exercised in tests.
     *
     * @return the exit code
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Path cwd,
            IntConsumer exitHandler) {
        List<String> arguments = Arrays.asList(args);
        if (arguments.isEmpty() || arguments.contains("--help") || arguments.contains("-h")) {
            out.print(USAGE);
            return 0;
        }
        if (arguments.equals(List.of("--version"))) {
            out.println(EngineVersion.current());
            return 0;
        }
        if (arguments.equals(List.of("lsp", "--stdio"))) {
            return serveLsp(in, out, err, cwd, exitHandler);
        }
        err.println("groovyscope-engine: unknown arguments: " + String.join(" ", arguments));
        err.print(USAGE);
        return 2;
    }

    private static int serveLsp(InputStream in, PrintStream out, PrintStream err, Path cwd,
            IntConsumer exitHandler) {
        // stdout is the protocol channel: anything that still prints to System.out must go to stderr.
        PrintStream previousOut = System.out;
        System.setOut(err);
        try {
            LspServerRunner.serve(in, out, cwd, exitHandler);
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
