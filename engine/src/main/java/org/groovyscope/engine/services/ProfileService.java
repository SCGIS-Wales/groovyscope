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
package org.groovyscope.engine.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.CodeAction;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.profiles.Contracts;
import org.groovyscope.engine.profiles.Descriptors;
import org.groovyscope.engine.profiles.Profile;
import org.groovyscope.engine.profiles.ProfileNavigation;
import org.groovyscope.engine.profiles.Profiles;
import org.groovyscope.engine.profiles.RemoteCompiler;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The runtime profiles of a workspace: which profile governs a document, its contract items and baseline, its
 * parameters for navigation and hover, the runtime-emulation compile, the regions fixes must not touch and how the
 * Groovy 6 checkers are treated in governed files.
 */
final class ProfileService {

    /** Most emulation results kept; each holds the findings of one profile's governed sources. */
    static final int EMULATION_CACHE_LIMIT = 8;

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final Map<String, Set<String>> seamCache = new ConcurrentHashMap<>();
    private volatile long seamVersion = -1;
    private final Map<String, Map<String, List<Diagnostic>>> emulationCache = lru(EMULATION_CACHE_LIMIT);

    ProfileService(WorkspaceModel workspace, LanguageServices navigation) {
        this.workspace = workspace;
        this.navigation = navigation;
    }

    /** The runtime profile governing a document, if any. */
    Optional<Profile> governing(String uri) {
        if (workspace.config().profiles().isEmpty()) {
            return Optional.empty();
        }
        return Profiles.governing(workspace.config().profiles(), workspace.root(), uri,
                navigation.snapshot().result().modules().get(uri));
    }

    /** Methods tests stub through instance dispatch, for the profile's test sources (cached per profile and snapshot). */
    Set<String> seams(Profile profile) {
        Snapshot snapshot = navigation.snapshot();
        if (seamVersion != snapshot.version()) {
            seamCache.clear();
            seamVersion = snapshot.version();
        }
        String key = profile.name() + "@" + snapshot.version();
        Set<String> cached = seamCache.get(key);
        if (cached == null) {
            cached = Contracts.seams(profile, workspace.root(), snapshot.result().modules());
            seamCache.put(key, cached);
        }
        return cached;
    }

    /** The contract items of a governed document. */
    Map<String, String> contractItems(Profile profile, String uri) {
        Path file = WorkspaceModel.toPath(uri);
        return Contracts.extract(profile, navigation.snapshot().result().modules().get(uri),
                profile.descriptor() == null ? null
                        : Descriptors.forFile(profile.descriptor(), workspace.root(), file).orElse(null),
                seams(profile));
    }

    /** Contract items for every governed document, keyed by workspace-relative path (what the baseline stores). */
    Map<String, Map<String, String>> contracts() {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (String uri : workspace.sources().keySet()) {
            governing(uri).ifPresent(profile -> result.put(relative(uri), contractItems(profile, uri)));
        }
        return result;
    }

