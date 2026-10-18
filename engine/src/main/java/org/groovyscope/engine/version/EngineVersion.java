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
package org.groovyscope.engine.version;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the versions that Gradle expands into {@code engine-version.properties}: the engine's own and the
 * CodeNarc release the build resolved from Maven Central and embedded (see {@code settings.gradle.kts}).
 */
public final class EngineVersion {

    private static final String RESOURCE = "/org/groovyscope/engine/engine-version.properties";
    private static final String UNKNOWN = "unknown";
    static final String ENGINE_KEY = "version";
    static final String CODENARC_KEY = "codenarc";

    private EngineVersion() {
    }

    /** Returns the engine version, or {@code "unknown"} when the resource is missing or unexpanded. */
    public static String current() {
        return value(ENGINE_KEY);
    }

    /** Returns the embedded CodeNarc version, or {@code "unknown"} when the resource is missing or unexpanded. */
    public static String codenarc() {
        return value(CODENARC_KEY);
    }

    private static String value(String key) {
        try (InputStream in = EngineVersion.class.getResourceAsStream(RESOURCE)) {
            return read(in, key);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    static String read(InputStream in) throws IOException {
        return read(in, ENGINE_KEY);
    }

    static String read(InputStream in, String key) throws IOException {
        if (in == null) {
            return UNKNOWN;
        }
        Properties properties = new Properties();
        properties.load(in);
        String value = properties.getProperty(key, UNKNOWN).trim();
        if (value.isEmpty() || value.startsWith("${")) {
            return UNKNOWN;
        }
        return value;
    }
}
