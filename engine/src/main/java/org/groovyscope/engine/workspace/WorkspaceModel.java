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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.groovyscope.engine.config.EngineConfig;

/**
 * The files under analysis: Groovy sources discovered under the configured roots, overlaid with the
 * documents the client currently has open (whose unsaved text wins over the disk).
 */
public final class WorkspaceModel {

    private final Path root;
    private final EngineConfig config;
    private final List<PathMatcher> excludes;
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();

    public WorkspaceModel(Path root, EngineConfig config) {
        this.root = root.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.excludes = config.exclude().stream()
                .flatMap(WorkspaceModel::globVariants)
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
    }

    public Path root() {
        return root;
    }

    public EngineConfig config() {
        return config;
    }

    /** Absolute classpath entries, resolved against the root. */
    public List<Path> classpath() {
        return config.classpath().stream().map(entry -> root.resolve(entry).normalize()).toList();
    }

    public void open(String uri, String text) {
        openDocuments.put(uri, text);
    }

    public void change(String uri, String text) {
        openDocuments.put(uri, text);
    }

    public void close(String uri) {
        openDocuments.remove(uri);
    }

    public boolean isOpen(String uri) {
        return openDocuments.containsKey(uri);
    }

    /** The text of an open document, or {@code null}. */
    public String openText(String uri) {
        return openDocuments.get(uri);
    }

    /** The current text of a document: the open overlay, else the file on disk; {@code null} when neither. */
    public String text(String uri) {
        String open = openDocuments.get(uri);
        if (open != null) {
            return open;
        }
        Path path = toPath(uri);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Groovy source files on disk. Uses the configured roots that exist; when none of them exists the
     * whole workspace is walked, so unusual layouts (Gradle multi-projects, plain script folders) still work.
     */
    public List<Path> discoverSourceFiles() {
        List<Path> roots = new ArrayList<>();
        for (String relative : config.sourceRoots()) {
            Path candidate = root.resolve(relative).normalize();
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty() && Files.isDirectory(root)) {
            roots.add(root);
        }
        List<Path> files = new ArrayList<>();
        for (Path start : roots) {
            try (Stream<Path> stream = Files.walk(start)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> EngineConfig.isGroovyFileName(path.getFileName().toString()))
                        .filter(path -> !isExcluded(path))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot walk " + start, e);
            }
        }
        Collections.sort(files);
        return files;
    }

    /** Sources to compile, keyed by file URI: disk contents overlaid with open documents. */
    public Map<String, String> sources() {
        Map<String, String> sources = new TreeMap<>();
        for (Path file : discoverSourceFiles()) {
            String uri = toUri(file);
            if (!openDocuments.containsKey(uri)) {
                try {
                    sources.put(uri, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // Unreadable or non-UTF-8 files are skipped rather than failing the whole workspace.
                    System.err.println("groovyscope-engine: skipping unreadable " + file + ": " + e.getMessage());
                }
            }
        }
        for (Map.Entry<String, String> open : openDocuments.entrySet()) {
            if (isGroovyUri(open.getKey())) {
                sources.put(open.getKey(), open.getValue());
            }
        }
        return sources;
    }

    /**
     * A leading {@code **}{@code /} in a glob demands a separator before the next segment, so
     * {@code **}{@code /build/**} would not match {@code build/x.groovy} at the root. Each such pattern also
     * gets its root-anchored variant.
     */
    static Stream<String> globVariants(String glob) {
        return glob.startsWith("**/") ? Stream.of(glob, glob.substring(3)) : Stream.of(glob);
    }

    /** Exclude globs apply to paths relative to the workspace root, never to the absolute path. */
    boolean isExcluded(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(absolute);
        for (PathMatcher matcher : excludes) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGroovyUri(String uri) {
        int slash = uri.lastIndexOf('/');
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        return EngineConfig.isGroovyFileName(name);
    }

    public static String toUri(Path path) {
        return path.toUri().toString();
    }

    /** The file system path for a {@code file:} URI, or {@code null} for other schemes. */
    public static Path toPath(String uri) {
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            return Paths.get(parsed).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
