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
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Range;

/**
 * Finds the constructs that defeat static analysis: classes without an explicit typing choice,
 * {@code def} on public API, dynamic-dispatch hooks, metaclass mutation, {@code @Grab}, runtime evaluation of
 * code, computed method names, categories, expandos, mixins, delegates, interceptors and reflective class
 * loading. Each finding says what and where, so an agent can review them before relying on
 * navigation or impact answers around them.
 */
public final class DynamicCodeFinder {

    /** A finding: its kind, where it is, and a short explanation. */
    public record Finding(String kind, Location location, String symbol, String detail) {
    }

    static final Set<String> STATIC_ANNOTATIONS = Set.of("groovy.transform.CompileStatic", "groovy.transform.TypeChecked");
    static final Set<String> DYNAMIC_ANNOTATIONS = Set.of("groovy.transform.CompileDynamic");
    /** Grape annotations by simple name: on an import they are left unresolved, so the package may be absent. */
    static final Set<String> GRAB_ANNOTATIONS = Set.of("Grab", "Grapes", "GrabConfig", "GrabResolver", "GrabExclude");
    static final Set<String> DISPATCH_HOOKS = Set.of("methodMissing", "propertyMissing", "invokeMethod",
            "getProperty", "setProperty", "$static_methodMissing", "$static_propertyMissing");
    static final Set<String> EVAL_CLASSES = Set.of("groovy.util.Eval", "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader",
            "groovy.lang.GroovyCodeSource", "groovy.text.GStringTemplateEngine", "groovy.text.SimpleTemplateEngine");
    static final Set<String> MIXIN_ANNOTATIONS = Set.of("groovy.lang.Mixin");
    static final Set<String> DELEGATE_ANNOTATIONS = Set.of("groovy.lang.Delegate");
    static final String INTERCEPTABLE = "groovy.lang.GroovyInterceptable";
    static final Set<String> EXPANDO_CLASSES = Set.of("groovy.util.Expando", "groovy.lang.Expando");

    private DynamicCodeFinder() {
    }

    /** Findings for the given documents (all indexed documents when {@code uris} is empty). */
    public static List<Finding> find(AstIndex index, Collection<String> uris) {
        List<Finding> findings = new ArrayList<>();
        Iterable<String> targets = uris == null || uris.isEmpty() ? index.uris() : uris;
        for (String uri : targets) {
            if (uri.endsWith(".java")) {
                continue; // Java has no dynamic Groovy; its classes are compiled only for resolution
            }
            for (NodeEntry entry : index.entries(uri)) {
                inspect(index, entry, findings);
            }
        }
        return findings;
    }

    /** Builder base classes: a class extending one answers calls that no declaration describes. */
    static final Set<String> BUILDER_SUPPORT = Set.of("groovy.util.BuilderSupport",
            "groovy.util.FactoryBuilderSupport");

