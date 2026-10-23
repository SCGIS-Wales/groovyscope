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

/**
 * A problem reported for a document.
 *
 * @param confidence how sure the finding is, for the checks that reason about types ({@code STATIC} when a declared
 *     type proves it, {@code INFERRED} when it rests on an inferred one); {@code null} for every other finding
 */
public record Diagnostic(String uri, Range range, Severity severity, String source, String code, String message,
        Confidence confidence) {

    /** A finding without a confidence: what every check but the type checks reports. */
    public Diagnostic(String uri, Range range, Severity severity, String source, String code, String message) {
        this(uri, range, severity, source, code, message, null);
    }

    /** Severities in LSP order. */
    public enum Severity {
        ERROR,
        WARNING,
        INFORMATION,
        HINT
    }
}
