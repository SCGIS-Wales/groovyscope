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
package org.groovyscope.engine.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Path globs from configuration (workspace excludes, runtime profile globs), compiled once and cached. A glob
 * with invalid syntax is reported once on stderr and then never matches, instead of failing the request.
 */
public final class Globs {

    static final int CACHE_LIMIT = 1024;
    private static final Map<String, Optional<PathMatcher>> MATCHERS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Globs() {
    }

    /** The compiled matcher for a glob, or empty when its syntax is invalid. */
    public static Optional<PathMatcher> matcher(String glob) {
        Optional<PathMatcher> cached = MATCHERS.get(glob);
        if (cached != null) {
            return cached;
        }
        Optional<PathMatcher> compiled;
        try {
            compiled = Optional.of(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            // PatternSyntaxException is an IllegalArgumentException.
            if (REPORTED.add(glob)) {
                System.err.println("groovyscope-engine: ignoring invalid glob '" + glob + "': " + e.getMessage());
            }
            compiled = Optional.empty();
        }
        if (MATCHERS.size() >= CACHE_LIMIT) {
            MATCHERS.clear();
        }
        MATCHERS.put(glob, compiled);
        return compiled;
    }

    /** True when a relative path matches a glob; a leading {@code **}{@code /} also matches at the root. */
    public static boolean matches(String glob, Path relative) {
        if (glob == null || glob.isBlank() || relative == null) {
            return false;
        }
        if (matcher(glob).map(m -> m.matches(relative)).orElse(false)) {
            return true;
        }
        return glob.startsWith("**/") && matcher(glob.substring(3)).map(m -> m.matches(relative)).orElse(false);
    }

    /**
     * True when a glob excludes everything under a relative directory, so a walk can skip it: the glob ends in
     * {@code /**} and the part before that matches the directory.
     */
    public static boolean excludesDirectory(String glob, Path relativeDirectory) {
        if (glob == null || !glob.endsWith("/**") || relativeDirectory == null
                || relativeDirectory.toString().isEmpty()) {
            return false;
        }
        return matches(glob.substring(0, glob.length() - 3), relativeDirectory);
    }
}
