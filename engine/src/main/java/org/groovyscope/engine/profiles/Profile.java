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
package org.groovyscope.engine.profiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A runtime profile: rules for Groovy files that run somewhere other than where they are built (a remote
 * loader with an older Groovy, a restricted classpath, hard external contracts). Profiles are plain data supplied
 * by the user ({@code .groovyscope.yaml} {@code profiles:} or {@code ~/.groovyscope/profiles}); nothing about any
 * particular runtime is built in. See docs/profiles.md.
 *
 * @param name a label used in messages
 * @param activation which files the profile governs
 * @param target the Groovy version and checker policy of the runtime
 * @param constructs constructs whose use is blocked, unproven, discouraged or proven only elsewhere
 * @param descriptor the parameter descriptor that defines the files' inputs, when there is one
 * @param infrastructure infrastructure variable files checked against the descriptor
 * @param secrets names that hold secrets, and where they must not flow
 * @param network network calls and the methods allowed to make them
 * @param sdk hints for hand-written code that duplicates a library capability
 * @param codenarcDisable CodeNarc rules switched off for governed files
 * @param contract what the contract baseline records
 * @param protectedRegions code that automatic fixes must not touch
 * @param guidance free text for agents
 */
public record Profile(String name, Activation activation, Target target, List<Construct> constructs,
        Descriptor descriptor, Infrastructure infrastructure, Secrets secrets, Network network, List<Hint> sdk,
        List<String> codenarcDisable, Contract contract, Protected protectedRegions, String guidance) {

    /** Which files a profile governs: path globs, imported package prefixes, and an optional marker file. */
    public record Activation(List<String> files, List<String> imports, List<String> markers) {
    }

    /** The runtime's Groovy version, how Groovy 6 checkers are treated, and who compiles which file. */
    public record Target(String groovy, String groovy6Checkers, List<Label> compiledBy, List<String> emulationJars) {
    }

    /** A provenance label for files matching a glob. */
    public record Label(String files, String label) {
    }

    /**
     * A construct and its status on the runtime.
     *
     * @param status {@code blocked}, {@code unproven}, {@code discouraged} or {@code proven-elsewhere}
     */
    public record Construct(String id, String description, List<String> imports, List<String> classes,
            List<String> calls, List<String> constructors, List<String> syntax, List<String> files, String status,
            String severity, String evidence, String replacement) {
    }

    /** Where a file's declared parameters live and how code reads them. */
    public record Descriptor(String file, String parameters, String nameField, String typeField, String defaultField,
            String descriptionField, List<Accessor> accessors, Map<String, List<String>> typeMap, List<String> rawMaps,
            List<ForbiddenValue> forbiddenValues) {
    }

    /** A method that reads a declared parameter by name, optionally with a type argument. */
    public record Accessor(String method, int nameArgument, int typeArgument) {
    }

    /** A descriptor value that must not be used. */
    public record ForbiddenValue(String path, List<String> values, String message) {
    }

    /** Infrastructure variable files and what must not appear on descriptor-supplied variables. */
    public record Infrastructure(String variables, boolean noDefaults, boolean noValidation) {
    }

    /** Secret-holding names and the sinks they must not reach. */
    public record Secrets(List<String> names, boolean logs, boolean exceptions, List<String> maps, List<String> calls) {
    }

    /** Network calls ({@code Type.method} or {@code method}) and the methods allowed to make them. */
    public record Network(List<String> calls, List<String> allowedIn) {
    }

    /** A suggestion shown where code matches a pattern. */
    public record Hint(String id, List<String> imports, List<String> classes, List<String> calls,
            List<String> constructors, String suggest) {
    }

    /** What the contract baseline records. */
    public record Contract(List<String> exceptions, List<String> frozenMethods, List<String> outputCalls,
            String testSources) {
    }

    /** Methods and marked regions that fixes must leave alone. */
    public record Protected(List<String> methods, String begin, String end) {
    }

    /** True when the profile governs the document at {@code relativePath} with the given imports. */
    public boolean governs(String relativePath, List<String> fileImports) {
        Path path = Path.of(relativePath);
        for (String glob : activation.files()) {
            if (matches(glob, path)) {
                return true;
            }
        }
        for (String prefix : activation.imports()) {
            for (String imported : fileImports) {
                if (imported.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when a workspace-relative path matches a glob ({@code **}/ also matches at the root). Matchers are
     * cached; a glob with invalid syntax is reported once and matches nothing.
     */
    public static boolean matches(String glob, Path relative) {
        return org.groovyscope.engine.workspace.Globs.matches(glob, relative);
    }

    /**
     * Whether the runtime forbids static compilation: a construct the profile marks {@code blocked} lists
     * {@code CompileStatic} or {@code TypeChecked} among its imports or classes. The type preview (GS077) then
     * leaves governed files alone, since the advice it leads to cannot be taken.
     */
    public boolean blocksStaticCompilation() {
        for (Construct construct : constructs) {
            if (!"blocked".equalsIgnoreCase(construct.status())) {
                continue;
            }
            List<String> names = new java.util.ArrayList<>(construct.imports() == null ? List.of()
                    : construct.imports());
            names.addAll(construct.classes() == null ? List.of() : construct.classes());
            for (String name : names) {
                if (name != null && (name.contains("CompileStatic") || name.contains("TypeChecked"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The provenance label for a file, or {@code null}. */
    public String compiledBy(String relativePath) {
        for (Label label : target.compiledBy()) {
            if (matches(label.files(), Path.of(relativePath))) {
                return label.label();
            }
        }
        return null;
    }

    // ---- JSON ----

    /** Parses one profile from the JSON the Python front end hands over. */
    public static Profile fromJson(JsonObject json) {
        JsonObject activation = object(json, "activation");
        JsonObject target = object(json, "target");
        List<Label> labels = new ArrayList<>();
        for (JsonElement element : array(target, "compiledBy")) {
            JsonObject label = element.getAsJsonObject();
            labels.add(new Label(text(label, "files", ""), text(label, "label", "")));
        }
        List<Construct> constructs = new ArrayList<>();
        for (JsonElement element : array(json, "constructs")) {
            JsonObject c = element.getAsJsonObject();
            constructs.add(new Construct(text(c, "id", ""), text(c, "description", ""), strings(c, "imports"),
                    strings(c, "classes"), strings(c, "calls"), strings(c, "constructors"), strings(c, "syntax"),
                    strings(c, "files"), text(c, "status", "unproven"), text(c, "severity", null),
                    text(c, "evidence", ""), text(c, "replacement", "")));
        }
        Descriptor descriptor = null;
        if (json.has("descriptor") && json.get("descriptor").isJsonObject()) {
            JsonObject d = json.getAsJsonObject("descriptor");
            List<Accessor> accessors = new ArrayList<>();
            for (JsonElement element : array(d, "accessors")) {
                JsonObject a = element.getAsJsonObject();
                accessors.add(new Accessor(text(a, "method", ""), integer(a, "nameArgument", 0),
                        integer(a, "typeArgument", -1)));
            }
            Map<String, List<String>> typeMap = new LinkedHashMap<>();
            JsonObject types = object(d, "typeMap");
            for (Map.Entry<String, JsonElement> entry : types.entrySet()) {
                typeMap.put(entry.getKey(), strings(types, entry.getKey()));
            }
            List<ForbiddenValue> forbidden = new ArrayList<>();
            for (JsonElement element : array(d, "forbiddenValues")) {
                JsonObject f = element.getAsJsonObject();
                forbidden.add(new ForbiddenValue(text(f, "path", ""), strings(f, "values"), text(f, "message", "")));
            }
            descriptor = new Descriptor(text(d, "file", ""), text(d, "parameters", "parameters"),
                    text(d, "nameField", "name"), text(d, "typeField", "type"), text(d, "defaultField", "default"),
                    text(d, "descriptionField", "description"), accessors, typeMap, strings(d, "rawMaps"), forbidden);
        }
        JsonObject infra = object(json, "infrastructure");
        JsonObject secrets = object(json, "secrets");
        JsonObject network = object(json, "network");
        List<Hint> hints = new ArrayList<>();
        for (JsonElement element : array(json, "sdk")) {
            JsonObject h = element.getAsJsonObject();
            hints.add(new Hint(text(h, "id", ""), strings(h, "imports"), strings(h, "classes"), strings(h, "calls"),
                    strings(h, "constructors"), text(h, "suggest", "")));
        }
        JsonObject contract = object(json, "contract");
        JsonObject protectedRegions = object(json, "protected");
        return new Profile(text(json, "name", "profile"),
                new Activation(strings(activation, "files"), strings(activation, "imports"), strings(activation, "markers")),
                new Target(text(target, "groovy", ""), text(target, "groovy6Checkers", "on"), labels,
                        strings(target, "emulationJars")),
                constructs, descriptor,
                new Infrastructure(text(infra, "variables", ""), bool(infra, "noDefaults", false),
                        bool(infra, "noValidation", false)),
                new Secrets(strings(secrets, "names"), bool(secrets, "logs", true), bool(secrets, "exceptions", true),
                        strings(secrets, "maps"), strings(secrets, "calls")),
                new Network(strings(network, "calls"), strings(network, "allowedIn")),
                hints, strings(object(json, "codenarc"), "disable"),
                new Contract(strings(contract, "exceptions"), strings(contract, "frozenMethods"),
                        strings(contract, "outputCalls"), text(contract, "testSources", "**/src/test/**")),
                new Protected(strings(protectedRegions, "methods"), text(protectedRegions, "begin", ""),
                        text(protectedRegions, "end", "")),
                text(json, "guidance", ""));
    }

    static JsonObject object(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static JsonArray array(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    static List<String> strings(JsonObject json, String key) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(json, key)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return List.copyOf(values);
    }

    static String text(JsonObject json, String key, String fallback) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt()
                : fallback;
    }

    static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : fallback;
    }
}
