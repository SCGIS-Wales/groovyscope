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
package org.groovyscope.engine.compile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.model.Diagnostic;

/**
 * What a compile produced: one {@link ModuleNode} per source that parsed, the source text that was
 * compiled (for name lookups), the diagnostics per URI (every compiled URI has an entry, possibly empty, so
 * clients can clear stale problems), and the URIs whose module is parsed but not resolved.
 */
public record CompilationResult(Map<String, ModuleNode> modules, Map<String, String> sources,
        Map<String, List<Diagnostic>> diagnostics, int phaseReached, Set<String> unresolvedUris) {

    public CompilationResult {
        modules = Map.copyOf(modules);
        sources = Map.copyOf(sources);
        diagnostics = Map.copyOf(diagnostics);
        unresolvedUris = Set.copyOf(unresolvedUris);
    }

    public static CompilationResult empty() {
        return new CompilationResult(Map.of(), Map.of(), Map.of(), 0, Set.of());
    }

    public boolean hasErrors() {
        return diagnostics.values().stream()
                .flatMap(List::stream)
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
