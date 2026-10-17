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

/** Reads the engine version that Gradle expands into {@code engine-version.properties}. */
public final class EngineVersion {

    private static final String RESOURCE = "/org/groovyscope/engine/engine-version.properties";
    private static final String UNKNOWN = "unknown";

    private EngineVersion() {
    }

    /** Returns the engine version, or {@code "unknown"} when the resource is missing or unexpanded. */
    public static String current() {
        try (InputStream in = EngineVersion.class.getResourceAsStream(RESOURCE)) {
            return read(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    static String read(InputStream in) throws IOException {
        if (in == null) {
            return UNKNOWN;
        }
        Properties properties = new Properties();
        properties.load(in);
        String value = properties.getProperty("version", UNKNOWN).trim();
        if (value.isEmpty() || value.startsWith("${")) {
            return UNKNOWN;
        }
        return value;
    }
}
