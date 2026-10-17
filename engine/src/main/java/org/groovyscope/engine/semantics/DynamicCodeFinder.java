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
package org.groovyscope.engine.semantics;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Range;

/**
 * Finds the constructs that defeat static analysis: classes without an explicit typing choice,
 * {@code def} on public API, dynamic-dispatch hooks, metaclass mutation, {@code @Grab}, and runtime
 * evaluation of code. Each finding says what and where, so an agent can review them before relying on
 * navigation or impact answers around them.
 */
public final class DynamicCodeFinder {

    /** A finding: its kind, where it is, and a short explanation. */
    public record Finding(String kind, Location location, String symbol, String detail) {
    }

    static final Set<String> STATIC_ANNOTATIONS = Set.of("groovy.transform.CompileStatic", "groovy.transform.TypeChecked");
    static final Set<String> DYNAMIC_ANNOTATIONS = Set.of("groovy.transform.CompileDynamic");
    static final Set<String> GRAB_ANNOTATIONS = Set.of("groovy.lang.Grab", "groovy.lang.Grapes", "groovy.lang.GrabConfig",
            "groovy.lang.GrabResolver", "groovy.lang.GrabExclude");
    static final Set<String> DISPATCH_HOOKS = Set.of("methodMissing", "propertyMissing", "invokeMethod",
            "getProperty", "setProperty", "$static_methodMissing", "$static_propertyMissing");
    static final Set<String> EVAL_CLASSES = Set.of("groovy.util.Eval", "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader",
            "groovy.lang.GroovyCodeSource", "groovy.text.GStringTemplateEngine", "groovy.text.SimpleTemplateEngine");

    private DynamicCodeFinder() {
    }

    /** Findings for the given documents (all indexed documents when {@code uris} is empty). */
    public static List<Finding> find(AstIndex index, Collection<String> uris) {
        List<Finding> findings = new ArrayList<>();
        Iterable<String> targets = uris == null || uris.isEmpty() ? index.uris() : uris;
        for (String uri : targets) {
            for (NodeEntry entry : index.entries(uri)) {
                inspect(index, entry, findings);
            }
        }
        return findings;
    }

    private static void inspect(AstIndex index, NodeEntry entry, List<Finding> out) {
        Object node = entry.node();
        if (entry.isDeclaration() && node instanceof ClassNode classNode) {
            inspectClass(index, entry, classNode, out);
        } else if (entry.isDeclaration() && node instanceof MethodNode method) {
            inspectMethod(index, entry, method, out);
        } else if (entry.role() == NodeEntry.Role.PROPERTY_NAME && entry.parent() instanceof PropertyExpression property
                && "metaClass".equals(property.getPropertyAsString())) {
            out.add(new Finding("metaclass-mutation", location(entry), describe(entry),
                    "metaClass access: methods or properties added at runtime are invisible to static analysis"));
        } else if (entry.role() == NodeEntry.Role.METHOD_NAME && entry.parent() instanceof MethodCallExpression call) {
            inspectCall(entry, call, out);
        } else if (entry.role() == NodeEntry.Role.CONSTRUCTOR_CALL && node instanceof ConstructorCallExpression call
                && call.getType() != null && EVAL_CLASSES.contains(call.getType().getName())) {
            out.add(new Finding("dynamic-evaluation", location(entry), describe(entry),
                    "code evaluated at runtime through " + call.getType().getNameWithoutPackage()));
        }
    }

    private static void inspectClass(AstIndex index, NodeEntry entry, ClassNode classNode, List<Finding> out) {
        if (classNode.isScript() || classNode.isInterface() || classNode.isAnnotationDefinition()) {
            return;
        }
        if (!hasAnnotation(classNode, STATIC_ANNOTATIONS) && !hasAnnotation(classNode, DYNAMIC_ANNOTATIONS)) {
            out.add(new Finding("untyped-class", nameLocation(index, entry, classNode), classNode.getName(),
                    "no @CompileStatic, @TypeChecked or @CompileDynamic: dynamic dispatch by default"));
        }
        for (AnnotationNode annotation : classNode.getAnnotations()) {
            if (GRAB_ANNOTATIONS.contains(annotation.getClassNode().getName())) {
                out.add(new Finding("grab", nameLocation(index, entry, classNode), classNode.getName(),
                        "@" + annotation.getClassNode().getNameWithoutPackage() + " resolves dependencies at compile time; disabled in the engine"));
            }
        }
    }

