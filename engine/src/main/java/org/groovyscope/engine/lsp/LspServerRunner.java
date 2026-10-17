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
package org.groovyscope.engine.lsp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.services.LanguageServices;

/** Wires a {@link GroovyscopeLanguageServer} to a pair of streams and blocks until the client goes away. */
public final class LspServerRunner {

    private LspServerRunner() {
    }

    /**
     * Serves LSP on the given streams. Returns when the input stream closes.
     *
     * @return the server, so callers can inspect its state after the session ends
     */
    public static GroovyscopeLanguageServer serve(InputStream in, OutputStream out, Path fallbackRoot,
            IntConsumer exitHandler, EngineConfig config) throws InterruptedException {
        return serve(in, out, fallbackRoot, exitHandler, config, null);
    }

    /** As above, with a provider of (possibly shared or cached) services per workspace root. */
    public static GroovyscopeLanguageServer serve(InputStream in, OutputStream out, Path fallbackRoot,
            IntConsumer exitHandler, EngineConfig config, Function<Path, LanguageServices> provider)
            throws InterruptedException {
        GroovyscopeLanguageServer server = provider == null
                ? new GroovyscopeLanguageServer(exitHandler, fallbackRoot, config)
                : new GroovyscopeLanguageServer(exitHandler, fallbackRoot, config, provider, null);
        Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>().setLocalService(server)
                .setRemoteInterface(LanguageClient.class).setInput(in).setOutput(out)
                .wrapMessages(MessageLogging.wrapper()).create();
        server.connect(launcher.getRemoteProxy());
        try {
            launcher.startListening().get();
        } catch (ExecutionException e) {
            // The stream closed or the client misbehaved; the server state tells the caller what happened.
            org.groovyscope.engine.log.EngineLog.warn("%s", "LSP session ended: " + e.getCause());
        } finally {
            server.connectionClosed();
        }
        return server;
    }
}
