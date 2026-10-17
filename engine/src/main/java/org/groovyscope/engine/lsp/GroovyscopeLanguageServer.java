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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.version.EngineVersion;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The LSP front end. {@code initialize} picks the workspace root and builds the {@link LanguageServices};
 * requests are delegated to the document and workspace services. The engine API behind this package stays
 * free of LSP4J types.
 */
public final class GroovyscopeLanguageServer implements LanguageServer, LanguageClientAware {

    /** Server name reported to clients in {@code ServerInfo}. */
    public static final String SERVER_NAME = "groovyscope-engine";

    private final IntConsumer exitHandler;
    private final Path fallbackRoot;
    private final EngineConfig config;
    private final Function<Path, LanguageServices> servicesProvider;
    private final Runnable daemonShutdown;
    private final AtomicReference<Path> workspaceRoot = new AtomicReference<>();
    private final AtomicReference<LanguageClient> client = new AtomicReference<>();
    private final AtomicReference<LanguageServices> services = new AtomicReference<>();
    private final DiagnosticsPublisher diagnostics;
    private final GroovyscopeTextDocumentService textDocumentService;
    private final GroovyscopeWorkspaceService workspaceService;
    private volatile boolean shutdownRequested;

    /**
     * @param exitHandler receives the process exit code when the client sends {@code exit}
     * @param fallbackRoot used when the client supplies neither workspace folders nor a root URI
     * @param config the engine configuration handed over by the front end
     */
    public GroovyscopeLanguageServer(IntConsumer exitHandler, Path fallbackRoot, EngineConfig config) {
        this(exitHandler, fallbackRoot, config,
                root -> new LanguageServices(new WorkspaceModel(root, config), new GroovyCompiler()), null);
    }

    /**
     * @param servicesProvider supplies (possibly shared) services for a workspace root
     * @param daemonShutdown what {@code groovyscope/shutdownDaemon} does; {@code null} outside the daemon
     */
    public GroovyscopeLanguageServer(IntConsumer exitHandler, Path fallbackRoot, EngineConfig config,
            Function<Path, LanguageServices> servicesProvider, Runnable daemonShutdown) {
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.fallbackRoot = Objects.requireNonNull(fallbackRoot, "fallbackRoot");
        this.config = Objects.requireNonNull(config, "config");
        this.servicesProvider = Objects.requireNonNull(servicesProvider, "servicesProvider");
        this.daemonShutdown = daemonShutdown;
        this.diagnostics = new DiagnosticsPublisher(services::get, client::get);
        this.textDocumentService = new GroovyscopeTextDocumentService(services::get, diagnostics);
        this.workspaceService = new GroovyscopeWorkspaceService(services::get, diagnostics);
    }

    @Override
    public void connect(LanguageClient languageClient) {
        client.set(languageClient);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        Path root = resolveRoot(params, fallbackRoot);
        workspaceRoot.set(root);
        services.set(servicesProvider.apply(root));
        ServerInfo info = new ServerInfo(SERVER_NAME, EngineVersion.current());
        return CompletableFuture.completedFuture(new InitializeResult(capabilities(), info));
    }

