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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
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
 * requests are delegated to the document and workspace services, and the {@code groovyscope/*} requests to
 * {@link StatusRequests}, {@link QualityRequests}, {@link ExplainGraphRequests} and {@link SearchRequests}. The
 * engine API behind this package stays free of LSP4J types.
 */
public final class GroovyscopeLanguageServer implements LanguageServer, LanguageClientAware {

    /** Server name reported to clients in {@code ServerInfo}. */
    public static final String SERVER_NAME = "groovyscope-engine";

    private final IntConsumer exitHandler;
    private final Path fallbackRoot;
    private final Supplier<EngineConfig> liveConfig;
    private final Consumer<EngineConfig> reloader;
    private final Function<Path, LanguageServices> servicesProvider;
    private final Runnable daemonShutdown;
    private final AtomicReference<Path> workspaceRoot = new AtomicReference<>();
    private final AtomicReference<LanguageClient> client = new AtomicReference<>();
    private final AtomicReference<LanguageServices> services = new AtomicReference<>();
    private final DiagnosticsPublisher diagnostics;
    private final GroovyscopeTextDocumentService textDocumentService;
    private final GroovyscopeWorkspaceService workspaceService;
    private final RequestExecutors requests;
    private final StatusRequests status;
    private final QualityRequests quality;
    private final ExplainGraphRequests explainGraph;
    private final SearchRequests search;
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
        this(exitHandler, fallbackRoot, config, servicesProvider, daemonShutdown, null, null);
    }

    /**
     * @param liveConfig the configuration currently in force (shared with other connections in the daemon);
     *                   {@code null} keeps a private copy of {@code config}
     * @param reloader applies a replacement configuration everywhere ({@code groovyscope/reloadConfig});
     *                 {@code null} reconfigures this connection's services only
     */
    public GroovyscopeLanguageServer(IntConsumer exitHandler, Path fallbackRoot, EngineConfig config,
            Function<Path, LanguageServices> servicesProvider, Runnable daemonShutdown,
            Supplier<EngineConfig> liveConfig, Consumer<EngineConfig> reloader) {
        this(exitHandler, fallbackRoot, config, servicesProvider, daemonShutdown, liveConfig, reloader, null);
    }

    /**
     * @param executors the request lanes shared by every connection of the process; {@code null} creates a
     *                  private pair sized from {@code config}
     */
    public GroovyscopeLanguageServer(IntConsumer exitHandler, Path fallbackRoot, EngineConfig config,
            Function<Path, LanguageServices> servicesProvider, Runnable daemonShutdown,
            Supplier<EngineConfig> liveConfig, Consumer<EngineConfig> reloader, RequestExecutors executors) {
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.fallbackRoot = Objects.requireNonNull(fallbackRoot, "fallbackRoot");
        Objects.requireNonNull(config, "config");
        this.servicesProvider = Objects.requireNonNull(servicesProvider, "servicesProvider");
        this.daemonShutdown = daemonShutdown;
        if (liveConfig == null || reloader == null) {
            AtomicReference<EngineConfig> own = new AtomicReference<>(config);
            this.liveConfig = own::get;
            this.reloader = replacement -> {
                own.set(replacement);
                LanguageServices current = services.get();
                if (current != null) {
                    current.reconfigure(replacement);
                }
            };
        } else {
            this.liveConfig = liveConfig;
            this.reloader = reloader;
        }
        this.requests = (executors == null ? RequestExecutors.forConfig(config) : executors).forConnection();
        this.diagnostics = new DiagnosticsPublisher(services::get, client::get, java.util.Set::of, requests);
        this.textDocumentService = new GroovyscopeTextDocumentService(services::get, diagnostics, requests);
        this.diagnostics.openDocuments(textDocumentService::openedDocuments);
        this.workspaceService = new GroovyscopeWorkspaceService(services::get, diagnostics, requests);
        this.status = new StatusRequests(requests, services::get, workspaceRoot::get, daemonShutdown != null,
                this.liveConfig, this.reloader);
        this.quality = new QualityRequests(requests, this::requireServices);
        this.explainGraph = new ExplainGraphRequests(requests, this::requireServices);
        this.search = new SearchRequests(requests, this::requireServices);
    }

    /** The request lanes this server submits to (shared in the daemon). */
    public RequestExecutors requests() {
        return requests;
    }

    @Override
    public void connect(LanguageClient languageClient) {
        client.set(languageClient);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        Path root = resolveRoot(params, fallbackRoot);
        LanguageServices served = workspaceFor(root);
        workspaceRoot.set(root);
        services.set(served);
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
        capabilities.setDocumentRangeFormattingProvider(Boolean.TRUE);
        capabilities.setImplementationProvider(Boolean.TRUE);
        capabilities.setDocumentHighlightProvider(Boolean.TRUE);
        capabilities.setFoldingRangeProvider(Boolean.TRUE);
        capabilities.setInlayHintProvider(Boolean.TRUE);
        capabilities.setCodeActionProvider(new CodeActionOptions(List.of(CodeActionKind.QuickFix,
                GroovyscopeTextDocumentService.FIX_ALL_KIND, CodeActionKind.SourceOrganizeImports)));
        capabilities.setDiagnosticProvider(new DiagnosticRegistrationOptions(false, false));
        capabilities.setCallHierarchyProvider(Boolean.TRUE);
        capabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(
                new SemanticTokensLegend(org.groovyscope.engine.services.SemanticTokensSupport.TYPES,
                        org.groovyscope.engine.services.SemanticTokensSupport.MODIFIERS), Boolean.TRUE));
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
        return status.status();
    }

    /** Replaces the engine configuration in place (see {@link StatusRequests#reloadConfig}). */
    @JsonRequest("groovyscope/reloadConfig")
    public CompletableFuture<Map<String, Object>> reloadConfig(Map<String, Object> params) {
        return status.reloadConfig(params);
    }

    /** Lint, compile and type-check diagnostics (see {@link QualityRequests#check}). */
    @JsonRequest("groovyscope/check")
    public CompletableFuture<Map<String, Object>> check(Map<String, Object> params) {
        return quality.check(params);
    }

    /** Safe fixes as edits, nothing written (see {@link QualityRequests#fix}). */
    @JsonRequest("groovyscope/fix")
    public CompletableFuture<Map<String, Object>> fix(Map<String, Object> params) {
        return quality.fix(params);
    }

    /** {@code {uri: [[start, end], ...]}} (1-based, inclusive) from a request, or null when absent. */
    static Map<String, List<int[]>> lineRanges(Object value) {
        return RequestParams.lineRanges(value);
    }

    /** Contract items of every profile-governed document, for {@code groovyscope baseline save}. */
    @JsonRequest("groovyscope/contracts")
    public CompletableFuture<Map<String, Object>> contracts(Map<String, Object> params) {
        return quality.contracts(params);
    }

    /** The runtime profile's view of one document. */
    @JsonRequest("groovyscope/profile")
    public CompletableFuture<Map<String, Object>> profile(Map<String, Object> params) {
        return quality.profile(params);
    }

    /** Box positions and edge routes for a diagram (see {@link ExplainGraphRequests#layout}). */
    @JsonRequest("groovyscope/layout")
    public CompletableFuture<Map<String, Object>> layout(Map<String, Object> params) {
        return explainGraph.layout(params);
    }

    /** What a diagnostic code means and how to fix it, or {@code null}. Params: {@code code}. */
    @JsonRequest("groovyscope/explain")
    public CompletableFuture<Map<String, Object>> explain(Map<String, Object> params) {
        return explainGraph.explain(params);
    }

    /** Formatting edits (see {@link QualityRequests#format}). */
    @JsonRequest("groovyscope/format")
    public CompletableFuture<Map<String, Object>> format(Map<String, Object> params) {
        return quality.format(params);
    }

    /** Whether candidate texts of one file parse, with their declarations (see {@link QualityRequests#parse}). */
    @JsonRequest("groovyscope/parse")
    public CompletableFuture<Map<String, Object>> parse(Map<String, Object> params) {
        return quality.parse(params);
    }

    /** Impact of changing a declaration (see {@link ExplainGraphRequests#impact}). */
    @JsonRequest("groovyscope/impact")
    public CompletableFuture<List<Map<String, Object>>> impact(Map<String, Object> params) {
        return explainGraph.impact(params);
    }

    /** Call graph around a declaration (see {@link ExplainGraphRequests#callGraph}). */
    @JsonRequest("groovyscope/callGraph")
    public CompletableFuture<Map<String, Object>> callGraph(Map<String, Object> params) {
        return explainGraph.callGraph(params);
    }

    /** Declarations whose name contains {@code query}. */
    @JsonRequest("groovyscope/symbols")
    public CompletableFuture<Map<String, Object>> symbols(Map<String, Object> params) {
        return search.symbols(params);
    }

    /** The declaration under a position with its confidence, or {@code null}. */
    @JsonRequest("groovyscope/definition")
    public CompletableFuture<Map<String, Object>> definition(Map<String, Object> params) {
        return search.definition(params);
    }

    /** Hover text for a position, or null. */
    @JsonRequest("groovyscope/hover")
    public CompletableFuture<Map<String, Object>> hover(Map<String, Object> params) {
        return search.hover(params);
    }

    /** The declaration of the type of the expression under a position, or null. */
    @JsonRequest("groovyscope/typeDefinition")
    public CompletableFuture<Map<String, Object>> typeDefinition(Map<String, Object> params) {
        return search.typeDefinition(params);
    }

    /** The outline of one document: declarations with their children. */
    @JsonRequest("groovyscope/documentSymbols")
    public CompletableFuture<Map<String, Object>> documentSymbols(Map<String, Object> params) {
        return search.documentSymbols(params);
    }

    /** Rename with the same safety as the LSP rename (see {@link SearchRequests#rename}). */
    @JsonRequest("groovyscope/rename")
    public CompletableFuture<Map<String, Object>> rename(Map<String, Object> params) {
        return search.rename(params);
    }

    /** References to the symbol under a position, each with its confidence. */
    @JsonRequest("groovyscope/references")
    public CompletableFuture<Map<String, Object>> references(Map<String, Object> params) {
        return search.references(params);
    }

    /** Constructs that defeat static analysis. */
    @JsonRequest("groovyscope/dynamicCode")
    public CompletableFuture<Map<String, Object>> dynamicCode(Map<String, Object> params) {
        return search.dynamicCode(params);
    }

    /** The source of one declaration, found by name (see {@link SearchRequests#readSymbol}). */
    @JsonRequest("groovyscope/readSymbol")
    public CompletableFuture<Map<String, Object>> readSymbol(Map<String, Object> params) {
        return search.readSymbol(params);
    }

    /** Structural code search (see {@link SearchRequests#findCode}). */
    @JsonRequest("groovyscope/findCode")
    public CompletableFuture<Map<String, Object>> findCode(Map<String, Object> params) {
        return search.findCode(params);
    }

    /** Duplicated statement runs (see {@link SearchRequests#duplicates}). */
    @JsonRequest("groovyscope/duplicates")
    public CompletableFuture<Map<String, Object>> duplicates(Map<String, Object> params) {
        return search.duplicates(params);
    }

    /** Sizes of the workspace by package, file and class, test files and untested classes. */
    @JsonRequest("groovyscope/overview")
    public CompletableFuture<Map<String, Object>> overview(Map<String, Object> params) {
        return search.overview(params);
    }

    /** What a class, method or file does, as facts (see {@link ExplainGraphRequests#explainCode}). */
    @JsonRequest("groovyscope/explainCode")
    public CompletableFuture<Map<String, Object>> explainCode(Map<String, Object> params) {
        return explainGraph.explainCode(params);
    }

    /** The relationship graph (see {@link ExplainGraphRequests#graph}). */
    @JsonRequest("groovyscope/graph")
    public CompletableFuture<Map<String, Object>> graph(Map<String, Object> params) {
        return explainGraph.graph(params);
    }

    static final int DEFAULT_FIND_LIMIT = SearchRequests.DEFAULT_FIND_LIMIT;

    /** The workspace for {@code root}, or an invalid-params error when this daemon serves another workspace. */
    private LanguageServices workspaceFor(Path root) {
        try {
            return servicesProvider.apply(root);
        } catch (org.groovyscope.engine.daemon.WorkspaceRegistry.RootRefusedException e) {
            throw RequestParams.invalid(e.getMessage());
        }
    }

    /**
     * The services of this connection, or, for a connection that skipped {@code initialize} (the Python
     * {@code check} and {@code format} commands), the services of the {@code root} named in the params.
     */
    private LanguageServices requireServices(Map<String, Object> params) {
        LanguageServices current = services.get();
        if (current == null && params != null && params.get("root") instanceof String root && !root.isBlank()) {
            Path path;
            try {
                path = Paths.get(root).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException e) {
                throw RequestParams.invalid("root is not a valid path: " + root);
            }
            if (!java.nio.file.Files.isDirectory(path)) {
                throw RequestParams.invalid("root is not an existing directory: " + root);
            }
            LanguageServices served = workspaceFor(path);
            workspaceRoot.set(path);
            services.compareAndSet(null, served);
            current = services.get();
        }
        if (current == null) {
            throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ServerNotInitialized,
                    "groovyscope engine has not been initialized (send initialize, or pass root)", null));
        }
        current.syncWithDisk();
        return current;
    }

    /** The {@code uri} parameter as text, or null. */
    static String uriOf(Map<String, Object> params) {
        return RequestParams.uriOf(params);
    }

    /** Refuses a whole-workspace request while the heap is under pressure; cheap requests are unaffected. */
    static void requireHeadroom() {
        RequestParams.requireHeadroom();
    }

    /** The documents a request targets (see {@link RequestParams#targets}). */
    static List<String> targets(LanguageServices current, Map<String, Object> params) {
        return RequestParams.targets(current, params);
    }

    /** True when the request named documents but none of them is inside the workspace (so nothing is checked). */
    static boolean noneInside(Map<String, Object> params, List<String> targets) {
        return RequestParams.noneInside(params, targets);
    }

    /** Directories expanded to the known documents under them (see {@link RequestParams#expandDirectories}). */
    static List<String> expandDirectories(List<String> uris, java.util.Collection<String> known) {
        return RequestParams.expandDirectories(uris, known);
    }

    /** JSON-RPC traffic logging for launchers outside this package (the daemon). */
    public static java.util.function.Function<org.eclipse.lsp4j.jsonrpc.MessageConsumer,
            org.eclipse.lsp4j.jsonrpc.MessageConsumer> messageLogging() {
        return MessageLogging.wrapper();
    }

    static List<String> stringList(Object value) {
        return RequestParams.stringList(value);
    }

    /**
     * Releases what this connection holds once its stream has ended (normally or not): the documents it opened
     * are closed, so their unsaved text stops overriding the disk for other clients, its running requests are
     * cancelled and its diagnostics thread stops. Safe to call more than once.
     */
    public void connectionClosed() {
        // LSP4J leaves this connection's running requests open (probe 0.4): nobody would read their answers.
        requests.cancelAll();
        textDocumentService.closeAll();
        diagnostics.shutdown();
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

    /** True once this connection's diagnostics thread has stopped (after shutdown or the end of the connection). */
    public boolean diagnosticsStopped() {
        return diagnostics.isShutdown();
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
        return org.groovyscope.engine.util.Uris.toPath(uri);
    }
}
