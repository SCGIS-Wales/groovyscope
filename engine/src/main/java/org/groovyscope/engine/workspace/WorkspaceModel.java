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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.profiles.Contracts;
import org.groovyscope.engine.profiles.Profile;

/**
 * The files under analysis: Groovy sources discovered under the configured roots, overlaid with the
 * documents the client currently has open (whose unsaved text wins over the disk).
 *
 * <p>Several connections may share one model, so each open document is reference-counted: one connection
 * closing a document does not drop the overlay another connection still has open.
 */
public final class WorkspaceModel {

    private final Path root;
    private final EngineConfig config;
    private final List<String> excludes;
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();
    private final Map<String, Integer> openCounts = new HashMap<>();
    private volatile Path realRoot;

    public WorkspaceModel(Path root, EngineConfig config) {
        this.root = root.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.excludes = config.exclude().stream().flatMap(WorkspaceModel::globVariants).toList();
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

    /** Opens a document (or opens it once more, from another connection) with the given text. */
    public void open(String uri, String text) {
        synchronized (openCounts) {
            openCounts.merge(uri, 1, Integer::sum);
            openDocuments.put(uri, text);
        }
    }

    public void change(String uri, String text) {
        synchronized (openCounts) {
            openCounts.putIfAbsent(uri, 1);
            openDocuments.put(uri, text);
        }
    }

    /** Releases one opening of a document; its overlay goes once no connection has it open. */
    public void close(String uri) {
        synchronized (openCounts) {
            Integer count = openCounts.get(uri);
            if (count == null || count <= 1) {
                openCounts.remove(uri);
                openDocuments.remove(uri);
            } else {
                openCounts.put(uri, count - 1);
            }
        }
    }

    public boolean isOpen(String uri) {
        return openDocuments.containsKey(uri);
    }

    /** The text of an open document, or {@code null}. */
    public String openText(String uri) {
        return openDocuments.get(uri);
    }

    /**
     * The current text of a document: the open overlay, else the file on disk; {@code null} when neither, and for
     * files outside the workspace root (only documents an editor opened may live elsewhere).
     */
    public String text(String uri) {
        String open = openDocuments.get(uri);
        if (open != null) {
            return open;
        }
        Path path = toPath(uri);
        if (path == null || !Files.isRegularFile(path) || !contains(path)) {
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
        return discover(false);
    }

    /** {@link #discoverSourceFiles()} plus, with {@code workspace.javaSources}, the Java files under the same roots. */
    public List<Path> discoverCompileFiles() {
        return discover(config.javaSources());
    }

    private List<Path> discover(boolean withJava) {
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
        long limit = config.maxFileBytes();
        Set<Path> files = new LinkedHashSet<>();
        for (Path start : roots) {
            files.addAll(FileWalker.files(start, root, excludes, Set.of(), (path, attributes) -> {
                String name = path.getFileName().toString();
                if (!EngineConfig.isGroovyFileName(name) && !(withJava && EngineConfig.isJavaFileName(name))) {
                    return false;
                }
                if (isExcluded(path)) {
                    return false;
                }
                if (limit > 0 && attributes.size() > limit) {
                    FileWalker.reportOnce("size:" + path, "skipping " + path + ": " + attributes.size()
                            + " bytes is over workspace.maxFileBytes (" + limit + ")");
                    return false;
                }
                return true;
            }));
        }
        List<Path> sorted = new ArrayList<>(files);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * Files outside the compile set whose edits change analysis results: the contract baseline and, with runtime
     * profiles, the parameter descriptors and infrastructure variable files the profiles name.
     */
    public List<Path> profileInputs() {
        List<Path> inputs = new ArrayList<>();
        Path baseline = root.resolve(Contracts.BASELINE);
        if (Files.isRegularFile(baseline)) {
            inputs.add(baseline);
        }
        Set<String> descriptorNames = new HashSet<>();
        List<String> variableGlobs = new ArrayList<>();
        for (Profile profile : config.profiles()) {
            if (profile.descriptor() != null && !profile.descriptor().file().isBlank()) {
                descriptorNames.add(profile.descriptor().file());
            }
            if (profile.infrastructure() != null && !profile.infrastructure().variables().isBlank()) {
                variableGlobs.add(profile.infrastructure().variables());
            }
        }
        if (descriptorNames.isEmpty() && variableGlobs.isEmpty() || !Files.isDirectory(root)) {
            return inputs;
        }
        inputs.addAll(FileWalker.files(root, root, excludes, Set.of(".terraform"), (path, attributes) -> {
            if (descriptorNames.contains(path.getFileName().toString())) {
                return true;
            }
            Path relative = root.relativize(path);
            return variableGlobs.stream().anyMatch(glob -> Globs.matches(glob, relative));
        }));
        return inputs;
    }

    /**
     * A cheap summary of what analysis reads from disk: every compile file's path, size and modification time,
     * plus the {@link #profileInputs()}. It changes when a file is added, removed or edited outside the editor (an
     * agent's write tool, a checkout), or when the contract baseline or a profile's descriptor changes.
     */
    public String diskFingerprint() {
        StringBuilder text = new StringBuilder();
        List<Path> files = new ArrayList<>(discoverCompileFiles());
        files.addAll(profileInputs());
        for (Path file : files) {
            text.append(file).append('|');
            try {
                text.append(Files.size(file)).append('|').append(Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                text.append("unreadable");
            }
            text.append('\n');
        }
        return Integer.toHexString(text.toString().hashCode()) + ":" + text.length();
    }

    /** Groovy documents keyed by file URI: disk contents overlaid with open documents. Lint and format use these. */
    public Map<String, String> sources() {
        return read(discoverSourceFiles());
    }

    /**
     * Everything the compiler sees: {@link #sources()} plus Java files of a joint Groovy/Java project when
     * {@code workspace.javaSources} is on. Groovy's parser reads Java syntax, so Java classes become resolvable.
     */
    public Map<String, String> compileSources() {
        return read(discoverCompileFiles());
    }

    private Map<String, String> read(List<Path> files) {
        Map<String, String> sources = new TreeMap<>();
        for (Path file : files) {
            String uri = toUri(file);
            if (!openDocuments.containsKey(uri)) {
                try {
                    sources.put(uri, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // Unreadable or non-UTF-8 files are skipped rather than failing the whole workspace.
                    FileWalker.reportOnce("read:" + file, "skipping unreadable " + file + ": " + e.getMessage());
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
        for (String glob : excludes) {
            if (Globs.matcher(glob).map(matcher -> matcher.matches(relative)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** True when a {@code file:} URI names a path inside the workspace root (after resolving symbolic links). */
    public boolean contains(String uri) {
        Path path = uri == null ? null : toPath(uri);
        return path != null && contains(path);
    }

    /** True when a path lies inside the workspace root, comparing real paths so links cannot lead out of it. */
    public boolean contains(Path path) {
        Path top = realRoot;
        if (top == null) {
            top = realPath(root);
            realRoot = top;
        }
        return realPath(path).startsWith(top);
    }

    /**
     * The real path of a file, or for one that does not exist, the real path of its nearest existing ancestor with
     * the rest appended.
     */
    static Path realPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }

    /** True for a {@code .java} document. */
    public static boolean isJavaUri(String uri) {
        int slash = uri.lastIndexOf('/');
        return EngineConfig.isJavaFileName(slash >= 0 ? uri.substring(slash + 1) : uri);
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