    static ServerCapabilities capabilities() {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setDefinitionProvider(Boolean.TRUE);
        capabilities.setTypeDefinitionProvider(Boolean.TRUE);
        capabilities.setReferencesProvider(Boolean.TRUE);
        capabilities.setHoverProvider(Boolean.TRUE);
        capabilities.setDocumentSymbolProvider(Boolean.TRUE);
        capabilities.setWorkspaceSymbolProvider(Boolean.TRUE);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(".")));
        capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
        capabilities.setRenameProvider(new RenameOptions(true));
        capabilities.setDocumentFormattingProvider(Boolean.TRUE);
        capabilities.setCodeActionProvider(new CodeActionOptions(List.of(CodeActionKind.QuickFix)));
        capabilities.setDiagnosticProvider(new DiagnosticRegistrationOptions(false, false));
        return capabilities;
    }

    @Override
    public void initialized(InitializedParams params) {
        diagnostics.schedule(0);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        diagnostics.shutdown();
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

    /** Daemon and engine state for the Python front end and {@code groovyscope doctor}. */
    @JsonRequest("groovyscope/status")
    public CompletableFuture<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("engine", EngineVersion.current());
        status.put("root", workspaceRoot.get() == null ? null : workspaceRoot.get().toString());
        status.put("daemon", daemonShutdown != null);
        LanguageServices current = services.get();
        if (current != null) {
            status.put("dirty", current.isDirty());
            status.put("files", current.workspace().discoverSourceFiles().size());
            status.put("trust", current.workspace().config().trust());
        }
        return CompletableFuture.completedFuture(status);
    }

    /**
     * Lint, compile and type-check diagnostics for the given documents (all when {@code uris} is empty).
     * Params: {@code uris} (list), {@code profile} (default|jenkins, optional), {@code include} (list).
     */
    @JsonRequest("groovyscope/check")
    public CompletableFuture<Map<String, Object>> check(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            List<String> uris = stringList(params.get("uris"));
            Object profile = params.get("profile");
            java.util.Set<String> include = new java.util.LinkedHashSet<>(stringList(params.get("include")));
            org.groovyscope.engine.model.CheckResult result = current.quality().check(uris,
                    profile == null ? null : profile.toString(), include);
            Map<String, List<org.eclipse.lsp4j.Diagnostic>> converted = new LinkedHashMap<>();
            for (Map.Entry<String, List<org.groovyscope.engine.model.Diagnostic>> entry : result.diagnostics().entrySet()) {
                List<org.eclipse.lsp4j.Diagnostic> list = new java.util.ArrayList<>();
                for (org.groovyscope.engine.model.Diagnostic diagnostic : entry.getValue()) {
                    list.add(Converters.toLsp(diagnostic));
                }
                converted.put(entry.getKey(), list);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("errors", result.count(org.groovyscope.engine.model.Diagnostic.Severity.ERROR));
            summary.put("warnings", result.count(org.groovyscope.engine.model.Diagnostic.Severity.WARNING));
            summary.put("information", result.count(org.groovyscope.engine.model.Diagnostic.Severity.INFORMATION));
            summary.put("files", converted.size());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("diagnostics", converted);
            response.put("summary", summary);
            return response;
        });
    }

    /** Formatting edits for the given documents (all workspace documents when {@code uris} is empty). */
    @JsonRequest("groovyscope/format")
    public CompletableFuture<Map<String, Object>> format(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            List<String> uris = stringList(params.get("uris"));
            if (uris.isEmpty()) {
                uris = new java.util.ArrayList<>(current.workspace().sources().keySet());
            }
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (String uri : uris) {
                List<org.eclipse.lsp4j.TextEdit> list = new java.util.ArrayList<>();
                try {
                    for (org.groovyscope.engine.model.TextEdit edit : current.quality().format(uri)) {
                        list.add(Converters.toLsp(edit));
                    }
                } catch (IllegalStateException e) {
                    throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                            new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest, e.getMessage(), null));
                }
                if (!list.isEmpty()) {
                    edits.put(uri, list);
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("edits", edits);
            response.put("changed", edits.size());
            response.put("checked", uris.size());
            return response;
        });
    }

    /**
     * The services of this connection, or, for a connection that skipped {@code initialize} (the Python
     * {@code check} and {@code format} commands), the services of the {@code root} named in the params.
     */
    private LanguageServices requireServices(Map<String, Object> params) {
        LanguageServices current = services.get();
        if (current == null && params != null && params.get("root") instanceof String root && !root.isBlank()) {
            Path path = Paths.get(root).toAbsolutePath().normalize();
            workspaceRoot.set(path);
            services.compareAndSet(null, servicesProvider.apply(path));
            current = services.get();
        }
        if (current == null) {
            throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ServerNotInitialized,
                    "groovyscope engine has not been initialized (send initialize, or pass root)", null));
        }
        return current;
    }

    static List<String> stringList(Object value) {
        List<String> list = new java.util.ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item != null) {
                    list.add(item.toString());
                }
            }
        }
        return list;
    }

    /** Asks a daemon to exit after this connection closes; ignored when not running as a daemon. */
    @JsonNotification("groovyscope/shutdownDaemon")
    public void shutdownDaemon() {
        if (daemonShutdown != null) {
            daemonShutdown.run();
        }
    }

    /** The workspace root chosen during {@code initialize}, or {@code null} before initialisation. */
    public Path workspaceRoot() {
        return workspaceRoot.get();
    }

    /** The engine services, or {@code null} before initialisation. */
    public LanguageServices services() {
        return services.get();
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
