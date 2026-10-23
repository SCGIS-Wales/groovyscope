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
import static org.groovyscope.engine.lsp.RequestParams.lineRanges;
import static org.groovyscope.engine.lsp.RequestParams.noneInside;
import static org.groovyscope.engine.lsp.RequestParams.number;
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.requireHeadroom;
import static org.groovyscope.engine.lsp.RequestParams.stringList;
import static org.groovyscope.engine.lsp.RequestParams.targets;
import static org.groovyscope.engine.lsp.RequestParams.text;
import static org.groovyscope.engine.lsp.RequestParams.uriOf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.index.Declarations;
import org.groovyscope.engine.model.CheckResult;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.search.SymbolEditor;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.QualityServices;

/**
 * The quality requests: {@code groovyscope/check}, {@code fix} and {@code format} over documents or the whole
 * workspace, {@code parse} over candidate texts that are not workspace documents, the edit gate's {@code preview}
 * (findings of candidate texts of workspace documents) and {@code edit} (one declaration edited as an object), and
 * the runtime profile's {@code contracts} and {@code profile}.
 */
final class QualityRequests {

    /** Most texts one {@code groovyscope/parse} request takes. */
    static final int MAX_PARSE_TEXTS = 16;
    /** Most syntax errors reported per text. */
    static final int MAX_PARSE_ERRORS = 20;
    /** The virtual file name of a {@code groovyscope/parse} request that names none. */
    static final String DEFAULT_PARSE_NAME = "Candidate.groovy";

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    QualityRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /**
     * Lint, compile and type-check diagnostics for the given documents (all when {@code uris} is empty).
     * Params: {@code uris} (list), {@code profile} (default|jenkins, optional), {@code include} (list).
     */
    CompletableFuture<Map<String, Object>> check(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            List<String> uris = targets(current, params);
            if (uris.isEmpty()) {
                requireHeadroom(); // a whole-workspace walk is what memory pressure refuses
            }
            Object profile = params.get("profile");
            Set<String> include = new LinkedHashSet<>(stringList(params.get("include")));
            CheckResult result = noneInside(params, uris)
                    ? new CheckResult(Map.of())
                    : current.quality().check(uris, profile == null ? null : profile.toString(), include);
            Map<String, List<org.eclipse.lsp4j.Diagnostic>> converted = new LinkedHashMap<>();
            for (Map.Entry<String, List<Diagnostic>> entry : result.diagnostics().entrySet()) {
                List<org.eclipse.lsp4j.Diagnostic> list = new ArrayList<>();
                for (Diagnostic diagnostic : entry.getValue()) {
                    list.add(Converters.toLsp(diagnostic, current.quality().fixable(diagnostic)));
                }
                converted.put(entry.getKey(), list);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("errors", result.count(Diagnostic.Severity.ERROR));
            summary.put("warnings", result.count(Diagnostic.Severity.WARNING));
            summary.put("information", result.count(Diagnostic.Severity.INFORMATION));
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
    CompletableFuture<Map<String, Object>> fix(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            List<String> uris = targets(current, params);
            QualityServices.FixResult result = noneInside(params, uris)
                    ? new QualityServices.FixResult(Map.of(), Map.of(), Map.of())
                    : current.quality().fix(uris, new LinkedHashSet<>(stringList(params.get("codes"))),
                            lineRanges(params.get("lines")));
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (Map.Entry<String, List<TextEdit>> entry : result.edits().entrySet()) {
                List<org.eclipse.lsp4j.TextEdit> list = new ArrayList<>();
                for (TextEdit edit : entry.getValue()) {
                    list.add(Converters.toLsp(edit));
                }
                edits.put(entry.getKey(), list);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("edits", edits);
            response.put("applied", result.applied());
            response.put("appliedCounts", result.appliedCounts());
            response.put("left", result.left());
            response.put("rejected", result.rejected());
            response.put("needsReview", result.needsReview());
            return response;
        });
    }

    /** Formatting edits for the given documents (all workspace documents when {@code uris} is empty). */
    CompletableFuture<Map<String, Object>> format(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            List<String> uris = targets(current, params);
            if (uris.isEmpty()) {
                requireHeadroom(); // a whole-workspace walk is what memory pressure refuses
            }
            if (uris.isEmpty() && !noneInside(params, uris)) {
                uris = new ArrayList<>(current.workspace().sources().keySet());
                uris.removeIf(uri -> !current.workspace().contains(uri));
            }
            Map<String, List<org.eclipse.lsp4j.TextEdit>> edits = new LinkedHashMap<>();
            for (String uri : uris) {
                List<org.eclipse.lsp4j.TextEdit> list = new ArrayList<>();
                try {
                    for (TextEdit edit : current.quality().format(uri)) {
                        list.add(Converters.toLsp(edit));
                    }
                } catch (IllegalStateException e) {
                    throw new ResponseErrorException(
                            new ResponseError(ResponseErrorCode.InvalidRequest, e.getMessage(), null));
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
     * Whether each candidate text of one file parses, with its syntax errors and, on request, its declarations.
     * Nothing is read from or written to disk and nothing from the workspace runs: each text is parsed on its own,
     * syntax only. Params: {@code root}, {@code name} (the virtual file name; only its last path segment is used),
     * {@code texts} (at most {@value #MAX_PARSE_TEXTS}), {@code declarations} (boolean, default false). A text over
     * {@code workspace.maxFileBytes} (UTF-8 bytes) is answered as not parsing rather than parsed.
     */
    CompletableFuture<Map<String, Object>> parse(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            if (params == null || !(params.get("texts") instanceof List<?> items)) {
                throw invalid("parse needs texts (a list of strings)");
            }
            if (items.size() > MAX_PARSE_TEXTS) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest,
                        "parse takes at most " + MAX_PARSE_TEXTS + " texts, got " + items.size(), null));
            }
            List<String> texts = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof String text)) {
                    throw invalid("parse needs texts (a list of strings)");
                }
                texts.add(text);
            }
            LanguageServices current = services.apply(params);
            long limit = current.workspace().config().maxFileBytes();
            String name = virtualName(params.get("name"));
            boolean declarations = Boolean.TRUE.equals(flag(params, "declarations"));
            List<Map<String, Object>> results = new ArrayList<>();
            for (String text : texts) {
                cancel.checkCanceled();
                results.add(limit > 0 && utf8Length(text) > limit
                        ? parseResult(false, List.of(tooLarge()), declarations ? List.of() : null)
                        : parsed(current.quality().parse(name, text), declarations));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            return response;
        });
    }

    /** The last path segment of {@code name}, or {@value #DEFAULT_PARSE_NAME} when it has none. */
    static String virtualName(Object name) {
        String text = name == null ? "" : name.toString().strip();
        String last = text.substring(Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1);
        return last.isEmpty() ? DEFAULT_PARSE_NAME : last;
    }

    /** The length of {@code text} in UTF-8 bytes, without encoding it. */
    static long utf8Length(String text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static Map<String, Object> tooLarge() {
        return error(1, 1, "too large to parse");
    }

    /** One text's result: the first errors and, when asked for, its declarations. */
    static Map<String, Object> parsed(GroovyCompiler.Parsed parsed, boolean declarations) {
        List<Map<String, Object>> errors = errorList(parsed);
        List<Map<String, Object>> found = null;
        if (declarations) {
            found = new ArrayList<>();
            for (Declarations.Declaration declaration : Declarations.of(parsed.module().orElse(null))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", declaration.name());
                entry.put("kind", declaration.kind().label());
                entry.put("line", declaration.line());
                entry.put("endLine", declaration.endLine());
                found.add(entry);
            }
        }
        return parseResult(parsed.ok(), errors, found);
    }

    private static Map<String, Object> parseResult(boolean ok, List<Map<String, Object>> errors,
            List<Map<String, Object>> declarations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("errors", errors);
        if (declarations != null) {
            result.put("declarations", declarations);
        }
        return result;
    }

    /**
     * The findings of candidate texts of workspace documents as if they were saved: nothing is written and the
     * snapshot is left as it is. Params: {@code root}, {@code texts} (a list of {@code {uri, text}}, at most
     * {@value #MAX_PARSE_TEXTS}; each uri inside the workspace, the file need not exist yet), {@code include} (any of
     * {@code compile}, {@code lint}, {@code practices}, {@code types}; the first three when absent or empty; the
     * type preview, GS077, runs with {@code practices} or {@code types}, and {@code types} also reports the type
     * errors of classes already static). Per uri: {@code syntax}, the
     * first {@value #MAX_PARSE_ERRORS} syntax errors (1-based), and {@code diagnostics}, the LSP diagnostics
     * {@code check} would report (with {@code data.fixable}); a text with syntax errors gets no diagnostics, and a
     * text over {@code workspace.maxFileBytes} (UTF-8 bytes) only the syntax error {@code too large to preview}.
     */
    CompletableFuture<Map<String, Object>> preview(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            long started = System.nanoTime();
            if (params == null || !(params.get("texts") instanceof List<?> items)) {
                throw invalid("preview needs texts (a list of {uri, text})");
            }
            if (items.size() > MAX_PARSE_TEXTS) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest,
                        "preview takes at most " + MAX_PARSE_TEXTS + " texts, got " + items.size(), null));
            }
            Set<String> include = new LinkedHashSet<>(stringList(params.get("include")));
            for (String name : include) {
                if (!QualityServices.PREVIEW_INCLUDES.contains(name)) {
                    throw invalid("preview includes only compile, lint, practices and types, not " + name);
                }
            }
            LanguageServices current = services.apply(params);
            Map<String, String> texts = new LinkedHashMap<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("uri") instanceof String uri)
                        || !(entry.get("text") instanceof String text)) {
                    throw invalid("preview needs texts (a list of {uri, text})");
                }
                if (outside(current, uri)) {
                    throw invalid("preview: " + uri + " is outside the workspace");
                }
                texts.put(uri, text);
            }
            long limit = current.workspace().config().maxFileBytes();
            Map<String, List<Map<String, Object>>> syntax = new LinkedHashMap<>();
            Map<String, String> candidates = new LinkedHashMap<>();
            for (Map.Entry<String, String> text : texts.entrySet()) {
                cancel.checkCanceled();
                List<Map<String, Object>> errors;
                if (limit > 0 && utf8Length(text.getValue()) > limit) {
                    errors = List.of(error(1, 1, "too large to preview"));
                } else {
                    errors = syntaxErrors(current.quality().parse(virtualName(text.getKey()), text.getValue()));
                }
                syntax.put(text.getKey(), errors);
                if (errors.isEmpty()) {
                    candidates.put(text.getKey(), text.getValue());
                }
            }
            Map<String, List<QualityServices.PreviewFinding>> found = candidates.isEmpty() ? Map.of()
                    : current.quality().preview(candidates, include);
            Map<String, Object> results = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : syntax.entrySet()) {
                List<org.eclipse.lsp4j.Diagnostic> diagnostics = new ArrayList<>();
                for (QualityServices.PreviewFinding finding : found.getOrDefault(entry.getKey(), List.of())) {
                    diagnostics.add(Converters.toLsp(finding.diagnostic(), finding.fixable()));
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("syntax", entry.getValue());
                result.put("diagnostics", diagnostics);
                results.put(entry.getKey(), result);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            response.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            return response;
        });
    }

    /**
     * One declaration of a document edited as an object; nothing is written. Params: {@code root}, {@code uri},
     * {@code symbol}, {@code operation} (see {@link SymbolEditor.Operation}), {@code text} (for every operation but
     * {@code delete}), {@code position} ({@code add_member}: {@code start}, {@code end} or {@code after:<member>}),
     * {@code line} (1-based; picks among declarations of the same name). Answers the whole new text, an exact
     * {@code oldStr} and {@code newStr} for an editor's {@code str_replace}, the original lines it touched and the
     * declaration's full name.
     */
    CompletableFuture<Map<String, Object>> edit(Map<String, Object> params) {
        return requests.lightAbout(uriOf(params), cancel -> {
            if (params == null || params.get("uri") == null) {
                throw invalid("edit needs a uri, a symbol and an operation");
            }
            SymbolEditor.Operation operation = SymbolEditor.Operation.of(String.valueOf(params.get("operation")))
                    .orElseThrow(() -> invalid("edit: operation must be one of " + SymbolEditor.Operation.labels()));
            LanguageServices current = services.apply(params);
            String uri = params.get("uri").toString();
            if (outside(current, uri)) {
                throw invalid("edit: " + uri + " is outside the workspace");
            }
            String source = current.workspace().text(uri);
            if (source == null) {
                throw invalid("edit: " + uri + " cannot be read (missing, or over workspace.maxFileBytes)");
            }
            GroovyCompiler.Parsed parsed = current.quality().parse(virtualName(uri), source);
            if (!parsed.ok()) {
                Diagnostic first = parsed.errors().isEmpty() ? null : parsed.errors().get(0);
                throw invalid("does not parse: " + (first == null ? "syntax error"
                        : first.message() + " (line " + (first.range().start().line() + 1) + ")"));
            }
            Object given = params.get("text");
            Object symbol = params.get("symbol");
            SymbolEditor.Result result;
            try {
                result = SymbolEditor.edit(source, parsed.module().orElse(null), new SymbolEditor.Request(
                        symbol == null ? null : symbol.toString(), operation, given == null ? null : given.toString(),
                        text(params, "position"), number(params, "line")));
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("text", result.text());
            response.put("oldStr", result.oldStr());
            response.put("newStr", result.newStr());
            response.put("startLine", result.startLine());
            response.put("endLine", result.endLine());
            response.put("symbol", result.symbol());
            if (current.workspace().config().practices().types().declareOnEdit()) {
                response.put("untyped", untyped(current, uri, operation, result));
            }
            return response;
        });
    }

    /**
     * The {@code untyped} answer of {@code edit}: per parameter, return type or field that the inserted or replacing
     * text declares without a type, {@code {line, what, inferred}} (line 1-based in the new text; {@code inferred} the
     * type the static type checker infers, or null). Empty for a deletion or an import.
     */
    private static List<Map<String, Object>> untyped(LanguageServices current, String uri,
            SymbolEditor.Operation operation, SymbolEditor.Result result) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (operation == SymbolEditor.Operation.DELETE || operation == SymbolEditor.Operation.ADD_IMPORT
                || result.insertedTo() <= result.insertedFrom()) {
            return list;
        }
        org.groovyscope.engine.index.TextLines lines = new org.groovyscope.engine.index.TextLines(result.text());
        int from = lines.positionOf(result.insertedFrom()).line() + 1;
        int to = lines.positionOf(result.insertedTo() - 1).line() + 1;
        for (org.groovyscope.engine.typecheck.UntypedDeclarations.Untyped untyped
                : current.quality().untyped(uri, result.text(), from, to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", untyped.line());
            item.put("what", untyped.what());
            item.put("inferred", untyped.inferred());
            list.add(item);
        }
        return list;
    }

    /**
     * The first {@value #MAX_PARSE_ERRORS} syntax errors of a parsed text (1-based), as {@code parse} reports them;
     * a text that failed without naming an error gets one at its start.
     */
    static List<Map<String, Object>> syntaxErrors(GroovyCompiler.Parsed parsed) {
        List<Map<String, Object>> errors = errorList(parsed);
        if (errors.isEmpty() && !parsed.ok()) {
            errors.add(error(1, 1, "does not parse"));
        }
        return errors;
    }

    private static List<Map<String, Object>> errorList(GroovyCompiler.Parsed parsed) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Diagnostic diagnostic : parsed.errors()) {
            if (errors.size() >= MAX_PARSE_ERRORS) {
                break;
            }
            errors.add(error(diagnostic.range().start().line() + 1, diagnostic.range().start().column() + 1,
                    diagnostic.message()));
        }
        return errors;
    }

    private static Map<String, Object> error(int line, int column, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("line", line);
        error.put("column", column);
        error.put("message", message);
        return error;
    }

    /** Contract items of every profile-governed document, for {@code groovyscope baseline save}. Params: {@code root}. */
    CompletableFuture<Map<String, Object>> contracts(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("files", services.apply(params).quality().contracts());
            return response;
        });
    }

    /** The runtime profile's view of one document. Params: {@code uri}, {@code root}. */
    CompletableFuture<Map<String, Object>> profile(Map<String, Object> params) {
        return requests.light(cancel -> {
            LanguageServices current = services.apply(params);
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
}
