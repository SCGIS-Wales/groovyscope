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

    /**
     * Safe fixes for the given documents (all when empty) and codes (all fixable when empty), as edits. Nothing
     * is written. Params: {@code uris}, {@code codes}, {@code root}.
     */
    @JsonRequest("groovyscope/fix")
    public CompletableFuture<Map<String, Object>> fix(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            org.groovyscope.engine.services.QualityServices.FixResult result = current.quality().fix(
                    stringList(params.get("uris")), new java.util.LinkedHashSet<>(stringList(params.get("codes"))));
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (Map.Entry<String, List<org.groovyscope.engine.model.TextEdit>> entry : result.edits().entrySet()) {
                List<org.eclipse.lsp4j.TextEdit> list = new java.util.ArrayList<>();
                for (org.groovyscope.engine.model.TextEdit edit : entry.getValue()) {
                    list.add(Converters.toLsp(edit));
                }
                edits.put(entry.getKey(), list);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("edits", edits);
            response.put("applied", result.applied());
            response.put("rejected", result.rejected());
            return response;
        });
    }

    /** Contract items of every profile-governed document, for {@code groovyscope baseline save}. Params: {@code root}. */
    @JsonRequest("groovyscope/contracts")
    public CompletableFuture<Map<String, Object>> contracts(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("files", requireServices(params).quality().contracts());
            return response;
        });
    }

    /** The runtime profile's view of one document. Params: {@code uri}, {@code root}. */
    @JsonRequest("groovyscope/profile")
    public CompletableFuture<Map<String, Object>> profile(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("profile needs a uri");
            }
            return current.quality().profileStatus(params.get("uri").toString());
        });
    }

    /** What a diagnostic code means and how to fix it, or {@code null}. Params: {@code code}. */
    @JsonRequest("groovyscope/explain")
    public CompletableFuture<Map<String, Object>> explain(Map<String, Object> params) {
        return CompletableFuture.completedFuture(org.groovyscope.engine.practices.Explanations.explain(
                params == null || params.get("code") == null ? null : params.get("code").toString()).orElse(null));
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
    /**
     * Impact of changing a declaration. Params: {@code symbol} (qualified name) or {@code uri} + {@code line}
     * + {@code character} (zero-based), {@code change} (signature|rename|delete|behaviour), {@code root}.
     */
    @JsonRequest("groovyscope/impact")
    public CompletableFuture<List<Map<String, Object>>> impact(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            org.groovyscope.engine.model.ImpactReport.Change change;
            try {
                change = org.groovyscope.engine.model.ImpactReport.Change.parse(
                        params.get("change") == null ? null : params.get("change").toString());
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            List<org.groovyscope.engine.model.ImpactReport> reports;
            if (params.get("symbol") != null && !params.get("symbol").toString().isBlank()) {
                reports = current.impactOf(params.get("symbol").toString(), change);
            } else if (params.get("uri") != null) {
                reports = current.impact(params.get("uri").toString(), positionOf(params), change)
                        .map(List::of).orElse(List.of());
            } else {
                throw invalid("impact needs a symbol or a uri with line and character");
            }
            List<Map<String, Object>> converted = new java.util.ArrayList<>();
            for (org.groovyscope.engine.model.ImpactReport report : reports) {
                converted.add(ReportJson.impact(report));
            }
            return converted;
        });
    }

    /**
     * Call graph around a declaration. Params: {@code symbol} or {@code uri} + {@code line} + {@code character},
     * {@code direction} (in|out), {@code depth} (default 2), {@code root}.
     */
    @JsonRequest("groovyscope/callGraph")
    public CompletableFuture<Map<String, Object>> callGraph(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            boolean outgoing = "out".equalsIgnoreCase(String.valueOf(params.getOrDefault("direction", "in")));
            int depth = params.get("depth") instanceof Number number ? Math.max(1, Math.min(6, number.intValue())) : 2;
            List<org.groovyscope.engine.model.Symbol> roots = new java.util.ArrayList<>();
            if (params.get("symbol") != null && !params.get("symbol").toString().isBlank()) {
                for (org.codehaus.groovy.ast.ASTNode node : current.findDeclarations(current.snapshot(),
                        params.get("symbol").toString())) {
                    roots.add(current.snapshot().symbols().of(node));
                }
            } else if (params.get("uri") != null) {
                current.callHierarchyItem(params.get("uri").toString(), positionOf(params)).ifPresent(roots::add);
            } else {
                throw invalid("callGraph needs a symbol or a uri with line and character");
            }
            return ReportJson.callGraph(current, roots, outgoing, depth);
        });
    }

    /** Declarations whose name contains {@code query}. Params: {@code query}, {@code root}. */
    @JsonRequest("groovyscope/symbols")
    public CompletableFuture<Map<String, Object>> symbols(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            String query = params.get("query") == null ? "" : params.get("query").toString();
            List<Map<String, Object>> symbols = new java.util.ArrayList<>();
            for (org.groovyscope.engine.model.Symbol symbol : current.workspaceSymbols(query)) {
                symbols.add(ReportJson.symbol(symbol));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("symbols", symbols);
            response.put("count", symbols.size());
            return response;
        });
    }

    /** The declaration under a position with its confidence, or {@code null}. Params: {@code uri}, {@code line}, {@code character}, {@code root}. */
    @JsonRequest("groovyscope/definition")
    public CompletableFuture<Map<String, Object>> definition(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("definition needs a uri with line and character");
            }
            return current.definition(params.get("uri").toString(), positionOf(params))
                    .map(definition -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("symbol", ReportJson.symbol(definition.symbol()));
                        response.put("confidence", definition.confidence().name());
                        response.put("inWorkspace", definition.isInWorkspace());
                        return response;
                    })
                    .orElse(null);
        });
    }

    /** References to the symbol under a position, each with its confidence. */
    @JsonRequest("groovyscope/references")
    public CompletableFuture<Map<String, Object>> references(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("references needs a uri with line and character");
            }
            boolean includeDeclaration = Boolean.TRUE.equals(params.get("includeDeclaration"));
            List<Map<String, Object>> references = new java.util.ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (org.groovyscope.engine.model.Reference reference : current.references(params.get("uri").toString(),
                    positionOf(params), includeDeclaration)) {
                Map<String, Object> json = ReportJson.location(reference.location());
                json.put("confidence", reference.confidence().name());
                references.add(json);
                counts.merge(reference.confidence().name(), 1, Integer::sum);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("references", references);
            response.put("counts", counts);
            return response;
        });
    }

    /** Constructs that defeat static analysis. Params: {@code uris} (optional), {@code root}. */
    @JsonRequest("groovyscope/dynamicCode")
    public CompletableFuture<Map<String, Object>> dynamicCode(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            List<Map<String, Object>> findings = new java.util.ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (org.groovyscope.engine.semantics.DynamicCodeFinder.Finding finding : current.dynamicCode(stringList(params.get("uris")))) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("kind", finding.kind());
                json.put("symbol", finding.symbol());
                json.put("detail", finding.detail());
                json.put("location", ReportJson.location(finding.location()));
                findings.add(json);
                counts.merge(finding.kind(), 1, Integer::sum);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("findings", findings);
            response.put("counts", counts);
            return response;
        });
    }

    private static org.groovyscope.engine.model.Position positionOf(Map<String, Object> params) {
        int line = params.get("line") instanceof Number number ? number.intValue() : 0;
        int character = params.get("character") instanceof Number number ? number.intValue() : 0;
        return new org.groovyscope.engine.model.Position(line, character);
    }

    private static org.eclipse.lsp4j.jsonrpc.ResponseErrorException invalid(String message) {
        return new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams, message, null));
    }

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
