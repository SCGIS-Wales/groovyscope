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

/**
 * Markdown shown for the node under the cursor, and the range it applies to.
 *
 * @param inferredTypes for a local assigned more than once, each value it is assigned in source order (null literals
 *     left out); empty otherwise
 */
public record Hover(String markdown, Range range, List<InferredType> inferredTypes) {

    public Hover {
        inferredTypes = List.copyOf(inferredTypes == null ? List.of() : inferredTypes);
    }

    /** A hover without assigned types. */
    public Hover(String markdown, Range range) {
        this(markdown, range, List.of());
    }

    /**
     * One value a local is assigned.
     *
     * @param type the value's type, or {@code null} when it is not known
     * @param line where it is assigned (1-based)
     */
    public record InferredType(String type, int line) {
    }
}
