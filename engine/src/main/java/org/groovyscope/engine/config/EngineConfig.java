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
package org.groovyscope.engine.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The slice of {@code .groovyscope.yaml} the engine needs. The Python front end validates the full document
 * against its schema and hands the effective configuration over as JSON (the {@code GROOVYSCOPE_CONFIG_JSON}
 * environment variable or {@code --config <file>}); the defaults here mirror {@code defaults.yaml}.
 *
 * @param sourceRoots directories, relative to the workspace root, that hold Groovy sources
 * @param classpath jars or directories added to the analysis classpath (never inferred)
 * @param exclude glob patterns of paths to ignore
 * @param trust whether AST transformations found on the workspace classpath may run
 */
public record EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust) {

    /** File extensions treated as Groovy sources. */
    public static final Set<String> EXTENSIONS = Set.of("groovy", "gvy", "gy", "gsh", "gradle");
    /** Extension-less file names treated as Groovy sources. */
    public static final Set<String> FILE_NAMES = Set.of("Jenkinsfile");

    public static final EngineConfig DEFAULTS = new EngineConfig(
            List.of("src/main/groovy", "src/test/groovy", "vars"),
            List.of(),
            List.of("**/build/**", "**/.gradle/**", "**/target/**", "**/node_modules/**"),
            false);

    public EngineConfig {
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
        exclude = List.copyOf(exclude);
    }

    /**
     * Parses the JSON form of the effective configuration. Unknown keys are ignored; missing keys keep
     * their defaults. A blank document yields the defaults.
     */
    public static EngineConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULTS;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("invalid engine configuration JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("engine configuration must be a JSON object");
        }
        JsonObject workspace = objectOrEmpty(root.getAsJsonObject().get("workspace"));
        return new EngineConfig(
                strings(workspace.get("sourceRoots"), DEFAULTS.sourceRoots()),
                strings(workspace.get("classpath"), DEFAULTS.classpath()),
                strings(workspace.get("exclude"), DEFAULTS.exclude()),
                bool(workspace.get("trust"), DEFAULTS.trust()));
    }

    /** True when the file name denotes a Groovy source. */
    public static boolean isGroovyFileName(String fileName) {
        if (FILE_NAMES.contains(fileName)) {
            return true;
        }
        for (String name : FILE_NAMES) {
            if (fileName.startsWith(name + ".")) {
                return true;
            }
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(fileName.substring(dot + 1));
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static List<String> strings(JsonElement element, List<String> fallback) {
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                values.add(item.getAsString());
            }
        }
        return values;
    }

    private static boolean bool(JsonElement element, boolean fallback) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }
}
