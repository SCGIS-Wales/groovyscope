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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.graph.RelationshipGraph;

/** JSON shapes of the explainCode and graph requests. */
final class ExplainJson {

    private ExplainJson() {
    }

    static Map<String, Object> classFacts(CodeFacts.ClassFacts facts) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", facts.kind());
        json.put("name", facts.name());
        json.put("simpleName", facts.simpleName());
        json.put("package", facts.packageName());
        json.put("uri", facts.uri());
        json.put("startLine", facts.startLine());
        json.put("endLine", facts.endLine());
        json.put("modifiers", facts.modifiers());
        json.put("annotations", facts.annotations());
        json.put("superClass", facts.superClass());
        json.put("interfaces", facts.interfaces());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CodeFacts.FieldFacts field : facts.fields()) {
            fields.add(fieldFacts(field));
        }
        json.put("fields", fields);
        List<Map<String, Object>> methods = new ArrayList<>();
        for (CodeFacts.MethodFacts method : facts.methods()) {
            methods.add(methodFacts(method));
        }
        json.put("methods", methods);
        List<Map<String, Object>> responsibilities = new ArrayList<>();
        for (CodeFacts.Responsibility responsibility : facts.responsibilities()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("verb", responsibility.verb());
            entry.put("methods", responsibility.methods());
            responsibilities.add(entry);
        }
        json.put("responsibilities", responsibilities);
        json.put("usedBy", facts.usedBy());
        json.put("uses", facts.uses());
        json.put("callerCount", facts.callerCount());
        return json;
    }

    static Map<String, Object> fieldFacts(CodeFacts.FieldFacts field) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", field.name());
        json.put("type", field.type());
        json.put("visibility", field.visibility());
        json.put("final", field.isFinal());
        json.put("static", field.isStatic());
        json.put("property", field.isProperty());
        json.put("initialised", field.initialised());
        json.put("line", field.line());
        return json;
    }

    static Map<String, Object> methodFacts(CodeFacts.MethodFacts method) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", method.name());
        json.put("signature", method.signature());
        json.put("visibility", method.visibility());
        json.put("static", method.isStatic());
        json.put("abstract", method.isAbstract());
        json.put("constructor", method.isConstructor());
        json.put("returns", method.returns());
        json.put("dynamicReturn", method.dynamicReturn());
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (CodeFacts.Parameter parameter : method.parameters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.name());
            entry.put("type", parameter.type());
            parameters.add(entry);
        }
        json.put("parameters", parameters);
        json.put("startLine", method.startLine());
        json.put("endLine", method.endLine());
        json.put("branches", method.branches());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (CodeFacts.Call call : method.calls()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", call.target());
            entry.put("confidence", call.confidence());
            entry.put("count", call.count());
            calls.add(entry);
        }
        json.put("calls", calls);
        json.put("callerCount", method.callerCount());
        json.put("callers", method.callers());
        json.put("reads", method.reads());
        json.put("writes", method.writes());
        json.put("throws", method.throwsTypes());
        json.put("catches", method.catches());
        json.put("externals", method.externals());
        return json;
    }

    static Map<String, Object> graph(RelationshipGraph.Graph graph) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RelationshipGraph.Node node : graph.nodes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.id());
            entry.put("name", node.name());
            entry.put("kind", node.kind());
            entry.put("package", node.packageName());
            entry.put("uri", node.uri());
            entry.put("startLine", node.startLine());
            entry.put("endLine", node.endLine());
            entry.put("members", node.members());
            entry.put("test", node.test());
            if (node.effect() != null) {
                entry.put("effect", node.effect());
            }
            nodes.add(entry);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (RelationshipGraph.Edge edge : graph.edges()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("from", edge.from());
            entry.put("to", edge.to());
            entry.put("kind", edge.kind());
            entry.put("confidence", edge.confidence());
            entry.put("count", edge.count());
            if (edge.effect() != null) {
                entry.put("effect", edge.effect());
            }
            edges.add(entry);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("nodes", nodes);
        json.put("edges", edges);
        json.put("truncated", graph.truncated());
        json.put("totalClasses", graph.totalClasses());
        return json;
    }
}
