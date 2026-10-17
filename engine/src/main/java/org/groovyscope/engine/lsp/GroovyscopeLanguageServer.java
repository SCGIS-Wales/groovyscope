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
        status.put("configHash", config.configHash());
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
            List<String> uris = targets(current, params);
            Object profile = params.get("profile");
            java.util.Set<String> include = new java.util.LinkedHashSet<>(stringList(params.get("include")));
            org.groovyscope.engine.model.CheckResult result = noneInside(params, uris)
                    ? new org.groovyscope.engine.model.CheckResult(Map.of())
                    : current.quality().check(uris, profile == null ? null : profile.toString(), include);
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
            List<String> uris = targets(current, params);
            org.groovyscope.engine.services.QualityServices.FixResult result = noneInside(params, uris)
                    ? new org.groovyscope.engine.services.QualityServices.FixResult(Map.of(), Map.of(), Map.of())
                    : current.quality().fix(uris, new java.util.LinkedHashSet<>(stringList(params.get("codes"))));
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
            if (outside(current, params.get("uri"))) {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("governed", false);
                return status;
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
            List<String> uris = targets(current, params);
            if (uris.isEmpty() && !noneInside(params, uris)) {
                uris = new java.util.ArrayList<>(current.workspace().sources().keySet());
                uris.removeIf(uri -> !current.workspace().contains(uri));
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
                reports = outside(current, params.get("uri")) ? List.of()
                        : current.impact(params.get("uri").toString(), positionOf(params), change)
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
                if (!outside(current, params.get("uri"))) {
                    current.callHierarchyItem(params.get("uri").toString(), positionOf(params)).ifPresent(roots::add);
                }
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
            if (outside(current, params.get("uri"))) {
                return null;
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

    /** Hover text for a position (types, documentation, runtime-profile parameters, finding notes), or null. */
    @JsonRequest("groovyscope/hover")
    public CompletableFuture<Map<String, Object>> hover(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("hover needs a uri with line and character");
            }
            if (outside(current, params.get("uri"))) {
                return null;
            }
            return GroovyscopeTextDocumentService.fullHover(current, params.get("uri").toString(), positionOf(params))
                    .map(found -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("markdown", found.markdown());
                        return response;
                    })
                    .orElse(null);
        });
    }

    /** The declaration of the type of the expression under a position, or null. */
    @JsonRequest("groovyscope/typeDefinition")
    public CompletableFuture<Map<String, Object>> typeDefinition(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("typeDefinition needs a uri with line and character");
            }
            if (outside(current, params.get("uri"))) {
                return null;
            }
            return current.typeDefinition(params.get("uri").toString(), positionOf(params))
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

    /** The outline of one document: declarations with their children. Params: {@code uri}, {@code root}. */
    @JsonRequest("groovyscope/documentSymbols")
    public CompletableFuture<Map<String, Object>> documentSymbols(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null) {
                throw invalid("documentSymbols needs a uri");
            }
            List<Map<String, Object>> symbols = new java.util.ArrayList<>();
            int lines = 0;
            if (!outside(current, params.get("uri"))) {
                String uri = params.get("uri").toString();
                for (org.groovyscope.engine.model.Symbol symbol : current.documentSymbols(uri)) {
                    symbols.add(outline(symbol));
                }
                lines = org.groovyscope.engine.search.WorkspaceOverview.lineCount(current.workspace().text(uri));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("symbols", symbols);
            response.put("lines", lines);
            return response;
        });
    }

    private static Map<String, Object> outline(org.groovyscope.engine.model.Symbol symbol) {
        Map<String, Object> json = ReportJson.symbol(symbol);
        json.put("endLine", symbol.location() == null ? null : symbol.location().range().end().line());
        List<Map<String, Object>> children = new java.util.ArrayList<>();
        for (org.groovyscope.engine.model.Symbol child : symbol.children()) {
            if (child.location() != null) {
                children.add(outline(child));
            }
        }
        if (!children.isEmpty()) {
            json.put("children", children);
        }
        return json;
    }

    /**
     * Rename with the same safety as the LSP rename: refused when a reference is matched by name only. Returns
     * the edits per document without applying them. Params: {@code uri}, {@code line}, {@code character},
     * {@code newName}, {@code root}.
     */
    @JsonRequest("groovyscope/rename")
    public CompletableFuture<Map<String, Object>> rename(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null || params.get("newName") == null) {
                throw invalid("rename needs a uri with line and character, and newName");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            if (outside(current, params.get("uri"))) {
                response.put("refused", "the document is outside the workspace");
                return response;
            }
            org.groovyscope.engine.model.RenameResult result = current.rename(params.get("uri").toString(),
                    positionOf(params), params.get("newName").toString());
            if (result.refusalReason() != null) {
                response.put("refused", result.refusalReason());
                return response;
            }
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (Map.Entry<String, List<org.groovyscope.engine.model.TextEdit>> entry
                    : new java.util.TreeMap<>(result.edits()).entrySet()) {
                if (outside(current, entry.getKey())) {
                    continue;
                }
                edits.put(entry.getKey(), entry.getValue().stream().map(Converters::toLsp).toList());
            }
            response.put("edits", edits);
            return response;
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
            List<org.groovyscope.engine.model.Reference> found = outside(current, params.get("uri")) ? List.of()
                    : current.references(params.get("uri").toString(), positionOf(params), includeDeclaration);
            for (org.groovyscope.engine.model.Reference reference : found) {
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
            List<String> uris = targets(current, params);
            List<org.groovyscope.engine.semantics.DynamicCodeFinder.Finding> found = noneInside(params, uris)
                    ? List.of() : current.dynamicCode(uris);
            for (org.groovyscope.engine.semantics.DynamicCodeFinder.Finding finding : found) {
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

    /**
     * The source of one declaration, found by name. Params: {@code uri}, {@code symbol} (simple name,
     * {@code Class.member}, {@code Class#member} or a class), {@code line} (zero-based, picks among overloads),
     * {@code maxChars} (default 12000), {@code root}.
     */
    @JsonRequest("groovyscope/readSymbol")
    public CompletableFuture<Map<String, Object>> readSymbol(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            if (params.get("uri") == null || params.get("symbol") == null || params.get("symbol").toString().isBlank()) {
                throw invalid("readSymbol needs a uri and a symbol");
            }
            String uri = params.get("uri").toString();
            if (outside(current, uri) || WorkspaceModel.isJavaUri(uri)) {
                return SearchJson.readSymbol(org.groovyscope.engine.search.SymbolReader.Result.notFound(List.of()));
            }
            Integer line = params.get("line") instanceof Number number ? number.intValue() : null;
            int maxChars = params.get("maxChars") instanceof Number number ? number.intValue()
                    : org.groovyscope.engine.search.SymbolReader.DEFAULT_MAX_CHARS;
            return SearchJson.readSymbol(org.groovyscope.engine.search.SymbolReader.read(current.snapshot().index(), uri,
                    params.get("symbol").toString(), line, maxChars));
        });
    }

    /**
     * Structural code search. Params: {@code kind} (one of {@link org.groovyscope.engine.search.CodeSearch#KINDS}),
     * filters {@code name}, {@code type}, {@code pattern}, {@code modifiers}, {@code emptyBody}, {@code rethrows},
     * {@code minLines}, {@code minParameters}, plus {@code uris}, {@code limit} (default 500), {@code root}.
     */
    @JsonRequest("groovyscope/findCode")
    public CompletableFuture<Map<String, Object>> findCode(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            org.groovyscope.engine.search.CodeSearch.Query query = new org.groovyscope.engine.search.CodeSearch.Query(
                    text(params, "kind"), text(params, "name"), text(params, "type"), text(params, "pattern"),
                    stringList(params.get("modifiers")), flag(params, "emptyBody"), flag(params, "rethrows"),
                    number(params, "minLines"), number(params, "minParameters"));
            int limit = params.get("limit") instanceof Number number ? Math.max(1, number.intValue()) : DEFAULT_FIND_LIMIT;
            List<String> uris = targets(current, params);
            org.groovyscope.engine.services.Snapshot snapshot = current.snapshot();
            List<org.groovyscope.engine.search.CodeSearch.Match> matches;
            try {
                matches = org.groovyscope.engine.search.CodeSearch.find(
                        noneInside(params, uris) ? Map.of() : snapshot.result().modules(), snapshot.index()::lines, uris, query);
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            return SearchJson.matches(matches, limit);
        });
    }

    /**
     * Duplicated statement runs. Params: {@code uris}, {@code minLines} (default 6), {@code minStatements}
     * (default 3), {@code root}.
     */
    @JsonRequest("groovyscope/duplicates")
    public CompletableFuture<Map<String, Object>> duplicates(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            List<String> uris = targets(current, params);
            Integer minLines = number(params, "minLines");
            Integer minStatements = number(params, "minStatements");
            org.groovyscope.engine.services.Snapshot snapshot = current.snapshot();
            return SearchJson.duplicates(org.groovyscope.engine.search.CloneFinder.find(
                    noneInside(params, uris) ? Map.of() : snapshot.result().modules(), snapshot.index()::lines, uris,
                    minLines == null ? 6 : minLines, minStatements == null ? 3 : minStatements));
        });
    }

    /** Sizes of the workspace by package, file and class, test files and untested classes. Params: {@code root}. */
    @JsonRequest("groovyscope/overview")
    public CompletableFuture<Map<String, Object>> overview(Map<String, Object> params) {
        return org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = requireServices(params);
            Map<String, String> sources = new LinkedHashMap<>(current.workspace().sources());
            sources.keySet().removeIf(uri -> !current.workspace().contains(uri));
            return SearchJson.overview(org.groovyscope.engine.search.WorkspaceOverview.of(current.workspace().root(),
                    sources, current.snapshot().result().modules()));
        });
    }

    static final int DEFAULT_FIND_LIMIT = 500;

    private static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Boolean flag(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof Boolean bool ? bool : value == null ? null : Boolean.valueOf(value.toString());
    }

    private static Integer number(Map<String, Object> params, String key) {
        return params.get(key) instanceof Number number ? number.intValue() : null;
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
            Path path;
            try {
                path = Paths.get(root).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException e) {
                throw invalid("root is not a valid path: " + root);
            }
            if (!java.nio.file.Files.isDirectory(path)) {
                throw invalid("root is not an existing directory: " + root);
            }
            workspaceRoot.set(path);
            services.compareAndSet(null, servicesProvider.apply(path));
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

    /**
     * The {@code uris} parameter with every directory replaced by the workspace sources under it. URIs whose real
     * path lies outside the workspace root are dropped: requests never read files the workspace does not hold.
     */
    static List<String> targets(LanguageServices current, Map<String, Object> params) {
        List<String> inside = new java.util.ArrayList<>();
        for (String uri : stringList(params.get("uris"))) {
            if (current.workspace().contains(uri)) {
                inside.add(uri);
            } else {
                org.groovyscope.engine.log.EngineLog.warn("%s", "ignoring " + uri + ": outside the workspace root");
            }
        }
        return expandDirectories(inside, current.workspace().sources().keySet());
    }

    /** True when the request named documents but none of them is inside the workspace (so nothing is checked). */
    static boolean noneInside(Map<String, Object> params, List<String> targets) {
        return targets.isEmpty() && !stringList(params.get("uris")).isEmpty();
    }

    /** True when a {@code uri} parameter does not name a file inside the workspace root. */
    private static boolean outside(LanguageServices current, Object uri) {
        return uri == null || !current.workspace().contains(uri.toString());
    }

    /**
     * Directories expand to the known documents under them; a directory with none stays as it is, so the request
     * matches nothing instead of falling back to the whole workspace.
     */
    static List<String> expandDirectories(List<String> uris, java.util.Collection<String> known) {
        List<String> expanded = new java.util.ArrayList<>();
        for (String uri : uris) {
            Path path = org.groovyscope.engine.workspace.WorkspaceModel.toPath(uri);
            if (path == null || !java.nio.file.Files.isDirectory(path)) {
                expanded.add(uri);
                continue;
            }
            String prefix = org.groovyscope.engine.workspace.WorkspaceModel.toUri(path);
            String under = prefix.endsWith("/") ? prefix : prefix + "/";
            List<String> inside = known.stream().filter(k -> k.startsWith(under)).sorted().toList();
            expanded.addAll(inside.isEmpty() ? List.of(uri) : inside);
        }
        return expanded;
    }

    /** JSON-RPC traffic logging for launchers outside this package (the daemon). */
    public static java.util.function.Function<org.eclipse.lsp4j.jsonrpc.MessageConsumer,
            org.eclipse.lsp4j.jsonrpc.MessageConsumer> messageLogging() {
        return MessageLogging.wrapper();
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

    /**
     * Releases what this connection holds once its stream has ended (normally or not): the documents it opened
     * are closed, so their unsaved text stops overriding the disk for other clients, and its diagnostics thread
     * stops. Safe to call more than once.
     */
    public void connectionClosed() {
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
