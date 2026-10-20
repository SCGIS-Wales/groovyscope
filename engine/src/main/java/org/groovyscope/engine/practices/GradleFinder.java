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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * Gradle build script checks, GS050 to GS052, read from the script's syntax alone (a build script is never type
 * checked, and its Gradle API types are not on the workspace classpath): the eager task API (GS050), dynamic
 * dependency versions (GS051), and repositories over plain {@code http://} or {@code mavenLocal()} (GS052). Each is
 * recognised by the DSL block it sits in ({@code dependencies}, {@code repositories}) and the call that makes it.
 */
public final class GradleFinder {

    /** Receivers through which a build script reaches its project. */
    static final List<String> PROJECT_RECEIVERS = List.of("project", "rootProject", "it", "this");
    /** A dependency notation {@code group:name:version}, optionally {@code :classifier} and {@code @extension}. */
    static final Pattern NOTATION = Pattern.compile("[^:\\s]+:[^:\\s]+:([^:@\\s]+)(:[^:@\\s]+)?(@\\S+)?");
    /** A version range such as {@code [1.0,2.0)} or {@code (,1.0]}. */
    static final Pattern RANGE = Pattern.compile("[\\[\\(\\]].*,.*[\\]\\)\\[]");

    private GradleFinder() {
    }

    /** Findings for one build script parsed or compiled on its own, as Gradle profile code. */
    public static List<PracticeFinder.Practice> find(String uri, ModuleNode module, String text, EngineConfig config) {
        List<PracticeFinder.Practice> out = new ArrayList<>();
        if (module != null && text != null && config.practices().enabled()) {
            find(PracticeContext.of(uri, module, text, config, PracticeContext.GRADLE, out));
        }
        return out;
    }

    /** Adds the findings of a build script to {@code context}; anything else is left alone. */
    static void find(PracticeContext context) {
        if (!context.gradle()) {
            return;
        }
        Visitor visitor = new Visitor(context);
        for (ClassNode classNode : context.module().getClasses()) {
            visitor.visitClass(classNode);
        }
    }

    /** Whether a version asks Gradle to pick one at resolution time: {@code 1.+}, {@code latest.*} or a range. */
    static boolean isDynamicVersion(String version) {
        String trimmed = version.strip();
        return trimmed.contains("+") || trimmed.startsWith("latest.") || RANGE.matcher(trimmed).matches();
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final PracticeContext context;
        /** Names of the DSL blocks (calls taking a closure) around the code being visited, innermost first. */
        private final Deque<String> blocks = new ArrayDeque<>();

        Visitor(PracticeContext context) {
            this.context = context;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return context.module().getContext();
        }

        private boolean within(String block) {
            return blocks.contains(block);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (name != null) {
                checkCall(call, name);
            }
            boolean block = name != null && hasClosure(call.getArguments());
            if (block) {
                blocks.push(name);
            }
            super.visitMethodCallExpression(call);
            if (block) {
                blocks.pop();
            }
        }

        private void checkCall(MethodCallExpression call, String name) {
            if ("task".equals(name) && onProject(call)) {
                context.report("GS050", call, "task creates and configures the task eagerly, in every build, even "
                        + "when it never runs; declare it with tasks.register");
            } else if ("create".equals(name) && "tasks".equals(receiverName(call.getObjectExpression()))) {
                context.report("GS050", call, "tasks.create configures the task eagerly, in every build; use "
                        + "tasks.register, which configures it only when it is needed");
            } else if ("mavenLocal".equals(name) && within("repositories") && onProject(call)) {
                context.reportAtMost(Severity.WARNING, "GS052", call, "mavenLocal() lets whatever was last installed "
                        + "on this machine shadow the published artefacts, so builds differ between machines; publish "
                        + "to a real repository, or keep mavenLocal() behind a content filter");
            }
        }

        private static boolean onProject(MethodCallExpression call) {
            return call.isImplicitThis() || PROJECT_RECEIVERS.contains(receiverName(call.getObjectExpression()));
        }

        private static String receiverName(Expression receiver) {
            if (receiver instanceof VariableExpression variable) {
                return variable.getName();
            }
            if (receiver instanceof PropertyExpression property) {
                return property.getPropertyAsString();
            }
            return null;
        }

        private static boolean hasClosure(Expression arguments) {
            if (arguments instanceof TupleExpression tuple) {
                for (Expression argument : tuple.getExpressions()) {
                    if (argument instanceof ClosureExpression) {
                        return true;
                    }
                }
            }
            return arguments instanceof ClosureExpression;
        }

        @Override
        public void visitConstantExpression(ConstantExpression constant) {
            if (constant.getValue() instanceof String text) {
                if (within("repositories") && text.toLowerCase(Locale.ROOT).startsWith("http://")) {
                    context.report("GS052", constant, "the repository " + text + " is fetched over plain http://: "
                            + "anyone on the network path can replace the artefacts; use https://");
                } else if (within("dependencies")) {
                    var notation = NOTATION.matcher(text.strip());
                    if (notation.matches() && isDynamicVersion(notation.group(1))) {
                        reportVersion(constant, "'" + text + "'");
                    }
                }
            }
            super.visitConstantExpression(constant);
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression entry) {
            if (within("dependencies") && entry.getKeyExpression() instanceof ConstantExpression key
                    && "version".equals(key.getValue()) && entry.getValueExpression() instanceof ConstantExpression value
                    && value.getValue() instanceof String version && isDynamicVersion(version)) {
                reportVersion(value, "version: '" + version + "'");
            }
            super.visitMapEntryExpression(entry);
        }

        private void reportVersion(ConstantExpression where, String what) {
            context.report("GS051", where, what + " is a dynamic version: the build resolves whatever is newest that "
                    + "day, so two builds of one commit can differ; pin the version (a platform or version catalogue "
                    + "keeps the pins in one place)");
        }
    }
}
