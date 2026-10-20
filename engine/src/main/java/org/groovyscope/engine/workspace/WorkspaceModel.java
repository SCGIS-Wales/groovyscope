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
    private volatile EngineConfig config;
    private volatile List<String> excludes;
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

    /**
     * Replaces the configuration (classpath, excludes, every setting) in place, so a re-exported classpath or an
     * edited {@code .groovyscope.yaml} applies without restarting the daemon. Open documents are kept.
     */
    public void reconfigure(EngineConfig replacement) {
        EngineConfig next = Objects.requireNonNull(replacement, "replacement");
        this.excludes = next.exclude().stream().flatMap(WorkspaceModel::globVariants).toList();
        this.config = next;
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
            long limit = config.maxFileBytes();
            if (limit > 0 && Files.size(path) > limit) {
                // The compile skips it; format and code actions must not read it either.
                return null;
            }
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

    /**
     * The Gradle build scripts ({@code *.gradle}, the root's and every subproject's) that the compile does not
     * already read, keyed by URI with their current text. They are never compiled with the workspace: their Gradle
     * API imports would not resolve, and every subproject's {@code build.gradle} would be the same script class. Lint
     * and the practices read them on their own. Source folders ({@code src}) are not entered.
     */
    public Map<String, String> buildScripts() {
        Map<String, String> scripts = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return scripts;
        }
        Set<Path> compiled = new HashSet<>(discoverSourceFiles());
        for (Path file : FileWalker.files(root, root, excludes, Set.of("src"), (path, attributes) ->
                path.getFileName().toString().endsWith(".gradle") && !isExcluded(path))) {
            if (!compiled.contains(file)) {
                String uri = toUri(file);
                String text = text(uri);
                if (text != null) {
                    scripts.put(uri, text);
                }
            }
        }
        return scripts;
    }

    /** The source root folders that exist inside the workspace (a shared library's {@code src/} included). */
    public List<Path> sourceRootPaths() {
        List<Path> roots = new ArrayList<>();
        for (String relative : config.sourceRoots()) {
            Path candidate = root.resolve(relative).normalize();
            if (contains(candidate) && Files.isDirectory(candidate) && !roots.contains(candidate)) {
                roots.add(candidate);
            }
        }
        Path librarySources = root.resolve("src");
        if (isSharedLibrary(root) && Files.isDirectory(librarySources) && !roots.contains(librarySources)) {
            roots.add(librarySources);
        }
        return roots;
    }

    /**
     * A Jenkins shared library: {@code vars/} beside {@code src/} or {@code resources/}, where {@code src/} holds
     * packages directly (Jenkins puts it on the classpath). A {@code src/main} or {@code src/test} folder means a
     * Gradle or Maven project that merely has a {@code vars/} folder.
     */
    public static boolean isSharedLibrary(Path folder) {
        Path sources = folder.resolve("src");
        boolean buildLayout = Files.isDirectory(sources.resolve("main")) || Files.isDirectory(sources.resolve("test"));
        return Files.isDirectory(folder.resolve("vars")) && !buildLayout
                && (Files.isDirectory(sources) || Files.isDirectory(folder.resolve("resources")));
    }

    /** True when {@code file} lies in the {@code src/} of a shared library at or below {@code root}. */
    public static boolean inSharedLibrarySource(Path file, Path root) {
        Path stop = root.toAbsolutePath().normalize();
        for (Path folder = file.toAbsolutePath().normalize().getParent(); folder != null; folder = folder.getParent()) {
            Path parent = folder.getParent();
            if (folder.getFileName() != null && folder.getFileName().toString().equals("src") && parent != null
                    && isSharedLibrary(parent)) {
                return true;
            }
            if (folder.equals(stop)) {
                return false;
            }
        }
        return false;
    }

    private List<Path> discover(boolean withJava) {
        List<Path> roots = new ArrayList<>();
        Path librarySources = root.resolve("src");
        if (isSharedLibrary(root) && Files.isDirectory(librarySources)) {
            roots.add(librarySources); // a shared library's classes, whatever workspace.sourceRoots says
        }
        for (String relative : config.sourceRoots()) {
            Path candidate = root.resolve(relative).normalize();
            if (!contains(candidate)) {
                FileWalker.reportOnce("root:" + relative, "source root " + relative + " lies outside the workspace "
                        + root + "; ignored");
                continue;
            }
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
        if (Files.isDirectory(root.resolve("vars"))) {
            // A shared library's own pipeline (Jenkinsfile, Jenkinsfile.prod) sits at the root, outside every root.
            try (java.util.stream.Stream<Path> top = Files.list(root)) {
                top.filter(path -> path.getFileName().toString().startsWith("Jenkinsfile"))
                        .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !isExcluded(path))
                        .forEach(files::add);
            } catch (java.io.IOException e) {
                FileWalker.reportOnce("list:" + root, "cannot list " + root + ": " + e.getMessage());
            }
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
        return fingerprint(discoverCompileFiles());
    }

    /** The compile's sources and the disk fingerprint they were read under, from one walk of the tree. */
    public record CompileInput(Map<String, String> sources, String fingerprint) {
    }

    /** {@link #compileSources()} and {@link #diskFingerprint()} from a single discovery of the files. */
    public CompileInput compileInput() {
        List<Path> files = discoverCompileFiles();
        String fingerprint = fingerprint(files);
        return new CompileInput(read(files, config.javaSources()), fingerprint);
    }

    private String fingerprint(List<Path> compileFiles) {
        fingerprintsTaken.incrementAndGet();
        StringBuilder text = new StringBuilder();
        List<Path> files = new ArrayList<>(compileFiles);
        files.addAll(profileInputs());
        // A rebuilt module jar or classes folder on the classpath changes what resolves, too.
        files.addAll(classpath());
        for (Path file : files) {
            text.append(file).append('|');
            try {
                text.append(Files.size(file)).append('|').append(Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                text.append("unreadable");
            }
            text.append('\n');
        }
        return fingerprintOf(text.toString());
    }

    /** 64 bits of the SHA-256 of {@code text}, as 16 hex digits (a 32-bit hash collides too easily on big trees). */
    public static String fingerprintOf(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger fingerprintsTaken = new java.util.concurrent.atomic.AtomicInteger();

    /** How many disk fingerprints this model has taken (each one walks the tree; for specs). */
    public int fingerprintsTaken() {
        return fingerprintsTaken.get();
    }

    /** Groovy documents keyed by file URI: disk contents overlaid with open documents. Lint and format use these. */
    public Map<String, String> sources() {
        return read(discoverSourceFiles(), false);
    }

    /** The URIs {@link #sources()} would carry, without reading any file: disk files plus open Groovy documents. */
    public Set<String> sourceUris() {
        Set<String> uris = new java.util.TreeSet<>();
        for (Path file : discoverSourceFiles()) {
            uris.add(toUri(file));
        }
        for (String open : openDocuments.keySet()) {
            if (isGroovyUri(open) && admitted(open)) {
                uris.add(open);
            }
        }
        return uris;
    }

    /**
     * Everything the compiler sees: {@link #sources()} plus Java files of a joint Groovy/Java project when
     * {@code workspace.javaSources} is on. Groovy's parser reads Java syntax, so Java classes become resolvable.
     */
    public Map<String, String> compileSources() {
        return read(discoverCompileFiles(), config.javaSources());
    }

    /** Files the last {@link #read} left out because the workspace is over its budget; empty when none. */
    private volatile List<Path> omitted = List.of();

    /** The files the budget ({@code workspace.maxFiles}, {@code workspace.maxTotalBytes}) left out of the last read. */
    public List<Path> omitted() {
        return omitted;
    }

    private Map<String, String> read(List<Path> files, boolean withJava) {
        long limit = config.maxFileBytes();
        int maxFiles = config.budget().maxFiles();
        long maxTotal = config.budget().maxTotalBytes();
        long total = 0;
        List<Path> leftOut = new ArrayList<>();
        Map<String, String> sources = new TreeMap<>();
        for (Path file : files) {
            String uri = toUri(file);
            if (!openDocuments.containsKey(uri)) {
                try {
                    long size = Files.size(file);
                    if ((maxFiles > 0 && sources.size() >= maxFiles) || (maxTotal > 0 && total + size > maxTotal)) {
                        // Over budget: the rest is left out (and reported once) rather than read until the heap goes.
                        leftOut.add(file);
                        continue;
                    }
                    total += size;
                    sources.put(uri, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // Unreadable or non-UTF-8 files are skipped rather than failing the whole workspace.
                    FileWalker.reportOnce("read:" + file, "skipping unreadable " + file + ": " + e.getMessage());
                }
            }
        }
        for (Map.Entry<String, String> open : openDocuments.entrySet()) {
            String uri = open.getKey();
            if (!isGroovyUri(uri) && !(withJava && isJavaUri(uri)) || !admitted(uri)) {
                continue;
            }
            if (limit > 0 && open.getValue().length() > limit) {
                FileWalker.reportOnce("size:" + uri, "skipping open " + uri + ": " + open.getValue().length()
                        + " chars is over workspace.maxFileBytes (" + limit + ")");
                continue;
            }
                sources.put(uri, open.getValue());
        }
        if (!leftOut.isEmpty() && omitted.isEmpty()) {
            org.groovyscope.engine.log.EngineLog.warn("%d file(s) left out: the workspace is over its budget (workspace.maxFiles %d, workspace.maxTotalBytes %d)",
                    leftOut.size(), maxFiles, maxTotal);
        }
        omitted = List.copyOf(leftOut);
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

    /**
     * Whether an open document joins the analysis: only a {@code file:} document inside the workspace. Anything else
     * (another folder, an {@code untitled:} buffer, a library source the editor opened) is left out, reported once.
     */
    boolean admitted(String uri) {
        if (contains(uri)) {
            return true;
        }
        FileWalker.reportOnce("open:" + uri, "open document " + uri + " is not a file inside the workspace " + root
                + "; not analysed");
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
