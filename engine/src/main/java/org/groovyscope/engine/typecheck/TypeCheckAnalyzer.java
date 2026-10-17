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
package org.groovyscope.engine.typecheck;

import groovy.lang.GroovyClassLoader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.WorkspaceParentClassLoader;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * Runs Groovy's static type checker (to {@code INSTRUCTION_SELECTION}, still no bytecode) over the
 * workspace with the configured Groovy 6 checkers added to every statically typed class, and reports its
 * errors. Dynamic classes are untouched, as the checkers only apply where {@code @CompileStatic} or
 * {@code @TypeChecked} already is.
 */
public final class TypeCheckAnalyzer {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyc-typecheck";
    private static final int MAX_RETRIES = 16;
    static final String NULL_CHECKER = "groovy.typecheckers.NullChecker";
    static final String STRICT_NULL_CHECKER = NULL_CHECKER + "(strict: true)";
    static final String SQL_INJECTION_CHECKER = "groovy.typecheckers.SqlInjectionChecker";
    static final String PURITY_CHECKER = "groovy.typecheckers.PurityChecker";
    static final String MODIFIES_CHECKER = "groovy.typecheckers.ModifiesChecker";
    static final String REGEX_CHECKER = "groovy.typecheckers.RegexChecker";
    static final String FORMAT_STRING_CHECKER = "groovy.typecheckers.FormatStringChecker";
    static final String COMBINER_CHECKER = "groovy.typecheckers.CombinerChecker";

    /** The extension class names the settings ask for, in a stable order. */
    public static List<String> extensionsFor(EngineConfig.TypeCheck settings) {
        List<String> extensions = new ArrayList<>();
        if ("strict".equalsIgnoreCase(settings.nullChecker())) {
            extensions.add(STRICT_NULL_CHECKER);
        } else if (!"off".equalsIgnoreCase(settings.nullChecker())) {
            extensions.add(NULL_CHECKER);
        }
        if (settings.sqlInjection()) {
            extensions.add(SQL_INJECTION_CHECKER);
        }
        if (settings.purity()) {
            extensions.add(PURITY_CHECKER);
            extensions.add(MODIFIES_CHECKER);
        }
        if (settings.regex()) {
            extensions.add(REGEX_CHECKER);
        }
        if (settings.formatString()) {
            extensions.add(FORMAT_STRING_CHECKER);
        }
        if (settings.combiner()) {
            extensions.add(COMBINER_CHECKER);
        }
        return extensions;
    }

    /** The Groovy 6 checkers apply only when the workspace targets Groovy 6 or later. */
    public static List<String> extensionsFor(EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy) {
        return groovy.hasGroovy6Checkers() ? extensionsFor(settings) : List.of();
    }

    /** Type-check errors per URI for every document in {@code sources} (an entry per URI, possibly empty). */
    public Map<String, List<Diagnostic>> analyze(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings) {
        return analyze(sources, classpath, trust, settings, EngineConfig.Groovy.DEFAULTS, Set.of());
    }

    /**
     * Type-check errors per URI. Files in {@code skip} (the ones the main compile could not resolve; it already
     * reports them) are left out, and files whose names stop resolving are dropped and the rest retried, so one
     * broken file no longer keeps static checking from running everywhere else.
     */
    public Map<String, List<Diagnostic>> analyze(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy, Set<String> skip) {
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        remaining.keySet().removeAll(skip);
        List<String> extensions = extensionsFor(settings, groovy);
        for (int attempt = 0; attempt <= MAX_RETRIES && !remaining.isEmpty(); attempt++) {
            Pass pass = compileOnce(remaining, classpath, trust, extensions);
            if (pass.crash != null) {
                String first = remaining.keySet().iterator().next();
                diagnostics.get(first).add(new Diagnostic(first, org.groovyscope.engine.model.Range.of(0, 0, 0, 1),
                        Severity.WARNING, SOURCE, "typecheck-failed", "the type checker failed: " + pass.crash));
                return diagnostics;
            }
            if (pass.checked || pass.broken.isEmpty()) {
                for (Map.Entry<String, List<Diagnostic>> entry : pass.diagnostics.entrySet()) {
                    diagnostics.get(entry.getKey()).addAll(entry.getValue());
                }
                return diagnostics;
            }
            remaining.keySet().removeAll(pass.broken); // resolution errors: the compile pass reports those
        }
        return diagnostics;
    }