    private static void inspectMethod(AstIndex index, NodeEntry entry, MethodNode method, List<Finding> out) {
        if (method instanceof ConstructorNode || method.isSynthetic()) {
            return;
        }
        String owner = method.getDeclaringClass() == null ? "" : method.getDeclaringClass().getName();
        String symbol = owner + "#" + method.getName();
        if (DISPATCH_HOOKS.contains(method.getName())) {
            out.add(new Finding("dynamic-dispatch-hook", nameLocation(index, entry, method), symbol,
                    method.getName() + " answers calls that no declaration describes"));
        }
        boolean publicApi = Modifier.isPublic(method.getModifiers()) && method.getDeclaringClass() != null
                && !method.getDeclaringClass().isScript() && !hasAnnotation(method.getDeclaringClass(), STATIC_ANNOTATIONS);
        if (publicApi && !DISPATCH_HOOKS.contains(method.getName())) {
            boolean dynamicReturn = method.isDynamicReturnType();
            boolean dynamicParameter = false;
            for (Parameter parameter : method.getParameters()) {
                dynamicParameter |= parameter.isDynamicTyped();
            }
            if (dynamicReturn || dynamicParameter) {
                out.add(new Finding("def-on-public-api", nameLocation(index, entry, method), symbol,
                        (dynamicReturn ? "def return type" : "def parameter") + " on a public method hides its contract"));
            }
        }
        for (AnnotationNode annotation : method.getAnnotations()) {
            if (GRAB_ANNOTATIONS.contains(annotation.getClassNode().getName())) {
                out.add(new Finding("grab", nameLocation(index, entry, method), symbol,
                        "@" + annotation.getClassNode().getNameWithoutPackage() + " on a method"));
            }
        }
    }

    private static void inspectCall(NodeEntry entry, MethodCallExpression call, List<Finding> out) {
        String name = call.getMethodAsString();
        if (name == null) {
            return;
        }
        ClassNode receiver = null;
        if (call.getObjectExpression() instanceof ClassExpression classExpression) {
            receiver = classExpression.getType();
        } else if (call.getObjectExpression() instanceof VariableExpression variable && !variable.isThisExpression()
                && variable.getAccessedVariable() == null && Character.isUpperCase(variable.getName().charAt(0))) {
            receiver = null;
        }
        if (receiver != null && EVAL_CLASSES.contains(receiver.getName())) {
            out.add(new Finding("dynamic-evaluation", location(entry), describe(entry),
                    receiver.getNameWithoutPackage() + "." + name + " evaluates code at runtime"));
        } else if ("execute".equals(name) && call.getObjectExpression() != null
                && !(call.getObjectExpression() instanceof VariableExpression v && v.isThisExpression())) {
            out.add(new Finding("process-execution", location(entry), describe(entry),
                    "execute() runs an external process; its input must not come from untrusted data"));
        } else if (("invokeMethod".equals(name) || "setMetaClass".equals(name)) && !call.isImplicitThis()) {
            out.add(new Finding("metaclass-mutation", location(entry), describe(entry),
                    name + " bypasses static dispatch"));
        }
    }

    static boolean hasAnnotation(AnnotatedNode node, Set<String> names) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (names.contains(annotation.getClassNode().getName())) {
                return true;
            }
        }
        return false;
    }

    private static Location location(NodeEntry entry) {
        return new Location(entry.uri(), entry.range());
    }

    private static Location nameLocation(AstIndex index, NodeEntry entry, AnnotatedNode declaration) {
        Range name = AstRanges.nameRange(declaration, entry.range(), index.lines(entry.uri()));
        return new Location(entry.uri(), name == null ? entry.range() : name);
    }

    private static String describe(NodeEntry entry) {
        MethodNode method = entry.enclosingMethod();
        if (method != null && method.getLineNumber() > 0) {
            return (method.getDeclaringClass() == null ? "" : method.getDeclaringClass().getName()) + "#" + method.getName();
        }
        ClassNode enclosing = entry.enclosingClass();
        return enclosing == null ? "" : enclosing.getName();
    }
}
