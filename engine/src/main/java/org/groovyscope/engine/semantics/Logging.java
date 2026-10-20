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
package org.groovyscope.engine.semantics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the engine knows about logging calls, in one place: the level methods, the logger types and logging
 * annotations with their library, and the libraries that take {@code {}} placeholders. Each consumer composes the
 * set it needs from these instead of keeping its own copy.
 */
public final class Logging {

    /** The level methods of SLF4J, Log4j2 and Commons Logging. */
    public static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");
    /** {@link #LEVELS} plus the level names of java.util.logging and Log4j 1. */
    public static final Set<String> ALL_LEVELS = union(LEVELS, Set.of("warning", "fatal", "severe"));
    /** Writing to the console, which leaks as surely as a log does. */
    public static final Set<String> CONSOLE = Set.of("println", "print", "printf");
    /** Logger types and their library; only SLF4J and Log4j2 take {@code {}} placeholders. */
    public static final Map<String, String> LOGGER_TYPES = Map.of("org.slf4j.Logger", "slf4j",
            "org.apache.logging.log4j.Logger", "log4j2", "java.util.logging.Logger", "jul",
            "org.apache.commons.logging.Log", "commons", "org.apache.log4j.Logger", "log4j");
    /** Groovy's logging annotations and the library of the field each adds. */
    public static final Map<String, String> LOGGING_ANNOTATIONS = Map.of("Slf4j", "slf4j", "Log4j2", "log4j2",
            "Log", "jul", "Commons", "commons", "Log4j", "log4j");
    /** The libraries whose messages take {@code {}} placeholders. */
    public static final Set<String> PLACEHOLDER_LIBRARIES = Set.of("slf4j", "log4j2");

    private Logging() {
    }

    /** The union of two sets, unmodifiable. */
    public static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> all = new HashSet<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }
}