    /**
     * Why a class relies on dynamic features the static type checker cannot follow, or {@code null} when it does not:
     * a {@code methodMissing}, {@code propertyMissing}, {@code invokeMethod}, {@code getProperty} or
     * {@code setProperty} of its own, {@code GroovyInterceptable}, {@code @Mixin}, a builder base class, and in its
     * code {@code metaClass}, an {@code Expando}, a Groovy builder ({@code new MarkupBuilder()} and the like), a
     * category ({@code use(...)}) or a computed method name. The type preview (GS077) skips such classes.
     */
    public static String dynamicFeature(ClassNode classNode) {
        for (MethodNode method : classNode.getMethods()) {
            if (DISPATCH_HOOKS.contains(method.getName()) && !method.isSynthetic()) {
                return method.getName();
            }
        }
        if (hasAnnotation(classNode, MIXIN_ANNOTATIONS)) {
            return "@Mixin";
        }
        for (ClassNode type : Members.hierarchy(classNode)) {
            if (INTERCEPTABLE.equals(type.getName())) {
                return "GroovyInterceptable";
            }
            if (BUILDER_SUPPORT.contains(type.getName())) {
                return "a builder (" + type.getNameWithoutPackage() + ")";
            }
        }
        String[] found = new String[1];
        org.codehaus.groovy.ast.ClassCodeVisitorSupport visitor =
                new org.codehaus.groovy.ast.ClassCodeVisitorSupport() {
            @Override
            protected org.codehaus.groovy.control.SourceUnit getSourceUnit() {
                return null;
            }

            @Override
            public void visitPropertyExpression(PropertyExpression expression) {
                if ("metaClass".equals(expression.getPropertyAsString())) {
                    found[0] = "metaClass";
                }
                super.visitPropertyExpression(expression);
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                String name = call.getMethodAsString();
                if (name == null) {
                    found[0] = "a computed method name";
                } else if ("setMetaClass".equals(name) || "getMetaClass".equals(name)) {
                    found[0] = "metaClass";
                } else if ("use".equals(name) && call.isImplicitThis() && isCategoryUse(call.getArguments())) {
                    found[0] = "a category (use)";
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitConstructorCallExpression(ConstructorCallExpression call) {
                ClassNode type = call.getType();
                if (type != null && EXPANDO_CLASSES.contains(type.getName())) {
                    found[0] = "Expando";
                } else if (type != null && isBuilder(type)) {
                    found[0] = "a builder (" + type.getNameWithoutPackage() + ")";
                }
                super.visitConstructorCallExpression(call);
            }
        };
        visitor.visitClass(classNode);
        return found[0];
    }

    /** A Groovy builder: a {@code groovy.*} class named {@code *Builder}, or one extending a builder base class. */
    static boolean isBuilder(ClassNode type) {
        if (type.getName().startsWith("groovy.") && type.getName().endsWith("Builder")) {
            return true;
        }
        for (ClassNode parent : Members.hierarchy(type)) {
            if (BUILDER_SUPPORT.contains(parent.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void inspect(AstIndex index, NodeEntry entry, List<Finding> out) {
        Object node = entry.node();
        if (entry.isDeclaration() && node instanceof ClassNode classNode) {
            inspectClass(index, entry, classNode, out);
        } else if (entry.isDeclaration() && node instanceof ImportNode importNode) {
            for (AnnotationNode annotation : importNode.getAnnotations()) {
                if (isGrab(annotation)) {
                    out.add(new Finding("grab", location(entry), importNode.getClassName(),
                            "@" + annotation.getClassNode().getNameWithoutPackage()
                                    + " on an import resolves a dependency at compile time; disabled in the engine"));
                }
            }
        } else if (entry.isDeclaration() && node instanceof MethodNode method) {
            inspectMethod(index, entry, method, out);
        } else if (entry.isDeclaration() && (node instanceof PropertyNode || node instanceof FieldNode)) {
            inspectMember(index, entry, (AnnotatedNode) node, out);
        } else if (entry.role() == NodeEntry.Role.PROPERTY_NAME && entry.parent() instanceof PropertyExpression property
                && "metaClass".equals(property.getPropertyAsString())) {
            out.add(new Finding("metaclass-mutation", location(entry), describe(entry),
                    "metaClass access: methods or properties added at runtime are invisible to static analysis"));
        } else if (entry.role() == NodeEntry.Role.METHOD_NAME && entry.parent() instanceof MethodCallExpression call) {
            inspectCall(entry, call, out);
        } else if (entry.role() == NodeEntry.Role.OTHER && node instanceof MethodCallExpression call
                && !(call.getMethod() instanceof ConstantExpression)) {
            out.add(new Finding("dynamic-method-name", location(entry), describe(entry),
                    "the method name is computed at runtime, so no declaration can be matched to this call"));
        } else if (entry.role() == NodeEntry.Role.CONSTRUCTOR_CALL && node instanceof ConstructorCallExpression call
                && call.getType() != null) {
            if (EVAL_CLASSES.contains(call.getType().getName())) {
                out.add(new Finding("dynamic-evaluation", location(entry), describe(entry),
                        "code evaluated at runtime through " + call.getType().getNameWithoutPackage()));
            } else if (EXPANDO_CLASSES.contains(call.getType().getName())) {
                out.add(new Finding("expando", location(entry), describe(entry),
                        "an Expando takes properties and methods added at runtime, invisible to static analysis"));
            }
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
        if (hasAnnotation(classNode, MIXIN_ANNOTATIONS)) {
            out.add(new Finding("mixin", nameLocation(index, entry, classNode), classNode.getName(),
                    "@Mixin adds the methods of other classes at runtime"));
        }
        for (ClassNode type : Members.hierarchy(classNode)) {
            if (INTERCEPTABLE.equals(type.getName())) {
                out.add(new Finding("interceptable", nameLocation(index, entry, classNode), classNode.getName(),
                        "GroovyInterceptable sends every call through invokeMethod"));
                break;
            }
        }
        for (AnnotationNode annotation : classNode.getAnnotations()) {
            if (isGrab(annotation)) {
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
            if (isGrab(annotation)) {
                out.add(new Finding("grab", nameLocation(index, entry, method), symbol,
                        "@" + annotation.getClassNode().getNameWithoutPackage() + " on a method"));
            }
        }
    }

    /** A field or property whose {@code @Delegate} forwards the delegate's methods through generated code. */
    private static void inspectMember(AstIndex index, NodeEntry entry, AnnotatedNode member, List<Finding> out) {
        boolean delegate = hasAnnotation(member, DELEGATE_ANNOTATIONS)
                || member instanceof PropertyNode property && property.getField() != null
                        && hasAnnotation(property.getField(), DELEGATE_ANNOTATIONS);
        if (delegate) {
            ClassNode owner = member.getDeclaringClass();
            String name = member instanceof PropertyNode property ? property.getName() : ((FieldNode) member).getName();
            out.add(new Finding("delegate", nameLocation(index, entry, member),
                    (owner == null ? "" : owner.getName()) + "#" + name,
                    "@Delegate forwards the methods of the delegate's type; they have no declaration in this class"));
        }
    }

    /** Calls on a METHOD_NAME entry, whose name is always a constant (a computed name is an OTHER entry). */
    private static void inspectCall(NodeEntry entry, MethodCallExpression call, List<Finding> out) {
        String name = call.getMethodAsString();
        ClassNode receiver = call.getObjectExpression() instanceof ClassExpression classExpression
                ? classExpression.getType()
                : null;
        if (receiver != null && EVAL_CLASSES.contains(receiver.getName())) {
            out.add(new Finding("dynamic-evaluation", location(entry), describe(entry),
                    receiver.getNameWithoutPackage() + "." + name + " evaluates code at runtime"));
        } else if ("execute".equals(name) && call.getObjectExpression() != null
                && !(call.getObjectExpression() instanceof VariableExpression v && v.isThisExpression())
                && mayRunProcess(call.getObjectExpression())) {
            out.add(new Finding("process-execution", location(entry), describe(entry),
                    "execute() runs an external process; its input must not come from untrusted data"));
        } else if (("invokeMethod".equals(name) || "setMetaClass".equals(name)) && !call.isImplicitThis()) {
            out.add(new Finding("metaclass-mutation", location(entry), describe(entry),
                    name + " bypasses static dispatch"));
        } else if ("enableGlobally".equals(name) && receiver != null
                && "groovy.lang.ExpandoMetaClass".equals(receiver.getName())) {
            out.add(new Finding("metaclass-mutation", location(entry), describe(entry),
                    "ExpandoMetaClass.enableGlobally() lets every class take methods added at runtime"));
        } else if ("forName".equals(name) && receiver != null && "java.lang.Class".equals(receiver.getName())) {
            out.add(new Finding("reflection", location(entry), describe(entry),
                    "Class.forName loads a class by name at runtime; its uses cannot be followed statically"));
        } else if ("use".equals(name) && call.isImplicitThis() && isCategoryUse(call.getArguments())) {
            out.add(new Finding("category-use", location(entry), describe(entry),
                    "use(Category) adds the category's methods inside the closure at runtime"));
        }
    }

    /** {@code use(SomeCategory) { ... }} or {@code use(A, B) { ... }}: classes or lists of them, then a closure. */
    static boolean isCategoryUse(Expression arguments) {
        if (!(arguments instanceof TupleExpression tuple) || tuple.getExpressions().size() < 2) {
            return false;
        }
        List<Expression> expressions = tuple.getExpressions();
        if (!(expressions.get(expressions.size() - 1) instanceof ClosureExpression)) {
            return false;
        }
        for (Expression category : expressions.subList(0, expressions.size() - 1)) {
            boolean classes = category instanceof ClassExpression
                    || category instanceof ListExpression list
                            && list.getExpressions().stream().allMatch(ClassExpression.class::isInstance);
            if (!classes) {
                return false;
            }
        }
        return true;
    }

    static boolean isGrab(AnnotationNode annotation) {
        ClassNode type = annotation.getClassNode();
        return type != null && GRAB_ANNOTATIONS.contains(type.getNameWithoutPackage())
                && (type.getPackageName() == null || "groovy.lang".equals(type.getPackageName()));
    }

    /** Types whose {@code execute()} is Groovy's process launcher (the GDK adds it to strings, lists and arrays). */
    static final Set<String> PROCESS_RECEIVERS = Set.of("java.lang.String", "groovy.lang.GString", "java.lang.Object",
            "java.lang.CharSequence", "java.util.List", "java.util.Collection", "java.util.ArrayList",
            "java.lang.String[]", "java.lang.Object[]");

    /**
     * Whether {@code receiver.execute()} can be the GDK's process launcher: a string, GString, list or array,
     * or a receiver whose type is unknown or dynamic. A receiver declared as any other class (a runner, a task)
     * calls that class's own {@code execute()}.
     */
    static boolean mayRunProcess(Expression receiver) {
        if (receiver instanceof ConstantExpression constant) {
            return constant.getValue() instanceof String;
        }
        if (receiver instanceof GStringExpression || receiver instanceof ListExpression) {
            return true;
        }
        if (receiver instanceof CastExpression cast) {
            return isProcessReceiver(cast.getType());
        }
        if (receiver instanceof ConstructorCallExpression constructor) {
            return isProcessReceiver(constructor.getType());
        }
        if (receiver instanceof VariableExpression variable) {
            Variable accessed = variable.getAccessedVariable();
            if (accessed == null || accessed.isDynamicTyped()) {
                return true;
            }
            return isProcessReceiver(accessed.getOriginType());
        }
        return true; // property chains, method results: unknown, so reported
    }

    private static boolean isProcessReceiver(ClassNode type) {
        return type == null || PROCESS_RECEIVERS.contains(type.getName())
                || type.isArray() && isProcessReceiver(type.getComponentType());
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