    /** What the governing profile says about a document: target, provenance, descriptor, seams, protection. */
    Map<String, Object> profileStatus(String uri) {
        Map<String, Object> status = new LinkedHashMap<>();
        Optional<Profile> governing = governing(uri);
        status.put("governed", governing.isPresent());
        if (governing.isEmpty()) {
            return status;
        }
        Profile profile = governing.get();
        String relative = relative(uri);
        status.put("profile", profile.name());
        status.put("targetGroovy", profile.target().groovy());
        status.put("groovy6Checkers", profile.target().groovy6Checkers());
        status.put("compiledBy", profile.compiledBy(relative));
        Map<String, String> items = contractItems(profile, uri);
        if (profile.descriptor() != null) {
            Descriptors.forFile(profile.descriptor(), workspace.root(), WorkspaceModel.toPath(uri)).ifPresent(d -> {
                status.put("descriptor", workspace.root().relativize(d.file()).toString());
                Map<String, String> parameters = new LinkedHashMap<>();
                d.parameters().values().forEach(p -> parameters.put(p.name(), p.type()));
                status.put("parameters", parameters);
            });
        }
        status.put("contractItems", items.size());
        status.put("seams", items.keySet().stream().filter(k -> k.startsWith("seam:")).map(k -> k.substring(5)).toList());
        List<Map<String, String>> constructs = new ArrayList<>();
        for (Profile.Construct construct : profile.constructs()) {
            if (construct.files().isEmpty() || construct.files().stream()
                    .anyMatch(glob -> Profile.matches(glob, Path.of(relative)))) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", construct.id());
                entry.put("status", construct.status());
                if (!construct.evidence().isBlank()) {
                    entry.put("evidence", construct.evidence());
                }
                if (!construct.replacement().isBlank()) {
                    entry.put("replacement", construct.replacement());
                }
                constructs.add(entry);
            }
        }
        status.put("constructs", constructs);
        status.put("protectedMethods", profile.protectedRegions().methods());
        status.put("baselineRecorded", Contracts.baseline(workspace.root(), relative) != null);
        status.put("guidance", profile.guidance());
        return status;
    }

    /** The parameter under a position in a governed document, with its profile, when the position is on one. */
    private Optional<Map.Entry<Profile, String>> parameterAt(String uri, Position position) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return Optional.empty();
        }
        return ProfileNavigation.parameterAt(governing.get(), uri, navigation.snapshot().result().modules().get(uri), position)
                .map(name -> Map.entry(governing.get(), name));
    }

    /** Descriptor and infrastructure declarations of the parameter under a position (empty when none). */
    List<Location> parameterDeclarations(String uri, Position position) {
        return parameterAt(uri, position).map(found -> ProfileNavigation.declarations(found.getKey(), workspace.root(),
                WorkspaceModel.toPath(uri), found.getValue())).orElse(List.of());
    }

    /** Every reader of the parameter under a position, plus its declarations when asked. */
    List<Location> parameterReferences(String uri, Position position, boolean includeDeclaration) {
        Optional<Map.Entry<Profile, String>> found = parameterAt(uri, position);
        if (found.isEmpty()) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>(
                ProfileNavigation.readers(found.get().getKey(), governedModules(found.get().getKey()), found.get().getValue()));
        if (includeDeclaration) {
            locations.addAll(parameterDeclarations(uri, position));
        }
        return locations;
    }

    /** Hover text for the parameter under a position, if any. */
    Optional<String> parameterHover(String uri, Position position) {
        return parameterAt(uri, position).flatMap(found -> ProfileNavigation.hover(found.getKey(), workspace.root(),
                WorkspaceModel.toPath(uri), found.getValue(),
                ProfileNavigation.readers(found.getKey(), governedModules(found.getKey()), found.getValue()).size()));
    }

    private Map<String, ModuleNode> governedModules(Profile profile) {
        Map<String, ModuleNode> modules = new TreeMap<>();
        for (Map.Entry<String, ModuleNode> entry : navigation.snapshot().result().modules().entrySet()) {
            if (governing(entry.getKey()).filter(p -> p == profile).isPresent()) {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        return modules;
    }

    /** A map that keeps its {@code limit} most recently used entries (not thread-safe; callers lock it). */
    static <K, V> Map<K, V> lru(int limit) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }

    /** Number of cached emulation results (for tests). */
    int emulationCacheSize() {
        synchronized (emulationCache) {
            return emulationCache.size();
        }
    }

    /** The runtime-emulation compile (RP050) of governed targets, per profile, cached by the sources' content. */
    Map<String, List<Diagnostic>> emulate(List<String> targets) {
        Map<String, List<Diagnostic>> result = new TreeMap<>();
        Map<String, String> sources = workspace.sources();
        for (Profile profile : workspace.config().profiles()) {
            if (profile.target().emulationJars().isEmpty()) {
                continue;
            }
            Map<String, String> governed = new TreeMap<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                if (governing(source.getKey()).filter(p -> p == profile).isPresent()) {
                    governed.put(source.getKey(), source.getValue());
                }
            }
            String key = profile.name() + ":" + QualityServices.hash(String.join("\u0000", governed.values())
                    + governed.keySet());
            Map<String, List<Diagnostic>> compiled;
            synchronized (emulationCache) {
                compiled = emulationCache.get(key);
            }
            if (compiled == null) {
                // Compiled outside the lock: an emulation compile can take seconds.
                compiled = RemoteCompiler.compile(profile, governed, workspace.classpath(), workspace.config().trust(),
                        workspace.config(), workspace.root());
                if (!couldNotRun(compiled)) {
                    synchronized (emulationCache) {
                        emulationCache.put(key, compiled);
                    }
                }
            }
            for (Map.Entry<String, List<Diagnostic>> entry : compiled.entrySet()) {
                if (targets.contains(entry.getKey())) {
                    result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                }
            }
        }
        return result;
    }

    /** True when an emulation result only says the compile could not run (worth retrying, so never cached). */
    static boolean couldNotRun(Map<String, List<Diagnostic>> compiled) {
        return compiled.values().stream().flatMap(List::stream)
                .anyMatch(d -> d.message().startsWith(RemoteCompiler.COULD_NOT_RUN));
    }

    /** A document's path relative to the workspace root, with forward slashes. */
    String relative(String uri) {
        Path file = WorkspaceModel.toPath(uri);
        Path top = workspace.root().toAbsolutePath().normalize();
        return file == null ? uri : top.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * In files a runtime profile governs, Groovy 6 checker findings are advisory (information) or dropped, as the
     * profile says: the runtime may not run those checkers at all.
     */
    List<Diagnostic> checkerPolicy(String uri, List<Diagnostic> typed) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return typed;
        }
        String policy = governing.get().target().groovy6Checkers();
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : typed) {
            boolean checker = !"typecheck".equals(diagnostic.code());
            if (!checker || "on".equalsIgnoreCase(policy)) {
                kept.add(diagnostic);
            } else if ("advisory".equalsIgnoreCase(policy)) {
                kept.add(new Diagnostic(diagnostic.uri(), diagnostic.range(), Diagnostic.Severity.INFORMATION,
                        diagnostic.source(), diagnostic.code(), "advisory on " + governing.get().name() + ": "
                        + diagnostic.message()));
            }
        }
        return kept;
    }

    /** Actions whose edits stay outside the protected regions of the governing profile. */
    List<CodeAction> unprotected(String uri, String text, List<CodeAction> actions) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return actions;
        }
        List<Range> ranges = Profiles.protectedRanges(governing.get(),
                navigation.snapshot().result().modules().get(uri), new TextLines(text));
        if (ranges.isEmpty()) {
            return actions;
        }
        List<CodeAction> kept = new ArrayList<>();
        for (CodeAction action : actions) {
            if (action.edits().stream().noneMatch(edit -> Profiles.touchesProtected(edit, ranges))) {
                kept.add(action);
            }
        }
        return kept;
    }
}
