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

import static org.groovyscope.engine.lsp.RequestParams.invalid;
import static org.groovyscope.engine.lsp.RequestParams.number;
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.groovyscope.engine.refactor.Changes;
import org.groovyscope.engine.refactor.MemberMover;
import org.groovyscope.engine.refactor.MethodExtractor;
import org.groovyscope.engine.refactor.SignatureChanger;
import org.groovyscope.engine.search.ExactReplacement;
import org.groovyscope.engine.services.LanguageServices;

/**
 * Semantic edits that compute their text edits and write nothing: {@code changeSignature}, {@code extractMethod} and
 * {@code moveMember}, and the {@code Extract method} refactor code action. Each answers {@code edits} (per document
 * URI, the LSP text edits, as {@code groovyscope/rename} answers them) and {@code files} (per changed document an
 * exact {@code oldStr}, which occurs once in its current text, and the {@code newStr} that replaces it), or only
 * {@code refused} with the reason nothing is changed.
 */
final class RefactorRequests {

    /** The title of the refactor code action. */
    static final String EXTRACT_TITLE = "Extract method";

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    RefactorRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /**
     * Changes a method's parameters and return type with every call site whose target is known (see
     * {@link SignatureChanger}). Params: {@code root}, {@code uri}, {@code symbol}, {@code line} (1-based, picks an
     * overload), {@code parameters} (the whole new list in its new order, each {@code {name, type?, default?}}; a
     * parameter is matched to an old one by name, a missing old name is removed and a new name is added, its
     * {@code default} passed by every call site), {@code returnType}. Answers also {@code callSites} and
     * {@code declaration} (the new signature on one line).
     */
    CompletableFuture<Map<String, Object>> changeSignature(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            String uri = required(params, "changeSignature", "uri");
            String symbol = required(params, "changeSignature", "symbol");
            if (!(params.get("parameters") instanceof List<?> items)) {
                throw invalid("changeSignature needs parameters, the whole new list ({name, type?, default?})");
            }
            List<SignatureChanger.NewParameter> parameters = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("name") instanceof String name)) {
                    throw invalid("changeSignature: each parameter needs a name ({name, type?, default?})");
                }
                parameters.add(new SignatureChanger.NewParameter(name, string(entry.get("type")),
                        string(entry.get("default"))));
            }
            LanguageServices current = services.apply(params);
            if (outside(current, uri)) {
                return refusal("the document is outside the workspace");
            }
            cancel.checkCanceled();
            SignatureChanger.Result result;
            try {
                result = SignatureChanger.change(current.snapshot(), uri, new SignatureChanger.Request(symbol,
                        number(params, "line"), parameters, text(params, "returnType")));
            } catch (IllegalArgumentException e) {
                throw invalid("changeSignature: " + e.getMessage());
            }
            if (result.refused() != null) {
                return refusal(result.refused());
            }
            Map<String, Object> response = changes(current, result.changes());
            if (!response.containsKey("refused")) {
                response.put("callSites", result.callSites());
                response.put("declaration", result.declaration());
            }
            return response;
        });
    }

    /**
     * Turns whole statements of one block into a new private method after the enclosing one (see
     * {@link MethodExtractor}). Params: {@code root}, {@code uri}, {@code startLine} and {@code endLine} (1-based,
     * inclusive, whole lines), {@code name} ({@value MethodExtractor#DEFAULT_NAME} when absent). Answers also
     * {@code method} (its signature), {@code parameters} ({@code {name, type}} in order of first use),
     * {@code returns} (the local it returns, or null) and {@code static}.
     */
    CompletableFuture<Map<String, Object>> extractMethod(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            String uri = required(params, "extractMethod", "uri");
            Integer startLine = number(params, "startLine");
            Integer endLine = number(params, "endLine");
            if (startLine == null || endLine == null) {
                throw invalid("extractMethod needs startLine and endLine (1-based, inclusive)");
            }
            LanguageServices current = services.apply(params);
            if (outside(current, uri)) {
                return refusal("the document is outside the workspace");
            }
            cancel.checkCanceled();
            MethodExtractor.Result result;
            try {
                result = MethodExtractor.extract(current.snapshot(), uri, new MethodExtractor.Request(startLine,
                        endLine, text(params, "name")));
            } catch (IllegalArgumentException e) {
                throw invalid("extractMethod: " + e.getMessage());
            }
            if (result.refused() != null) {
                return refusal(result.refused());
            }
            Map<String, Object> response = changes(current, result.changes());
            if (!response.containsKey("refused")) {
                response.put("method", result.method());
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (MethodExtractor.NewParameter parameter : result.parameters()) {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("name", parameter.name());
                    json.put("type", parameter.type());
                    parameters.add(json);
                }
                response.put("parameters", parameters);
                response.put("returns", result.returns());
                response.put("static", result.isStatic());
            }
            return response;
        });
    }

    /**
     * Moves a static method, or one that uses no instance state, into another workspace class, with every call site
     * whose target is known (see {@link MemberMover}). Params: {@code root}, {@code uri}, {@code symbol},
     * {@code line} (1-based, picks an overload), {@code toClass} (simple or qualified name). Answers also
     * {@code method} (its new signature), {@code from} and {@code to} (qualified class names), {@code callSites},
     * {@code static} (true when it became static) and {@code madePublic} (true when it lost {@code private}
     * because other classes now call it).
     */
    CompletableFuture<Map<String, Object>> moveMember(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            String uri = required(params, "moveMember", "uri");
            String symbol = required(params, "moveMember", "symbol");
            String toClass = required(params, "moveMember", "toClass");
            LanguageServices current = services.apply(params);
            if (outside(current, uri)) {
                return refusal("the document is outside the workspace");
            }
            cancel.checkCanceled();
            MemberMover.Result result;
            try {
                result = MemberMover.move(current.snapshot(), uri, new MemberMover.Request(symbol,
                        number(params, "line"), toClass));
            } catch (IllegalArgumentException e) {
                throw invalid("moveMember: " + e.getMessage());
            }
            if (result.refused() != null) {
                return refusal(result.refused());
            }
            Map<String, Object> response = changes(current, result.changes());
            if (!response.containsKey("refused")) {
                response.put("method", result.method());
                response.put("from", result.from());
                response.put("to", result.to());
                response.put("callSites", result.callSites());
                response.put("static", result.madeStatic());
                response.put("madePublic", result.madePublic());
            }
            return response;
        });
    }

    /**
     * The {@code Extract method} refactor for a selection of whole statements, the new method named
     * {@value MethodExtractor#DEFAULT_NAME} (the user renames it after); empty for an empty selection or one that
     * {@link MethodExtractor} refuses.
     */
    static Optional<CodeAction> extractAction(LanguageServices current, String uri, Range range) {
        if (range == null || range.getStart().equals(range.getEnd())) {
            return Optional.empty();
        }
        int startLine = range.getStart().getLine() + 1;
        int endLine = range.getEnd().getCharacter() == 0 && range.getEnd().getLine() > range.getStart().getLine()
                ? range.getEnd().getLine() : range.getEnd().getLine() + 1;
        MethodExtractor.Result result;
        try {
            result = MethodExtractor.extract(current.snapshot(), uri, new MethodExtractor.Request(startLine, endLine,
                    null));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (result.refused() != null || !current(current, result.changes())) {
            return Optional.empty();
        }
        Map<String, List<TextEdit>> changes = new HashMap<>();
        for (Map.Entry<String, List<org.groovyscope.engine.model.TextEdit>> entry
                : result.changes().edits().entrySet()) {
            changes.put(entry.getKey(), entry.getValue().stream().map(Converters::toLsp).toList());
        }
        CodeAction action = new CodeAction(EXTRACT_TITLE);
        action.setKind(CodeActionKind.RefactorExtract);
        action.setEdit(new WorkspaceEdit(changes));
        return Optional.of(action);
    }

    /**
     * {@code edits} and {@code files} of computed changes; only {@code refused} when a changed document's current
     * text is not the text the edits were computed on (it changed since the compile).
     */
    private static Map<String, Object> changes(LanguageServices current, Changes changes) {
        if (!current(current, changes)) {
            return refusal("a document changed since the last compile; ask again");
        }
        Map<String, Object> edits = new LinkedHashMap<>();
        for (Map.Entry<String, List<org.groovyscope.engine.model.TextEdit>> entry : changes.edits().entrySet()) {
            edits.put(entry.getKey(), entry.getValue().stream().map(Converters::toLsp).toList());
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (Changes.FileChange file : changes.files()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("uri", file.uri());
            json.put("oldStr", file.oldStr());
            json.put("newStr", file.newStr());
            files.add(json);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("edits", edits);
        response.put("files", files);
        return response;
    }

    /**
     * Whether every changed document's current text is the one the changes were computed on: its {@code oldStr}
     * occurs there once, and replacing it gives the new text.
     */
    private static boolean current(LanguageServices current, Changes changes) {
        for (Changes.FileChange file : changes.files()) {
            String text = current.workspace().text(file.uri());
            if (text == null || !ExactReplacement.occursOnce(text, file.oldStr())
                    || !text.replace(file.oldStr(), file.newStr()).equals(file.text())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> refusal(String reason) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refused", reason);
        return response;
    }

    private static String required(Map<String, Object> params, String request, String key) {
        String value = params == null ? null : text(params, key);
        if (value == null) {
            throw invalid(request + " needs " + key);
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
