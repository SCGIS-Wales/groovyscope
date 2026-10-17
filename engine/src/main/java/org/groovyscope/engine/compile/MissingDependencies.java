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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;

/**
 * With no classpath configured, imports of third-party classes cannot resolve, and every such import (and every use
 * of the class it brings in) becomes a compile error that says nothing about the real cause. This folds them into one
 * {@code GS100} warning per file that names the missing packages and how to configure the classpath.
 */
public final class MissingDependencies {

    /** The practice code of the folded finding. */
    public static final String CODE = "GS100";
    /** Diagnostic source of the folded finding. */
    public static final String SOURCE = "groovyscope";
    /** Most packages named in the message before it is cut short. */
    static final int MAX_PACKAGES = 8;

    private static final String UNRESOLVED = "unable to resolve class ";
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w$.]+)");

    private MissingDependencies() {
    }

    /**
     * The compile diagnostics of every document with unresolved imports folded into {@code GS100}.
     *
     * @param diagnostics compile diagnostics per document
     * @param sources document texts
     * @param severity the severity of the folded finding, or {@code null} when {@code GS100} is switched off (the
     *     unresolved-class errors of affected files are then dropped without a replacement)
     */
    public static Map<String, List<Diagnostic>> fold(Map<String, List<Diagnostic>> diagnostics,
            Map<String, String> sources, Diagnostic.Severity severity) {
        Map<String, List<Diagnostic>> folded = new LinkedHashMap<>();
        for (Map.Entry<String, List<Diagnostic>> entry : diagnostics.entrySet()) {
            folded.put(entry.getKey(), fold(entry.getKey(), entry.getValue(), sources.get(entry.getKey()), severity));
        }
        return folded;
    }

    /** One document's compile diagnostics with unresolved imports folded into {@code GS100}. */
    public static List<Diagnostic> fold(String uri, List<Diagnostic> diagnostics, String text,
            Diagnostic.Severity severity) {
        if (text == null || diagnostics.isEmpty()) {
            return diagnostics;
        }
        TextLines lines = new TextLines(text);
        Range first = null;
        Set<String> packages = new LinkedHashSet<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (!isUnresolved(diagnostic)) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(lines.line(diagnostic.range().start().line()));
            if (matcher.find()) {
                packages.add(packageOf(matcher.group(1)));
                if (first == null || diagnostic.range().start().isBefore(first.start())) {
                    first = diagnostic.range();
                }
            }
        }
        if (first == null) {
            return diagnostics;
        }
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (!isUnresolved(diagnostic)) {
                kept.add(diagnostic);
            }
        }
        if (severity != null) {
            kept.add(0, new Diagnostic(uri, first, severity, SOURCE, CODE, message(packages)));
        }
        return kept;
    }

    static boolean isUnresolved(Diagnostic diagnostic) {
        return GroovyCompiler.SOURCE.equals(diagnostic.source()) && diagnostic.message() != null
                && diagnostic.message().startsWith(UNRESOLVED);
    }

    /** The package of an imported name: the segments before the first capitalised one (a class name). */
    static String packageOf(String imported) {
        String[] segments = imported.split("\\.");
        List<String> kept = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.isEmpty() && Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            kept.add(segment);
        }
        if (kept.size() == segments.length && segments.length > 1) {
            kept.remove(kept.size() - 1);
        }
        return kept.isEmpty() ? imported : String.join(".", kept);
    }

    static String message(Set<String> packages) {
        List<String> names = new ArrayList<>(packages);
        String listed = names.size() > MAX_PACKAGES
                ? String.join(", ", names.subList(0, MAX_PACKAGES)) + ", …"
                : String.join(", ", names);
        return "missing dependencies (no classpath configured): " + listed + "; run `groovyscope classpath`";
    }
}
