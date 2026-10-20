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
import org.groovyscope.engine.model.CheckResult;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.QualityServices;

/**
 * The quality requests: {@code groovyscope/check}, {@code fix} and {@code format} over documents or the whole
 * workspace, and the runtime profile's {@code contracts} and {@code profile}.
 */
final class QualityRequests {

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