    private record Pass(boolean checked, Set<String> broken, Map<String, List<Diagnostic>> diagnostics, Exception crash) {
    }

    private Pass compileOnce(Map<String, String> sources, List<Path> classpath, boolean trust, List<String> extensions) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setSourceEncoding("UTF-8");
        configuration.setTolerance(Integer.MAX_VALUE);
        configuration.addCompilationCustomizers(new TypeCheckExtensionCustomizer(extensions));

        ClassLoader parent = new WorkspaceParentClassLoader(GroovyCompiler.class.getClassLoader());
        GroovyClassLoader loader = new GroovyClassLoader(parent, configuration);
        for (Path entry : classpath) {
            try {
                loader.addURL(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad classpath entry " + entry, e);
            }
        }
        GroovyClassLoader transformLoader = trust ? loader : new GroovyClassLoader(parent, configuration);
        CompilationUnit unit = new CompilationUnit(configuration, null, loader, transformLoader);
        Map<String, SourceUnit> units = new HashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            units.put(source.getKey(), unit.addSource(source.getKey(), source.getValue()));
        }
        try {
            unit.compile(Phases.INSTRUCTION_SELECTION);
        } catch (CompilationFailedException e) {
            // Errors are read from the collectors below.
        } catch (RuntimeException | LinkageError | AssertionError e) {
            return new Pass(false, Set.of(), Map.of(), new IllegalStateException(e.toString(), e));
        } finally {
            closeQuietly(loader);
            if (transformLoader != loader) {
                closeQuietly(transformLoader);
            }
        }
        Map<String, List<Diagnostic>> found = new HashMap<>();
        for (String uri : sources.keySet()) {
            found.put(uri, new ArrayList<>());
        }
        collect(unit.getErrorCollector(), sources, found);
        for (SourceUnit source : units.values()) {
            collect(source.getErrorCollector(), sources, found);
        }
        // Static checking runs in INSTRUCTION_SELECTION; a unit stopped earlier failed on resolution.
        boolean checked = unit.getPhase() >= Phases.INSTRUCTION_SELECTION;
        Set<String> broken = new HashSet<>();
        if (!checked) {
            for (Map.Entry<String, List<Diagnostic>> entry : found.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    broken.add(entry.getKey());
                }
            }
        }
        return new Pass(checked, broken, found, null);
    }

    /** The checker that produced a static type checking message, or {@code typecheck} for the core checker. */
    static String codeFor(String message) {
        if (message.startsWith("Potential null dereference") || message.contains("may be null")
                || message.contains("@NonNull") || message.contains("@Nullable")) {
            return "NullChecker";
        }
        if (message.startsWith("Possible SQL injection")) {
            return "SqlInjectionChecker";
        }
        if (message.startsWith("Bad regex")) {
            return "RegexChecker";
        }
        if (message.startsWith("CombinerChecker")) {
            return "CombinerChecker";
        }
        if (message.matches("^(Illegal|Missing|Unknown|Duplicate|Mismatched)\\w*Format\\w*[:].*")
                || message.startsWith("FormatStringChecker")) {
            return "FormatStringChecker";
        }
        if (message.contains("@Modifies")) {
            return "ModifiesChecker";
        }
        if (message.contains("@Pure") || message.contains("PurityChecker")) {
            return "PurityChecker";
        }
        return "typecheck";
    }

    static String stripPrefix(String message) {
        String prefix = "[Static type checking] - ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    private static void collect(ErrorCollector collector, Map<String, String> sources, Map<String, List<Diagnostic>> out) {
        if (collector == null || collector.getErrors() == null) {
            return;
        }
        for (Message message : collector.getErrors()) {
            if (!(message instanceof SyntaxErrorMessage syntax)) {
                continue;
            }
            SyntaxException cause = syntax.getCause();
            String uri = cause.getSourceLocator();
            if (uri == null || !sources.containsKey(uri)) {
                continue;
            }
            String text = stripPrefix(cause.getOriginalMessage().strip());
            Diagnostic diagnostic = new Diagnostic(uri, GroovyCompiler.rangeOf(cause), Severity.ERROR, SOURCE,
                    codeFor(text), text);
            List<Diagnostic> list = out.get(uri);
            if (!list.contains(diagnostic)) {
                list.add(diagnostic);
            }
        }
    }

    private static void closeQuietly(GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }
}
