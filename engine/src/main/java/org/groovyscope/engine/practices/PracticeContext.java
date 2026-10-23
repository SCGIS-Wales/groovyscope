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
package org.groovyscope.engine.practices;

import java.util.List;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.Logging;
import org.groovyscope.engine.workspace.TestSources;

/**
 * What every practice finder needs about one document, worked out once (docs/improvement-plan.md, item 6.3): its
 * tree and text, the configuration, how the document is classified (test source, Jenkins pipeline code, Gradle build
 * script, Spock specification, logger), and the one way findings are reported, so the finders agree on all of it.
 *
 * @param profile the lint profile the document is checked with ({@code default}, {@code jenkins}, {@code gradle})
 * @param test whether the document is a test source
 * @param jenkins whether the document runs on Jenkins' CPS runtime (a Jenkinsfile, {@code vars/}, a shared library)
 * @param gradle whether the document is a Gradle build script
 * @param out where findings are collected
 */
public record PracticeContext(String uri, ModuleNode module, TextLines lines, EngineConfig config, String profile,
        boolean test, boolean jenkins, boolean gradle, List<PracticeFinder.Practice> out) {

    /** The profile names, as {@code QualityServices} assigns them. */
    public static final String JENKINS = "jenkins";
    public static final String GRADLE = "gradle";
    public static final String DEFAULT = "default";

    /**
     * The context of one document. {@code profile} may be {@code null}: it is then read from the path alone
     * (a {@code Jenkinsfile*} or {@code vars/} script is Jenkins code, {@code *.gradle} a build script).
     */
    public static PracticeContext of(String uri, ModuleNode module, String text, EngineConfig config, String profile,
            List<PracticeFinder.Practice> out) {
        String chosen = profile == null || profile.isBlank() ? profileOf(uri) : profile;
        boolean test = uri.contains("/src/test/") || uri.contains("/test/groovy/") || TestSources.isTestPath(uri);
        return new PracticeContext(uri, module, new TextLines(text), config, chosen, test, JENKINS.equals(chosen),
                GRADLE.equals(chosen), out);
    }

    /** The profile a path alone implies. */
    static String profileOf(String uri) {
        String path = uri.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("Jenkinsfile") || path.contains("/vars/")) {
            return JENKINS;
        }
        return name.endsWith(".gradle") ? GRADLE : DEFAULT;
    }

    /**
     * Whether a class of this document is test code: the document is a test source, or the class is named like a
     * test ({@code *Test}, {@code *Spec} and the like).
     */
    public boolean isTestClass(String simpleName) {
        return test || TestSources.isTestName(simpleName);
    }

    /**
     * The logging library behind {@code receiver}, or {@code null} when it is not proven to be a logger: a variable
     * of a known logger type, or the field a logging annotation ({@code @Slf4j} and the like) adds to
     * {@code enclosing} or a class around it. A receiver merely named {@code log} is not enough.
     */
    public static String loggerLibrary(Expression receiver, ClassNode enclosing) {
        ClassNode type = PracticeFinder.typeOf(receiver);
        if (type != null && Logging.LOGGER_TYPES.containsKey(type.getName())) {
            return Logging.LOGGER_TYPES.get(type.getName());
        }
        return annotatedLogger(receiver, enclosing);
    }

    /** The library of the logging annotation on {@code enclosing} (or outwards) whose field {@code receiver} is. */
    public static String annotatedLogger(Expression receiver, ClassNode enclosing) {
        if (enclosing == null || !(receiver instanceof VariableExpression variable)) {
            return null;
        }
        for (ClassNode holder = enclosing; holder != null; holder = holder.getOuterClass()) {
            for (AnnotationNode annotation : holder.getAnnotations()) {
                String library = Logging.LOGGING_ANNOTATIONS.get(annotation.getClassNode().getNameWithoutPackage());
                Expression field = annotation.getMember("value");
                String name = field instanceof ConstantExpression constant ? String.valueOf(constant.getValue()) : "log";
                if (library != null && name.equals(variable.getName())) {
                    return library;
                }
            }
        }
        return null;
    }

    /** Whether a class is a Spock specification (it extends {@code Specification}, directly or not). */
    public static boolean isSpecification(ClassNode classNode) {
        ClassNode parent = classNode.getUnresolvedSuperClass(false);
        while (parent != null) {
            if (parent.getName().endsWith("Specification")) {
                return true;
            }
            parent = parent.getUnresolvedSuperClass(false);
        }
        return false;
    }

    /** The source text of a node, or {@code null} when it has no position. */
    public String source(ASTNode node) {
        Range range = AstRanges.rangeOf(node);
        return range == null ? null : lines.slice(range);
    }

    /** An edit replacing a node's text. */
    public TextEdit replace(ASTNode node, String text) {
        return new TextEdit(AstRanges.rangeOf(node), text);
    }

    /** Reports a finding at its configured severity; nothing when the code is switched off or the node has no position. */
    public void report(String code, ASTNode node, String message, String fixTitle, List<TextEdit> fix) {
        report(code, node, message, fixTitle, fix, List.of());
    }

    /** {@link #report} with other fixes offered next to the first, for when the intent is not clear from the code. */
    public void report(String code, ASTNode node, String message, String fixTitle, List<TextEdit> fix,
            List<PracticeFinder.Alternative> alternatives) {
        Severity severity = PracticeFinder.severity(code, config);
        if (severity != null) {
            add(code, node, severity, message, fixTitle, fix, alternatives);
        }
    }

    /** {@link #report} without a fix. */
    public void report(String code, ASTNode node, String message) {
        report(code, node, message, null, List.of());
    }

    /**
     * {@link #report} with the configured severity lowered to at most {@code ceiling}: what is inferred from names
     * rather than proven from types stays information.
     */
    public void reportAtMost(Severity ceiling, String code, ASTNode node, String message) {
        reportAtMost(ceiling, code, node, message, null, List.of());
    }

    /** {@link #reportAtMost(Severity, String, ASTNode, String)} with a fix. */
    public void reportAtMost(Severity ceiling, String code, ASTNode node, String message, String fixTitle,
            List<TextEdit> fix) {
        Severity configured = PracticeFinder.severity(code, config);
        if (configured != null) {
            add(code, node, configured.ordinal() >= ceiling.ordinal() ? configured : ceiling, message, fixTitle, fix,
                    List.of());
        }
    }

    /** {@link #report} with the severity raised to at least {@code floor} (unless the code is switched off). */
    public void reportAtLeast(Severity floor, String code, ASTNode node, String message) {
        Severity configured = PracticeFinder.severity(code, config);
        if (configured != null) {
            add(code, node, configured.ordinal() <= floor.ordinal() ? configured : floor, message, null, List.of(),
                    List.of());
        }
    }

    /**
     * {@link #report} at a text range rather than a node: for what the tree does not hold, such as comments.
     * Nothing when the code is switched off.
     */
    public void report(String code, Range range, String message, String fixTitle, List<TextEdit> fix) {
        Severity severity = PracticeFinder.severity(code, config);
        if (severity != null) {
            add(code, range, severity, message, fixTitle, fix, List.of());
        }
    }

    private void add(String code, ASTNode node, Severity severity, String message, String fixTitle,
            List<TextEdit> fix, List<PracticeFinder.Alternative> alternatives) {
        add(code, AstRanges.rangeOf(node), severity, message, fixTitle, fix, alternatives);
    }

    private void add(String code, Range range, Severity severity, String message, String fixTitle,
            List<TextEdit> fix, List<PracticeFinder.Alternative> alternatives) {
        if (range == null) {
            return;
        }
        String title = PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::title).orElse(code);
        out.add(new PracticeFinder.Practice(new org.groovyscope.engine.model.Diagnostic(uri, range, severity,
                PracticeFinder.SOURCE, code, title + ": " + message), fixTitle, fix, alternatives));
    }
}
