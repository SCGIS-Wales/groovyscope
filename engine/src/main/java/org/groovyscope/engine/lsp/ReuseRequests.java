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
import static org.groovyscope.engine.lsp.RequestParams.stringList;
import static org.groovyscope.engine.lsp.RequestParams.targets;
import static org.groovyscope.engine.lsp.RequestParams.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.search.ReuseFinder;
import org.groovyscope.engine.search.ReuseHelpers;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.Snapshot;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Reuse before writing: {@code groovyscope/similar}, the existing code a candidate could call instead of repeating
 * it, and the {@code helpers} {@code explainCode} lists.
 */
final class ReuseRequests {

    /** The name a candidate text is parsed under. */
    static final String CANDIDATE_NAME = "SimilarCandidate.groovy";

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    ReuseRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /**
     * Existing code a candidate could reuse (see {@link ReuseFinder}). Params: {@code root} and exactly one of
     * {@code text} (a method, members, a whole file or bare statements about to be written; an optional {@code uri}
     * and 1-based {@code line} name the class it is meant for, whose package and superclasses scope the signature
     * matches), {@code symbol} with {@code uri} (an existing declaration, found as {@code groovyscope/edit} finds it;
     * a 1-based {@code line} picks an overload) or {@code ranges} with {@code uri} ({@code [[start, end], ...]},
     * 1-based and inclusive: lines a branch added); then {@code paths} (workspace-relative folders or files searched,
     * the workspace by default), {@code minLines} (default {@code reuse.minLines}, else {@code duplicates.minLines})
     * and {@code limit} (default {@code reuse.limit}). Answers {@code matches} ({@code symbol}, {@code uri},
     * {@code line}, {@code endLine} 1-based, {@code kind} exact, similar, signature or calls, {@code score} from 0 to
     * 1, {@code reason}), strongest first and never the candidate itself, or for {@code ranges} {@code byRange}
     * ({@code range}, {@code matches}) in the order given; plus {@code elapsedMs}.
     */
    CompletableFuture<Map<String, Object>> similar(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            long started = System.nanoTime();
            String given = params.get("text") == null ? null : params.get("text").toString();
            String symbol = text(params, "symbol");
            Object ranges = params.get("ranges");
            int forms = (given != null ? 1 : 0) + (symbol != null ? 1 : 0) + (ranges != null ? 1 : 0);
            if (forms != 1 || given != null && given.isBlank()) {
                throw invalid("similar needs exactly one of text, symbol with a uri, or ranges with a uri");
            }
            LanguageServices current = services.apply(params);
            String uri = text(params, "uri");
            if (given == null && uri == null) {
                throw invalid("similar: symbol and ranges need the uri of the document they are in");
            }
            if (uri != null && outside(current, uri)) {
                throw invalid("similar: " + uri + " is outside the workspace");
            }
            EngineConfig.Reuse settings = current.workspace().config().practices().reuse();
            Integer minLines = number(params, "minLines");
            Integer limit = number(params, "limit");
            ReuseFinder.Query query = new ReuseFinder.Query(settings,
                    minLines == null ? settings.minLines() : Math.max(1, minLines),
                    limit == null ? settings.limit() : Math.max(1, limit), scope(current, params));
            Snapshot snapshot = current.snapshot();
            Map<String, Object> response = new LinkedHashMap<>();
            if (ranges != null) {
                List<Map<String, Object>> byRange = new ArrayList<>();
                Document document = document(current, snapshot, uri);
                for (int[] range : ranges(ranges)) {
                    Cancellation.checkpoint();
                    ReuseFinder.Candidate candidate = ReuseFinder.ofRange(document.module(), uri, document.text(),
                            range[0], range[1]);
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("range", List.of(range[0], range[1]));
                    json.put("matches", matches(ReuseFinder.find(candidate, snapshot.result().modules(),
                            snapshot.index(), query)));
                    byRange.add(json);
                }
                response.put("byRange", byRange);
            } else {
                ReuseFinder.Candidate candidate = given != null ? fromText(current, snapshot, given, uri, params)
                        : fromSymbol(current, snapshot, uri, symbol, number(params, "line"));
                if (candidate.name() != null) {
                    response.put("symbol", candidate.name());
                }
                response.put("matches", matches(ReuseFinder.find(candidate, snapshot.result().modules(),
                        snapshot.index(), query)));
            }
            response.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            return response;
        });
    }

    private static ReuseFinder.Candidate fromText(LanguageServices current, Snapshot snapshot, String given,
            String uri, Map<String, Object> params) {
        ClassNode context = uri == null ? null : contextClass(document(current, snapshot, uri).module(),
                number(params, "line"));
        return ReuseFinder.ofText(given, text -> {
            GroovyCompiler.Parsed parsed = current.quality().parse(CANDIDATE_NAME, text);
            return parsed.ok() ? parsed.module() : Optional.empty();
        }, context).orElseThrow(() -> invalid("similar: the text does not parse as a file, as members of a class "
                + "or as statements of a method"));
    }

    private static ReuseFinder.Candidate fromSymbol(LanguageServices current, Snapshot snapshot, String uri,
            String symbol, Integer line) {
        if (WorkspaceModel.isJavaUri(uri)) {
            throw invalid("similar: " + uri + " is a Java source; similar reads Groovy declarations");
        }
        Document document = document(current, snapshot, uri);
        try {
            return ReuseFinder.ofSymbol(document.module(), uri, document.text(), symbol, line);
        } catch (IllegalArgumentException e) {
            throw invalid("similar: " + e.getMessage());
        }
    }

    /** A document's tree and text: the snapshot's, or the document parsed alone when the compile left it out. */
    private record Document(ModuleNode module, TextLines text) {
    }

    private static Document document(LanguageServices current, Snapshot snapshot, String uri) {
        ModuleNode module = snapshot.result().modules().get(uri);
        if (module != null) {
            return new Document(module, snapshot.index().lines(uri));
        }
        String source = current.workspace().text(uri);
        if (source == null) {
            throw invalid("similar: " + uri + " cannot be read (missing, or over workspace.maxFileBytes)");
        }
        GroovyCompiler.Parsed parsed = current.quality().parse(CANDIDATE_NAME, source);
        if (parsed.module().isEmpty()) {
            throw invalid("similar: " + uri + " does not parse");
        }
        return new Document(parsed.module().get(), new TextLines(source));
    }

    /** The class a 1-based line lies in, the first class without a line, or {@code null} for an empty document. */
    static ClassNode contextClass(ModuleNode module, Integer line) {
        ClassNode first = null;
        for (ClassNode classNode : module.getClasses()) {
            if (line != null && classNode.getLineNumber() <= line && line <= classNode.getLastLineNumber()) {
                return classNode;
            }
            if (first == null || first.isScript() && !classNode.isScript()) {
                first = classNode;
            }
        }
        return first;
    }

    /** {@code [[start, end], ...]}, 1-based and inclusive, each with its start not after its end. */
    static List<int[]> ranges(Object value) {
        List<int[]> ranges = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Number start
                        && pair.get(1) instanceof Number end && start.intValue() >= 1
                        && start.intValue() <= end.intValue()) {
                    ranges.add(new int[] {start.intValue(), end.intValue()});
                } else {
                    ranges.clear();
                    break;
                }
            }
        }
        if (ranges.isEmpty()) {
            throw invalid("similar: ranges must be [[start, end], ...], 1-based and inclusive, start <= end");
        }
        return ranges;
    }

    /** The documents searched: the workspace when no {@code paths} are given, else the documents under them. */
    private static Set<String> scope(LanguageServices current, Map<String, Object> params) {
        List<String> paths = stringList(params.get("paths"));
        if (paths.isEmpty()) {
            return null;
        }
        Path root = current.workspace().root();
        List<String> uris = new ArrayList<>();
        for (String path : paths) {
            uris.add(WorkspaceModel.toUri(root.resolve(path).normalize()));
        }
        Map<String, Object> asUris = new LinkedHashMap<>();
        asUris.put("uris", uris);
        return Set.copyOf(targets(current, asUris));
    }

    private static List<Map<String, Object>> matches(List<ReuseFinder.Match> matches) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReuseFinder.Match match : matches) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("symbol", match.symbol());
            json.put("uri", match.uri());
            json.put("line", match.line());
            json.put("endLine", match.endLine());
            json.put("kind", match.kind());
            json.put("score", match.score());
            json.put("reason", match.reason());
            list.add(json);
        }
        return list;
    }

    /**
     * The {@code helpers} of {@code explainCode}: the utilities a class, or its member {@code self}, could call (see
     * {@link ReuseHelpers#helpers}), each with {@code symbol}, {@code signature}, {@code line} (zero-based, as the
     * rest of {@code explainCode}) and {@code uri}.
     */
    static List<Map<String, Object>> helpers(LanguageServices current, Snapshot snapshot, ClassNode owner,
            MethodNode self) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReuseHelpers.Helper helper : ReuseHelpers.helpers(owner, self, snapshot.result().modules(),
                type -> snapshot.index().declarationEntry(type).map(NodeEntry::uri).orElse(null),
                current.workspace().config().practices().reuse())) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("symbol", helper.symbol());
            json.put("signature", helper.signature());
            json.put("line", helper.line());
            json.put("uri", helper.uri());
            list.add(json);
        }
        return list;
    }
}
