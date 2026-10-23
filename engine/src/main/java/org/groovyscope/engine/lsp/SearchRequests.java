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

import static org.groovyscope.engine.lsp.RequestParams.flag;
import static org.groovyscope.engine.lsp.RequestParams.invalid;
import static org.groovyscope.engine.lsp.RequestParams.noneInside;
import static org.groovyscope.engine.lsp.RequestParams.number;
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.positionOf;
import static org.groovyscope.engine.lsp.RequestParams.requireHeadroom;
import static org.groovyscope.engine.lsp.RequestParams.stringList;
import static org.groovyscope.engine.lsp.RequestParams.targets;
import static org.groovyscope.engine.lsp.RequestParams.text;
import static org.groovyscope.engine.lsp.RequestParams.uriOf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.groovyscope.engine.model.Definition;
import org.groovyscope.engine.model.Reference;
import org.groovyscope.engine.model.RenameResult;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.search.CloneFinder;
import org.groovyscope.engine.search.CodeSearch;
import org.groovyscope.engine.search.SymbolReader;
import org.groovyscope.engine.search.WorkspaceOverview;
import org.groovyscope.engine.semantics.DynamicCodeFinder;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.Snapshot;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Navigation and search: symbols, definitions, hover, the outline, references and rename, and the structural
 * searches ({@code findCode}, {@code duplicates}, {@code dynamicCode}, {@code readSymbol}, {@code overview}).
 */
final class SearchRequests {

    /** Matches {@code groovyscope/findCode} returns when no {@code limit} is given. */
    static final int DEFAULT_FIND_LIMIT = 500;

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    SearchRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /** Declarations whose name contains {@code query}. Params: {@code query}, {@code root}. */
    CompletableFuture<Map<String, Object>> symbols(Map<String, Object> params) {
        return requests.light(cancel -> {
            LanguageServices current = services.apply(params);
            String query = params.get("query") == null ? "" : params.get("query").toString();
            List<Map<String, Object>> symbols = new ArrayList<>();
            for (Symbol symbol : current.workspaceSymbols(query)) {
                symbols.add(ReportJson.symbol(symbol));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("symbols", symbols);
            response.put("count", symbols.size());
            return response;
        });
    }

    private static Map<String, Object> definitionJson(Definition definition) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", ReportJson.symbol(definition.symbol()));
        response.put("confidence", definition.confidence().name());
        response.put("inWorkspace", definition.isInWorkspace());
        return response;
    }

