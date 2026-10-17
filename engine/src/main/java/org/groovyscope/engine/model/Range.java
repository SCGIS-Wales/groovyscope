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

/** Half-open text range: {@code start} inclusive, {@code end} exclusive. */
public record Range(Position start, Position end) {

    public static Range of(int startLine, int startColumn, int endLine, int endColumn) {
        return new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
    }

    public boolean contains(Position position) {
        return !position.isBefore(start) && position.isBefore(end);
    }

    public boolean contains(Range other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    /** A rough size used to prefer the innermost of several enclosing ranges. */
    public long extent() {
        return (long) (end.line() - start.line()) * 100_000L + (end.column() - start.column());
    }
}
