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
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.requireHeadroom;
import static org.groovyscope.engine.lsp.RequestParams.stringList;
import static org.groovyscope.engine.lsp.RequestParams.targets;

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
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.QualityServices;

/**
 * The quality requests: {@code groovyscope/check}, {@code fix} and {@code format} over documents or the whole
 * workspace, {@code parse} over candidate texts that are not workspace documents, and the runtime profile's
 * {@code contracts} and {@code profile}.
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
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("line", 1);
        error.put("column", 1);
        error.put("message", "too large to parse");
        return error;
    }

    /** One text's result: the first errors and, when asked for, its declarations. */
    static Map<String, Object> parsed(GroovyCompiler.Parsed parsed, boolean declarations) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Diagnostic diagnostic : parsed.errors()) {
            if (errors.size() >= MAX_PARSE_ERRORS) {
                break;
            }
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("line", diagnostic.range().start().line() + 1);
            error.put("column", diagnostic.range().start().column() + 1);
            error.put("message", diagnostic.message());
            errors.add(error);
        }
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