    /** The declaration under a position with its confidence, or {@code null}. Params: {@code uri}, {@code line}, {@code character}, {@code root}. */
    CompletableFuture<Map<String, Object>> definition(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null) {
                throw invalid("definition needs a uri with line and character");
            }
            if (outside(current, params.get("uri"))) {
                return null;
            }
            return current.definition(params.get("uri").toString(), positionOf(params))
                    .map(SearchRequests::definitionJson).orElse(null);
        });
    }

    /** Hover text for a position (types, documentation, runtime-profile parameters, finding notes), or null. */
    CompletableFuture<Map<String, Object>> hover(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            LanguageServices current = services.apply(params);
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
                        if (!found.inferredTypes().isEmpty()) {
                            List<Map<String, Object>> types = new ArrayList<>();
                            for (org.groovyscope.engine.model.Hover.InferredType type : found.inferredTypes()) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("type", type.type());
                                item.put("line", type.line());
                                types.add(item);
                            }
                            response.put("inferredTypes", types);
                        }
                        return response;
                    })
                    .orElse(null);
        });
    }

    /** The declaration of the type of the expression under a position, or null. */
    CompletableFuture<Map<String, Object>> typeDefinition(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null) {
                throw invalid("typeDefinition needs a uri with line and character");
            }
            if (outside(current, params.get("uri"))) {
                return null;
            }
            return current.typeDefinition(params.get("uri").toString(), positionOf(params))
                    .map(SearchRequests::definitionJson).orElse(null);
        });
    }

    /** The outline of one document: declarations with their children. Params: {@code uri}, {@code root}. */
    CompletableFuture<Map<String, Object>> documentSymbols(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null) {
                throw invalid("documentSymbols needs a uri");
            }
            List<Map<String, Object>> symbols = new ArrayList<>();
            int lines = 0;
            if (!outside(current, params.get("uri"))) {
                String uri = params.get("uri").toString();
                for (Symbol symbol : current.documentSymbols(uri)) {
                    symbols.add(outline(symbol));
                }
                lines = WorkspaceOverview.lineCount(current.workspace().text(uri));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("symbols", symbols);
            response.put("lines", lines);
            return response;
        });
    }

    private static Map<String, Object> outline(Symbol symbol) {
        Map<String, Object> json = ReportJson.symbol(symbol);
        json.put("endLine", symbol.location() == null ? null : symbol.location().range().end().line());
        List<Map<String, Object>> children = new ArrayList<>();
        for (Symbol child : symbol.children()) {
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
    CompletableFuture<Map<String, Object>> rename(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null || params.get("newName") == null) {
                throw invalid("rename needs a uri with line and character, and newName");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            if (outside(current, params.get("uri"))) {
                response.put("refused", "the document is outside the workspace");
                return response;
            }
            RenameResult result = current.rename(params.get("uri").toString(), positionOf(params),
                    params.get("newName").toString());
            if (result.refusalReason() != null) {
                response.put("refused", result.refusalReason());
                return response;
            }
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (Map.Entry<String, List<TextEdit>> entry : new TreeMap<>(result.edits()).entrySet()) {
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
    CompletableFuture<Map<String, Object>> references(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null) {
                throw invalid("references needs a uri with line and character");
            }
            boolean includeDeclaration = Boolean.TRUE.equals(params.get("includeDeclaration"));
            List<Map<String, Object>> references = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<Reference> found = outside(current, params.get("uri")) ? List.of()
                    : current.references(params.get("uri").toString(), positionOf(params), includeDeclaration);
            for (Reference reference : found) {
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
    CompletableFuture<Map<String, Object>> dynamicCode(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            List<Map<String, Object>> findings = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> uris = targets(current, params);
            if (uris.isEmpty()) {
                requireHeadroom(); // a whole-workspace walk is what memory pressure refuses
            }
            List<DynamicCodeFinder.Finding> found = noneInside(params, uris) ? List.of() : current.dynamicCode(uris);
            for (DynamicCodeFinder.Finding finding : found) {
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
    CompletableFuture<Map<String, Object>> readSymbol(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            LanguageServices current = services.apply(params);
            if (params.get("uri") == null || params.get("symbol") == null || params.get("symbol").toString().isBlank()) {
                throw invalid("readSymbol needs a uri and a symbol");
            }
            String uri = params.get("uri").toString();
            if (outside(current, uri) || WorkspaceModel.isJavaUri(uri)) {
                return SearchJson.readSymbol(SymbolReader.Result.notFound(List.of()));
            }
            Integer line = params.get("line") instanceof Number number ? number.intValue() : null;
            int maxChars = params.get("maxChars") instanceof Number number ? number.intValue()
                    : SymbolReader.DEFAULT_MAX_CHARS;
            return SearchJson.readSymbol(SymbolReader.read(current.snapshot().index(), uri,
                    params.get("symbol").toString(), line, maxChars));
        });
    }

    /**
     * Structural code search. Params: {@code kind} (one of {@link CodeSearch#KINDS}), filters {@code name},
     * {@code type}, {@code pattern}, {@code modifiers}, {@code emptyBody}, {@code rethrows}, {@code minLines},
     * {@code minParameters}, plus {@code uris}, {@code limit} (default 500), {@code root}.
     */
    CompletableFuture<Map<String, Object>> findCode(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            CodeSearch.Query query = new CodeSearch.Query(text(params, "kind"), text(params, "name"),
                    text(params, "type"), text(params, "pattern"), stringList(params.get("modifiers")),
                    flag(params, "emptyBody"), flag(params, "rethrows"), number(params, "minLines"),
                    number(params, "minParameters"));
            int limit = params.get("limit") instanceof Number number ? Math.max(1, number.intValue()) : DEFAULT_FIND_LIMIT;
            List<String> uris = targets(current, params);
            Snapshot snapshot = current.snapshot();
            List<CodeSearch.Match> matches;
            try {
                matches = CodeSearch.find(noneInside(params, uris) ? Map.of() : snapshot.result().modules(),
                        snapshot.index()::lines, uris, query, snapshot.resolver());
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
    CompletableFuture<Map<String, Object>> duplicates(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            requireHeadroom();
            List<String> uris = targets(current, params);
            Integer minLines = number(params, "minLines");
            Integer minStatements = number(params, "minStatements");
            Snapshot snapshot = current.snapshot();
            return SearchJson.duplicates(CloneFinder.find(noneInside(params, uris) ? Map.of()
                    : snapshot.result().modules(), snapshot.index()::lines, uris,
                    minLines == null ? 6 : minLines, minStatements == null ? 3 : minStatements));
        });
    }

    /** Sizes of the workspace by package, file and class, test files and untested classes. Params: {@code root}. */
    CompletableFuture<Map<String, Object>> overview(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            requireHeadroom();
            Map<String, String> sources = new LinkedHashMap<>(current.workspace().sources());
            sources.keySet().removeIf(uri -> !current.workspace().contains(uri));
            return SearchJson.overview(WorkspaceOverview.of(current.workspace().root(), sources,
                    current.snapshot().result().modules()));
        });
    }
}
