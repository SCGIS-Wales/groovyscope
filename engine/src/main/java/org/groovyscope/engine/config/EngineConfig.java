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
 * @param lint CodeNarc settings
 * @param typecheck Groovy 6 type-checker settings
 * @param format formatter settings
 * @param javaSources whether {@code .java} files under the source roots join the compile, so Groovy code that
 *     uses Java classes of the same project resolves (they are never linted, formatted or reported on)
 */
public record EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
        Lint lint, TypeCheck typecheck, Format format, boolean javaSources) {

    /** File extensions treated as Groovy sources. */
    public static final Set<String> EXTENSIONS = Set.of("groovy", "gvy", "gy", "gsh", "gradle");
    /** Extension-less file names treated as Groovy sources. */
    public static final Set<String> FILE_NAMES = Set.of("Jenkinsfile");
    /** Extension of Java sources compiled alongside Groovy in joint projects. */
    public static final String JAVA_EXTENSION = "java";

    /**
     * CodeNarc settings.
     *
     * @param ruleset path of the default profile, or {@code null} for the bundled defensive profile
     * @param jenkinsRuleset path of the Jenkins profile, or {@code null} for the bundled one
     * @param failOnPriority {@code check} fails on violations with this priority or more severe (1 is worst)
     * @param publish whether lint findings are pushed as LSP diagnostics
     */
    public record Lint(String ruleset, String jenkinsRuleset, int failOnPriority, boolean publish) {
        public static final Lint DEFAULTS = new Lint(null, null, 2, true);
    }

    /**
     * Groovy 6 type-checker settings, applied to classes that already carry {@code @CompileStatic} or
     * {@code @TypeChecked}.
     *
     * @param nullChecker {@code off}, {@code lenient} or {@code strict}
     * @param sqlInjection whether to add the SQL injection checker
     * @param purity whether to add the purity and modifies checkers
     */
    public record TypeCheck(String nullChecker, boolean sqlInjection, boolean purity) {
        public static final TypeCheck DEFAULTS = new TypeCheck("strict", true, false);
    }

    /**
     * Formatter settings.
     *
     * @param engine {@code basic} (bundled whitespace and CodeNarc-driven fixes) or {@code greclipse} (not bundled)
     * @param indentSize spaces per indentation level
     * @param maxBlankLines consecutive blank lines allowed
     * @param removeSemicolons remove unnecessary trailing semicolons
     */
    public record Format(String engine, int indentSize, int maxBlankLines, boolean removeSemicolons) {
        public static final Format DEFAULTS = new Format("basic", 4, 1, true);
    }

    public static final EngineConfig DEFAULTS = new EngineConfig(
            List.of("src/main/groovy", "src/test/groovy", "src/main/java", "src/test/java", "vars"),
            List.of(),
            List.of("**/build/**", "**/.gradle/**", "**/target/**", "**/node_modules/**"),
            false,
            Lint.DEFAULTS,
            TypeCheck.DEFAULTS,
            Format.DEFAULTS,
            true);

    public EngineConfig {
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
        exclude = List.copyOf(exclude);
        lint = lint == null ? Lint.DEFAULTS : lint;
        typecheck = typecheck == null ? TypeCheck.DEFAULTS : typecheck;
        format = format == null ? Format.DEFAULTS : format;
    }

    /** Convenience for callers that only care about workspace settings. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust) {
        this(sourceRoots, classpath, exclude, trust, Lint.DEFAULTS, TypeCheck.DEFAULTS, Format.DEFAULTS, true);
    }

    /** All settings except {@code javaSources}, which stays on. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, true);
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
        JsonObject document = root.getAsJsonObject();
        JsonObject workspace = objectOrEmpty(document.get("workspace"));
        JsonObject lint = objectOrEmpty(document.get("lint"));
        JsonObject typecheck = objectOrEmpty(document.get("typecheck"));
        JsonObject format = objectOrEmpty(document.get("format"));
        return new EngineConfig(
                strings(workspace.get("sourceRoots"), DEFAULTS.sourceRoots()),
                strings(workspace.get("classpath"), DEFAULTS.classpath()),
                strings(workspace.get("exclude"), DEFAULTS.exclude()),
                bool(workspace.get("trust"), DEFAULTS.trust()),
                new Lint(
                        string(lint.get("ruleset"), null),
                        string(lint.get("jenkinsRuleset"), null),
                        integer(lint.get("failOnPriority"), Lint.DEFAULTS.failOnPriority()),
                        bool(lint.get("publish"), Lint.DEFAULTS.publish())),
                new TypeCheck(
                        string(typecheck.get("nullChecker"), TypeCheck.DEFAULTS.nullChecker()),
                        bool(typecheck.get("sqlInjection"), TypeCheck.DEFAULTS.sqlInjection()),
                        bool(typecheck.get("purity"), TypeCheck.DEFAULTS.purity())),
                new Format(
                        string(format.get("engine"), Format.DEFAULTS.engine()),
                        integer(format.get("indentSize"), Format.DEFAULTS.indentSize()),
                        integer(format.get("maxBlankLines"), Format.DEFAULTS.maxBlankLines()),
                        bool(format.get("removeSemicolons"), Format.DEFAULTS.removeSemicolons())),
                bool(workspace.get("javaSources"), DEFAULTS.javaSources()));
    }

    /** True when the file name denotes a Java source ({@code Foo.java}). */
    public static boolean isJavaFileName(String fileName) {
        return fileName.endsWith("." + JAVA_EXTENSION) && fileName.length() > JAVA_EXTENSION.length() + 1;
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

    private static String string(JsonElement element, String fallback) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int integer(JsonElement element, int fallback) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        return fallback;
    }

    private static boolean bool(JsonElement element, boolean fallback) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }
}
