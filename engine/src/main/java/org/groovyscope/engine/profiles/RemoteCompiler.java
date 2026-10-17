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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.practices.PracticeFinder;

/**
 * Compiles governed files with the runtime's own Groovy (jars the profile names, for example an older Groovy),
 * isolated from the engine's Groovy, through class generation, and reports what that compiler reports (RP050).
 * Generated classes are never loaded or run; Grape and {@code @ASTTest} stay off, and AST transformations on the
 * workspace classpath load only when the workspace is trusted.
 */
public final class RemoteCompiler {

    static final int CLASS_GENERATION = 7;

    private RemoteCompiler() {
    }

    /** RP050 findings per URI; empty when the profile names no emulation jars or they are missing. */
    public static Map<String, List<Diagnostic>> compile(Profile profile, Map<String, String> sources, List<Path> classpath,
            boolean trust, EngineConfig config) {
        Map<String, List<Diagnostic>> out = new TreeMap<>();
        Severity severity = PracticeFinder.severity("RP050", config);
        List<URL> groovy = urls(profile.target().emulationJars().stream().map(Path::of).toList());
        if (groovy.isEmpty() || sources.isEmpty() || severity == null) {
            return out;
        }
        List<URL> all = new ArrayList<>(groovy);
        all.addAll(urls(classpath));
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader runtime = new URLClassLoader(all.toArray(URL[]::new), platform);
             URLClassLoader groovyOnly = new URLClassLoader(groovy.toArray(URL[]::new), platform)) {
            Class<?> configurationType = runtime.loadClass("org.codehaus.groovy.control.CompilerConfiguration");
            Object configuration = configurationType.getConstructor().newInstance();
            configurationType.getMethod("setTolerance", int.class).invoke(configuration, Integer.MAX_VALUE);
            Class<?> loaderType = runtime.loadClass("groovy.lang.GroovyClassLoader");
            Object loader = loaderType.getConstructor(ClassLoader.class, configurationType).newInstance(runtime, configuration);
            Object transformLoader = trust ? loader
                    : loaderType.getConstructor(ClassLoader.class, configurationType).newInstance(groovyOnly, configuration);
            Class<?> unitType = runtime.loadClass("org.codehaus.groovy.control.CompilationUnit");
            Object unit = unitType.getConstructor(configurationType, CodeSource.class, loaderType, loaderType)
                    .newInstance(configuration, null, loader, transformLoader);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                unitType.getMethod("addSource", String.class, String.class).invoke(unit, source.getKey(), source.getValue());
            }
            try {
                unitType.getMethod("compile", int.class).invoke(unit, CLASS_GENERATION);
            } catch (InvocationTargetException e) {
                // compile errors are collected below
            }
            Object collector = unitType.getMethod("getErrorCollector").invoke(unit);
            Object errors = collector.getClass().getMethod("getErrors").invoke(collector);
            if (errors instanceof List<?> list) {
                for (Object message : list) {
                    report(message, sources, severity, profile, out);
                }
            }
        } catch (ReflectiveOperationException | IOException | LinkageError | RuntimeException e) {
            String first = sources.keySet().iterator().next();
            out.computeIfAbsent(first, k -> new ArrayList<>()).add(new Diagnostic(first, Range.of(0, 0, 0, 1),
                    Severity.WARNING, ProfileFinder.SOURCE, "RP050", "the emulation compile could not run: " + e));
        }
        return out;
    }

    private static void report(Object message, Map<String, String> sources, Severity severity, Profile profile,
            Map<String, List<Diagnostic>> out) throws ReflectiveOperationException {
        if (!message.getClass().getSimpleName().equals("SyntaxErrorMessage")) {
            return;
        }
        Object cause = message.getClass().getMethod("getCause").invoke(message);
        String uri = (String) cause.getClass().getMethod("getSourceLocator").invoke(cause);
        if (uri == null || !sources.containsKey(uri)) {
            return;
        }
        int line = Math.max(1, (Integer) cause.getClass().getMethod("getLine").invoke(cause)) - 1;
        int column = Math.max(1, (Integer) cause.getClass().getMethod("getStartColumn").invoke(cause)) - 1;
        String text = (String) cause.getClass().getMethod("getOriginalMessage").invoke(cause);
        out.computeIfAbsent(uri, k -> new ArrayList<>()).add(new Diagnostic(uri, Range.of(line, column, line, column + 1),
                severity, ProfileFinder.SOURCE, "RP050", "does not compile on " + profile.name()
                + (profile.target().groovy().isBlank() ? "" : " (Groovy " + profile.target().groovy() + ")") + ": "
                + text.strip()));
    }

    private static List<URL> urls(List<Path> paths) {
        List<URL> urls = new ArrayList<>();
        for (Path path : paths) {
            if (Files.exists(path)) {
                try {
                    urls.add(path.toUri().toURL());
                } catch (MalformedURLException e) {
                    // skipped: not a usable classpath entry
                }
            }
        }
        return urls;
    }
}
