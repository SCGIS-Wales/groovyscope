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
import java.util.List;
import java.util.Map;
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
    static final String NULL_CHECKER = "groovy.typecheckers.NullChecker";
    static final String SQL_INJECTION_CHECKER = "groovy.typecheckers.SqlInjectionChecker";
    static final String PURITY_CHECKER = "groovy.typecheckers.PurityChecker";
    static final String MODIFIES_CHECKER = "groovy.typecheckers.ModifiesChecker";

    /** The extension class names the settings ask for, in a stable order. */
    public static List<String> extensionsFor(EngineConfig.TypeCheck settings) {
        List<String> extensions = new ArrayList<>();
        if (!"off".equalsIgnoreCase(settings.nullChecker())) {
            extensions.add(NULL_CHECKER);
        }
        if (settings.sqlInjection()) {
            extensions.add(SQL_INJECTION_CHECKER);
        }
        if (settings.purity()) {
            extensions.add(PURITY_CHECKER);
            extensions.add(MODIFIES_CHECKER);
        }
        return extensions;
    }

    /** Type-check errors per URI for every document in {@code sources} (an entry per URI, possibly empty). */
    public Map<String, List<Diagnostic>> analyze(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings) {
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        if (sources.isEmpty()) {
            return diagnostics;
        }
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setSourceEncoding("UTF-8");
        configuration.setTolerance(Integer.MAX_VALUE);
        configuration.addCompilationCustomizers(new TypeCheckExtensionCustomizer(extensionsFor(settings)));

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
        } catch (RuntimeException e) {
            String first = sources.keySet().iterator().next();
            diagnostics.get(first).add(new Diagnostic(first, org.groovyscope.engine.model.Range.of(0, 0, 0, 1),
                    Severity.WARNING, SOURCE, "typecheck-failed", "the type checker failed: " + e));
            return diagnostics;
        } finally {
            closeQuietly(loader);
            if (transformLoader != loader) {
                closeQuietly(transformLoader);
            }
        }
        collect(unit.getErrorCollector(), sources, diagnostics);
        for (SourceUnit source : units.values()) {
            collect(source.getErrorCollector(), sources, diagnostics);
        }
        return diagnostics;
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
            Diagnostic diagnostic = new Diagnostic(uri, GroovyCompiler.rangeOf(cause), Severity.ERROR, SOURCE,
                    "typecheck", cause.getOriginalMessage());
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
