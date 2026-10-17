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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.ImpactReport;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.services.LanguageServices;

/** Compact JSON shapes for the agent-facing requests. Never includes file bodies. */
final class ReportJson {

    private ReportJson() {
    }

    static Map<String, Object> symbol(Symbol symbol) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", symbol.name());
        json.put("kind", symbol.kind() == null ? null : symbol.kind().name().toLowerCase());
        json.put("container", symbol.container());
        json.put("detail", symbol.detail());
        Location location = symbol.selectionRange() != null && symbol.location() != null
                ? new Location(symbol.location().uri(), symbol.selectionRange())
                : symbol.location();
        json.put("location", location == null ? null : location(location));
        return json;
    }

    static Map<String, Object> location(Location location) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uri", location.uri());
        json.put("line", location.range().start().line());
        json.put("character", location.range().start().column());
        return json;
    }

    static Map<String, Object> site(CallSite site) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("caller", symbol(site.caller()));
        json.put("callee", symbol(site.callee()));
        json.put("site", location(site.site()));
        json.put("confidence", site.confidence().name());
        return json;
    }

    static Map<String, Object> impact(ImpactReport report) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("symbol", symbol(report.symbol()));
        json.put("change", report.change().name().toLowerCase());
        json.put("riskScore", report.riskScore());
        json.put("riskLevel", report.riskLevel());
        json.put("directCallers", sites(report.directCallers()));
        json.put("transitiveCallers", sites(report.transitiveCallers()));
        json.put("overrides", symbols(report.overrides()));
        json.put("tests", sites(report.tests()));
        json.put("dynamicUnknowns", sites(report.dynamicUnknowns()));
        json.put("suggestedChecks", report.suggestedChecks());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("direct", report.directCallers().size());
        counts.put("transitive", report.transitiveCallers().size());
        counts.put("overrides", report.overrides().size());
        counts.put("tests", report.tests().size());
        counts.put("dynamicUnknowns", report.dynamicUnknowns().size());
        json.put("counts", counts);
        return json;
    }

    static List<Map<String, Object>> sites(List<CallSite> sites) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CallSite site : sites) {
            list.add(site(site));
        }
        return list;
    }

    static List<Map<String, Object>> symbols(List<Symbol> symbols) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Symbol symbol : symbols) {
            list.add(symbol(symbol));
        }
        return list;
    }

    /** Nodes and edges reachable from {@code roots} following incoming or outgoing calls to {@code depth}. */
    static Map<String, Object> callGraph(LanguageServices services, List<Symbol> roots, boolean outgoing, int depth) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seenEdges = new LinkedHashSet<>();
        Deque<Symbol> frontier = new ArrayDeque<>(roots);
        for (Symbol root : roots) {
            nodes.put(Converters.key(root), symbol(root));
        }
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            Deque<Symbol> next = new ArrayDeque<>();
            for (Symbol current : frontier) {
                List<CallSite> calls = outgoing ? services.outgoingCalls(current) : services.incomingCalls(current);
                for (CallSite call : calls) {
                    Symbol other = outgoing ? call.callee() : call.caller();
                    String key = Converters.key(other);
                    String edgeKey = Converters.key(call.caller()) + "->" + Converters.key(call.callee()) + "@" + call.site();
                    if (seenEdges.add(edgeKey)) {
                        edges.add(site(call));
                    }
                    if (!nodes.containsKey(key)) {
                        nodes.put(key, symbol(other));
                        if (other.location() != null) {
                            next.add(other);
                        }
                    }
                }
            }
            frontier = next;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("direction", outgoing ? "out" : "in");
        json.put("depth", depth);
        json.put("roots", symbols(roots));
        json.put("nodes", new ArrayList<>(nodes.values()));
        json.put("edges", edges);
        return json;
    }
}
