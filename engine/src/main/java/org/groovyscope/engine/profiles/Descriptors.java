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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.workspace.FileWalker;

/**
 * Reads the parameter descriptor that defines a governed file's inputs, and the infrastructure variable files
 * that receive them, with line positions so diagnostics and navigation can point into them.
 */
public final class Descriptors {

    /** One declared parameter. */
    public record Parameter(String name, String type, JsonElement defaultValue, String description, Location location) {
    }

    /** A parsed descriptor: its file, its parameters by name, and the raw document. */
    public record Document(Path file, Map<String, Parameter> parameters, JsonObject json) {
    }

    /** One infrastructure variable block. */
    public record Variable(String name, Location location, Location defaultLine, Location validationLine) {
    }

    private static final Pattern VARIABLE = Pattern.compile("^\\s*variable\\s+\"([^\"]+)\"\\s*\\{");

    private Descriptors() {
    }

    /** The descriptor for a governed file: the nearest {@code descriptor.file} in its directory or above, within the root. */
    public static Optional<Document> forFile(Profile.Descriptor descriptor, Path root, Path file) {
        if (descriptor == null || descriptor.file().isBlank() || file == null) {
            return Optional.empty();
        }
        Path directory = file.toAbsolutePath().normalize().getParent();
        Path top = root.toAbsolutePath().normalize();
        while (directory != null && directory.startsWith(top)) {
            Path candidate = directory.resolve(descriptor.file());
            if (Files.isRegularFile(candidate)) {
                return read(descriptor, candidate);
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    /** Directory names the descriptor and variable walks never enter. */
    static final Set<String> SKIPPED_DIRECTORIES = Set.of("build", ".terraform", "node_modules");

    /** Every descriptor file under the root (for workspace-wide checks). */
    public static List<Document> all(Profile.Descriptor descriptor, Path root) {
        return all(descriptor, root, List.of());
    }

    /**
     * Every descriptor file under the root, without entering directories the workspace exclude globs cover; an
     * unreadable or malformed descriptor is skipped and the walk goes on.
     */
    public static List<Document> all(Profile.Descriptor descriptor, Path root, List<String> excludes) {
        List<Document> documents = new ArrayList<>();
        if (descriptor == null || descriptor.file().isBlank()) {
            return documents;
        }
        Path top = root.toAbsolutePath().normalize();
        for (Path path : FileWalker.files(top, top, excludes, SKIPPED_DIRECTORIES,
                (file, attributes) -> file.getFileName().toString().equals(descriptor.file()))) {
            read(descriptor, path).ifPresent(documents::add);
        }
        return documents;
    }

    static Optional<Document> read(Profile.Descriptor descriptor, Path file) {
        String text;
        JsonObject json;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            json = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            return Optional.empty();
        }
        String[] lines = text.split("\n", -1);
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        JsonElement container = at(json, descriptor.parameters());
        String uri = file.toUri().toString();
        if (container != null && container.isJsonArray()) {
            for (JsonElement element : container.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    String name = Profile.text(item, descriptor.nameField(), null);
                    if (name != null) {
                        parameters.put(name, parameter(name, item, descriptor, uri, lines,
                                Pattern.compile("\"" + Pattern.quote(descriptor.nameField()) + "\"\\s*:\\s*\"" + Pattern.quote(name) + "\"")));
                    }
                }
            }
        } else if (container != null && container.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : container.getAsJsonObject().entrySet()) {
                JsonObject item = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : new JsonObject();
                parameters.put(entry.getKey(), parameter(entry.getKey(), item, descriptor, uri, lines,
                        Pattern.compile("\"" + Pattern.quote(entry.getKey()) + "\"\\s*:")));
            }
        }
        return Optional.of(new Document(file, parameters, json));
    }

    private static Parameter parameter(String name, JsonObject item, Profile.Descriptor descriptor, String uri,
            String[] lines, Pattern locate) {
        return new Parameter(name, Profile.text(item, descriptor.typeField(), ""), item.get(descriptor.defaultField()),
                Profile.text(item, descriptor.descriptionField(), ""), find(uri, lines, locate));
    }

    /** The value at a dotted path, or {@code null}. */
    public static JsonElement at(JsonObject json, String path) {
        JsonElement current = json;
        if (path == null || path.isBlank()) {
            return current;
        }
        for (String part : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    /** The first line matching a pattern, as a location covering the match. */
    static Location find(String uri, String[] lines, Pattern pattern) {
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                return new Location(uri, Range.of(i, matcher.start(), i, matcher.end()));
            }
        }
        return new Location(uri, Range.of(0, 0, 0, 1));
    }

    /** A descriptor value's location: the line of its last path segment as a JSON key. */
    public static Location locate(Path file, String path) {
        try {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            String key = path.substring(path.lastIndexOf('.') + 1);
            return find(file.toUri().toString(), lines, Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:"));
        } catch (IOException e) {
            return new Location(file.toUri().toString(), Range.of(0, 0, 0, 1));
        }
    }

    /** Variable blocks in the infrastructure files matching the glob under the root. */
    public static List<Variable> variables(Profile.Infrastructure infrastructure, Path root) {
        return variables(infrastructure, root, List.of());
    }

    /**
     * Variable blocks in the infrastructure files matching the glob under the root, without entering directories
     * the workspace exclude globs cover; an unreadable file is reported once and skipped.
     */
    public static List<Variable> variables(Profile.Infrastructure infrastructure, Path root, List<String> excludes) {
        List<Variable> variables = new ArrayList<>();
        if (infrastructure == null || infrastructure.variables().isBlank()) {
            return variables;
        }
        Path top = root.toAbsolutePath().normalize();
        List<Path> files = FileWalker.files(top, top, excludes, SKIPPED_DIRECTORIES,
                (path, attributes) -> Profile.matches(infrastructure.variables(), top.relativize(path)));
        for (Path file : files) {
            try {
                variables.addAll(parseVariables(file));
            } catch (IOException | UncheckedIOException e) {
                FileWalker.reportOnce("read:" + file, "skipping unreadable " + file + ": " + e.getMessage());
            }
        }
        return variables;
    }

    static List<Variable> parseVariables(Path file) throws IOException {
        List<Variable> variables = new ArrayList<>();
        String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        String uri = file.toUri().toString();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = VARIABLE.matcher(lines[i]);
            if (!matcher.find()) {
                continue;
            }
            int depth = 0;
            Location defaultLine = null;
            Location validationLine = null;
            int end = i;
            for (int j = i; j < lines.length; j++) {
                String line = lines[j];
                if (j > i && depth == 1) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("default") && trimmed.substring(7).strip().startsWith("=")) {
                        defaultLine = new Location(uri, Range.of(j, line.indexOf("default"), j, line.length()));
                    } else if (trimmed.startsWith("validation") && trimmed.substring(10).strip().startsWith("{")) {
                        validationLine = new Location(uri, Range.of(j, line.indexOf("validation"), j, line.length()));
                    }
                }
                for (char c : line.toCharArray()) {
                    depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                }
                end = j;
                if (depth <= 0 && j >= i) {
                    break;
                }
            }
            variables.add(new Variable(matcher.group(1), new Location(uri, Range.of(i, matcher.start(1), i, matcher.end(1))),
                    defaultLine, validationLine));
            i = end;
        }
        return variables;
    }

    /** Whether an infrastructure variable carries a descriptor parameter (same name ignoring case, _ and -). */
    public static boolean sameName(String variable, String parameter) {
        return normalise(variable).equals(normalise(parameter));
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
