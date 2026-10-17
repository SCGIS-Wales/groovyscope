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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.groovyscope.engine.version.EngineVersion;

/**
 * Minimal LSP front end. It completes the initialize / shutdown / exit lifecycle and records the
 * workspace root. Language features arrive in later stages; the engine API behind this class must stay
 * free of LSP4J types.
 */
public final class GroovyscopeLanguageServer implements LanguageServer, LanguageClientAware {

    /** Server name reported to clients in {@code ServerInfo}. */
    public static final String SERVER_NAME = "groovyscope-engine";

    private final IntConsumer exitHandler;
    private final Path fallbackRoot;
    private final AtomicReference<Path> workspaceRoot = new AtomicReference<>();
    private final AtomicReference<LanguageClient> client = new AtomicReference<>();
    private final NoOpTextDocumentService textDocumentService = new NoOpTextDocumentService();
    private final NoOpWorkspaceService workspaceService = new NoOpWorkspaceService();
    private volatile boolean shutdownRequested;

    /**
     * @param exitHandler receives the process exit code when the client sends {@code exit}
     * @param fallbackRoot used when the client supplies neither workspace folders nor a root URI
     */
    public GroovyscopeLanguageServer(IntConsumer exitHandler, Path fallbackRoot) {
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.fallbackRoot = Objects.requireNonNull(fallbackRoot, "fallbackRoot");
    }

    @Override
    public void connect(LanguageClient languageClient) {
        client.set(languageClient);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        workspaceRoot.set(resolveRoot(params, fallbackRoot));
        ServerInfo info = new ServerInfo(SERVER_NAME, EngineVersion.current());
        return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities(), info));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        exitHandler.accept(shutdownRequested ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    /** The workspace root chosen during {@code initialize}, or {@code null} before initialisation. */
    public Path workspaceRoot() {
        return workspaceRoot.get();
    }

    /** True once the client has asked the server to shut down. */
    public boolean shutdownRequested() {
        return shutdownRequested;
    }

    /** The connected client, or {@code null} when not connected. */
    LanguageClient client() {
        return client.get();
    }

    /**
     * Prefers the first workspace folder, then the (deprecated but still common) rootUri, then
     * rootPath, then the supplied fallback. Mirrors Kiro CLI, which may omit workspace folders.
     */
    @SuppressWarnings("deprecation")
    static Path resolveRoot(InitializeParams params, Path fallback) {
        if (params != null) {
            List<WorkspaceFolder> folders = params.getWorkspaceFolders();
            if (folders != null) {
                for (WorkspaceFolder folder : folders) {
                    Path path = toPath(folder.getUri());
                    if (path != null) {
                        return path;
                    }
                }
            }
            Path fromUri = toPath(params.getRootUri());
            if (fromUri != null) {
                return fromUri;
            }
            String rootPath = params.getRootPath();
            if (rootPath != null && !rootPath.isBlank()) {
                return Paths.get(rootPath).toAbsolutePath().normalize();
            }
        }
        return fallback.toAbsolutePath().normalize();
    }

    static Path toPath(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            return Paths.get(parsed).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
