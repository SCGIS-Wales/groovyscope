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
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.explain.DesignFacts;
import org.groovyscope.engine.explain.DesignIndex;
import org.groovyscope.engine.explain.DesignSuggestions;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.Snapshot;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * {@code groovyscope/design}: how a class is designed (see {@link DesignFacts}), with a refactor for each finding and
 * the edit tool call that performs it.
 */
final class DesignRequests {

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    DesignRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /**
     * The design of a class, or of every class of a document. Params: {@code root} and {@code symbol} (a class as
     * {@code pkg.Class} or its simple name; a member stands for its class) or {@code uri}. Answers {@code found},
     * {@code items} (one per class: {@code class}, {@code simpleName}, {@code uri}, {@code path}, {@code line},
     * {@code size}, {@code responsibilities}, {@code cohesion} ({@code lcom4}, {@code groups}, {@code byFields},
     * {@code separable}: see {@link DesignFacts.Cohesion}), {@code fieldsUsedByOneMethod}, {@code featureEnvy},
     * {@code sharedParameterLists}, {@code typeSwitches}, {@code publicMutableFields}, {@code staticMutableState},
     * {@code longParameterLists}, {@code primitiveObsession}, {@code statelessMethods}, {@code dataOwner} and
     * {@code suggestions}, lines 1-based) and {@code elapsedMs}.
     */
    CompletableFuture<Map<String, Object>> design(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            long started = System.nanoTime();
            LanguageServices current = services.apply(params);
            Snapshot snapshot = current.snapshot();
            String symbol = text(params, "symbol");
            String uri = text(params, "uri");
            Set<ClassNode> classes = Collections.newSetFromMap(new IdentityHashMap<>());
            List<ClassNode> ordered = new ArrayList<>();
            if (symbol != null) {
                for (ASTNode declaration : current.findDeclarations(snapshot, symbol)) {
                    ClassNode owner = ownerOf(declaration);
                    if (owner != null && classes.add(owner)) {
                        ordered.add(owner);
                    }
                }
            } else if (uri != null) {
                ModuleNode module = outside(current, uri) ? null : snapshot.result().modules().get(uri);
                if (module != null) {
                    for (ClassNode classNode : module.getClasses()) {
                        if (classNode.getLineNumber() > 0 && !classNode.isScript()
                                && !classNode.getName().contains("$_") && !classNode.getName().contains("$Trait$")) {
                            ordered.add(classNode);
                        }
                    }
                }
            } else {
                throw invalid("design needs a symbol (a class) or a uri");
            }
            EngineConfig.Design limits = current.workspace().config().practices().design();
            List<Map<String, Object>> items = new ArrayList<>();
            for (ClassNode classNode : ordered) {
                Cancellation.checkpoint();
                if (classNode.getModule() == null) {
                    continue;
                }
                DesignFacts.ClassDesign design = DesignFacts.of(classNode, DesignIndex.of(classNode.getModule()),
                        limits);
                CodeFacts.ClassFacts facts = CodeFacts.ofClass(classNode, snapshot.index(), current.callGraph());
                String classUri = snapshot.index().declarationEntry(classNode).map(NodeEntry::uri).orElse(null);
                items.add(classJson(design, facts, classUri, relative(current, classUri)));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("found", !items.isEmpty());
            response.put("items", items);
            response.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            return response;
        });
    }

    private static ClassNode ownerOf(ASTNode declaration) {
        if (declaration instanceof ClassNode classNode) {
            return classNode;
        }
        if (declaration instanceof MethodNode method) {
            return method.getDeclaringClass();
        }
        if (declaration instanceof PropertyNode property) {
            return property.getDeclaringClass();
        }
        if (declaration instanceof FieldNode field) {
            return field.getOwner();
        }
        return null;
    }

    /** A document's path relative to the workspace root, with forward slashes, as the edit tools take it. */
    private static String relative(LanguageServices current, String uri) {
        Path path = uri == null ? null : WorkspaceModel.toPath(uri);
        if (path == null) {
            return uri;
        }
        Path root = current.workspace().root();
        return (path.startsWith(root) ? root.relativize(path) : path).toString().replace('\\', '/');
    }

    static Map<String, Object> classJson(DesignFacts.ClassDesign design, CodeFacts.ClassFacts facts, String uri,
            String path) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("class", design.name());
        json.put("simpleName", design.simpleName());
        json.put("uri", uri);
        json.put("path", path);
        json.put("line", design.line());
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("methods", design.size().methods());
        size.put("fields", design.size().fields());
        size.put("lines", design.size().lines());
        json.put("size", size);
        List<Map<String, Object>> responsibilities = new ArrayList<>();
        for (CodeFacts.Responsibility responsibility : facts.responsibilities()) {
            responsibilities.add(map("verb", responsibility.verb(), "methods", responsibility.methods()));
        }
        json.put("responsibilities", responsibilities);
        List<Map<String, Object>> groups = groups(design.cohesion().groups());
        Map<String, Object> cohesion = new LinkedHashMap<>();
        cohesion.put("lcom4", design.cohesion().lcom4());
        cohesion.put("groups", groups);
        cohesion.put("byFields", groups(design.cohesion().byFields()));
        cohesion.put("separable", groups(design.cohesion().separable()));
        json.put("cohesion", cohesion);
        List<Map<String, Object>> locals = new ArrayList<>();
        for (DesignFacts.LocalCandidate local : design.fieldsUsedByOneMethod()) {
            locals.add(map("field", local.field(), "method", local.method(), "line", local.line()));
        }
        json.put("fieldsUsedByOneMethod", locals);
        List<Map<String, Object>> envy = new ArrayList<>();
        for (DesignFacts.Envy found : design.featureEnvy()) {
            Map<String, Object> entry = map("method", found.method(), "line", found.line(), "target", found.target());
            entry.put("targetUses", found.targetUses());
            entry.put("ownUses", found.ownUses());
            envy.add(entry);
        }
        json.put("featureEnvy", envy);
        List<Map<String, Object>> shared = new ArrayList<>();
        for (DesignFacts.SharedParameters list : design.sharedParameterLists()) {
            shared.add(map("parameters", list.parameters(), "methods", list.methods()));
        }
        json.put("sharedParameterLists", shared);
        List<Map<String, Object>> switches = new ArrayList<>();
        for (DesignFacts.TypeSwitch found : design.typeSwitches()) {
            Map<String, Object> entry = map("method", found.method(), "line", found.line(), "subject", found.subject());
            entry.put("kind", found.kind());
            List<Map<String, Object>> branches = new ArrayList<>();
            for (DesignFacts.Branch branch : found.branches()) {
                branches.add(map("type", branch.type(), "startLine", branch.startLine(), "endLine", branch.endLine()));
            }
            entry.put("branches", branches);
            switches.add(entry);
        }
        json.put("typeSwitches", switches);
        List<Map<String, Object>> exposed = new ArrayList<>();
        for (DesignFacts.Exposed found : design.publicMutableFields()) {
            Map<String, Object> entry = map("name", found.name(), "type", found.type(), "line", found.line());
            entry.put("kind", found.kind());
            entry.put("field", found.field());
            exposed.add(entry);
        }
        json.put("publicMutableFields", exposed);
        List<Map<String, Object>> statics = new ArrayList<>();
        for (DesignFacts.StaticState found : design.staticMutableState()) {
            Map<String, Object> entry = map("name", found.name(), "type", found.type(), "line", found.line());
            entry.put("kind", found.kind());
            statics.add(entry);
        }
        json.put("staticMutableState", statics);
        List<Map<String, Object>> longLists = new ArrayList<>();
        for (DesignFacts.LongParameters found : design.longParameterLists()) {
            longLists.add(map("method", found.method(), "line", found.line(), "parameters", found.parameters()));
        }
        json.put("longParameterLists", longLists);
        List<Map<String, Object>> primitives = new ArrayList<>();
        for (DesignFacts.PrimitiveObsession found : design.primitiveObsession()) {
            primitives.add(map("method", found.method(), "line", found.line(), "parameters", found.parameters()));
        }
        json.put("primitiveObsession", primitives);
        List<Map<String, Object>> stateless = new ArrayList<>();
        for (DesignFacts.Stateless found : design.statelessMethods()) {
            Map<String, Object> entry = map("method", found.method(), "line", found.line(), "target", found.target());
            entry.put("callers", callers(facts, found.node()));
            stateless.add(entry);
        }
        json.put("statelessMethods", stateless);
        DesignFacts.DataOwner owner = design.dataOwner();
        Map<String, Object> dataOwner = owner == null ? null
                : map("class", owner.owner(), "method", owner.method(), "reads", owner.reads());
        if (dataOwner != null) {
            dataOwner.put("writes", owner.writes());
        }
        json.put("dataOwner", dataOwner);
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (DesignSuggestions.Suggestion suggestion : DesignSuggestions.of(design, path)) {
            suggestions.add(map("kind", suggestion.kind(), "message", suggestion.message(), "tool", suggestion.tool()));
        }
        json.put("suggestions", suggestions);
        return json;
    }

    private static List<Map<String, Object>> groups(List<DesignFacts.Group> groups) {
        List<Map<String, Object>> json = new ArrayList<>();
        for (DesignFacts.Group group : groups) {
            json.add(map("methods", group.methods(), "fields", group.fields()));
        }
        return json;
    }

    /** How many calls reach a method, from the call graph {@code explainCode} reads. */
    private static int callers(CodeFacts.ClassFacts facts, MethodNode method) {
        for (CodeFacts.MethodFacts candidate : facts.methods()) {
            if (candidate.name().equals(method.getName()) && candidate.startLine() + 1 <= method.getLineNumber()
                    && method.getLineNumber() <= candidate.endLine() + 1) {
                return candidate.callerCount();
            }
        }
        return 0;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(k1, v1);
        json.put(k2, v2);
        return json;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> json = map(k1, v1, k2, v2);
        json.put(k3, v3);
        return json;
    }
}
