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
package org.groovyscope.engine.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What {@code check} found: diagnostics per document (every checked document has an entry, possibly empty).
 */
public record CheckResult(Map<String, List<Diagnostic>> diagnostics) {

    public CheckResult {
        diagnostics = Map.copyOf(new TreeMap<>(diagnostics));
    }

    public long count(Diagnostic.Severity severity) {
        return diagnostics.values().stream().flatMap(List::stream).filter(d -> d.severity() == severity).count();
    }

    public long total() {
        return diagnostics.values().stream().mapToLong(List::size).sum();
    }
}
