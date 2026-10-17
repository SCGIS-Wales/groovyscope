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
import org.groovyscope.engine.search.CloneFinder;
import org.groovyscope.engine.search.CodeSearch;
import org.groovyscope.engine.search.SymbolReader;
import org.groovyscope.engine.search.WorkspaceOverview;

/** JSON shapes of the code search, clone, symbol source and overview requests. */
final class SearchJson {

    private SearchJson() {
    }

    static Map<String, Object> readSymbol(SymbolReader.Result result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("found", result.found());
        if (!result.found()) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (SymbolReader.Candidate candidate : result.candidates()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", candidate.name());
                entry.put("kind", candidate.kind());
                entry.put("container", candidate.container());
                entry.put("line", candidate.line());
                candidates.add(entry);
            }
            json.put("candidates", candidates);
            return json;
        }
        json.put("name", result.name());
        json.put("kind", result.kind());
        json.put("container", result.container());
        json.put("startLine", result.startLine());
        json.put("endLine", result.endLine());
        json.put("text", result.text());
        json.put("truncated", result.truncated());
        return json;
    }

    static Map<String, Object> matches(List<CodeSearch.Match> matches, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CodeSearch.Match match : matches.subList(0, Math.min(limit, matches.size()))) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("uri", match.uri());
            json.put("line", match.line());
            json.put("character", match.character());
            json.put("endLine", match.endLine());
            json.put("kind", match.kind());
            json.put("detail", match.detail());
            json.put("snippet", match.snippet());
            json.put("enclosing", match.enclosing());
            json.putAll(match.facts());
            list.add(json);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("matches", list);
        response.put("count", matches.size());
        response.put("truncated", matches.size() > list.size());
        return response;
    }

    static Map<String, Object> duplicates(List<CloneFinder.Group> groups) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CloneFinder.Group group : groups) {
            List<Map<String, Object>> occurrences = new ArrayList<>();
            for (CloneFinder.Occurrence occurrence : group.occurrences()) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("uri", occurrence.uri());
                json.put("line", occurrence.line());
                json.put("endLine", occurrence.endLine());
                json.put("enclosing", occurrence.enclosing());
                occurrences.add(json);
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("lines", group.lines());
            json.put("statements", group.statements());
            json.put("similarity", group.exact() ? "exact" : "similar");
            json.put("occurrences", occurrences);
            list.add(json);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groups", list);
        response.put("count", list.size());
        return response;
    }

    static Map<String, Object> overview(WorkspaceOverview.Overview overview) {
        List<Map<String, Object>> packages = new ArrayList<>();
        for (WorkspaceOverview.PackageStats stats : overview.packages()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", stats.name());
            json.put("files", stats.files());
            json.put("classes", stats.classes());
            json.put("lines", stats.lines());
            packages.add(json);
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (WorkspaceOverview.FileStats stats : overview.largestFiles()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("uri", stats.uri());
            json.put("lines", stats.lines());
            json.put("classes", stats.classes());
            json.put("members", stats.members());
            files.add(json);
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        for (WorkspaceOverview.ClassStats stats : overview.largestClasses()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", stats.name());
            json.put("uri", stats.uri());
            json.put("lines", stats.lines());
            json.put("members", stats.members());
            classes.add(json);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", overview.files());
        response.put("lines", overview.lines());
        response.put("packages", packages);
        response.put("largestFiles", files);
        response.put("largestClasses", classes);
        response.put("testFiles", overview.testFiles());
        response.put("untested", overview.untested());
        return response;
    }
}
