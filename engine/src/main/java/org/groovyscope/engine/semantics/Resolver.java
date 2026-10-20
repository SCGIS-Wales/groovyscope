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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Confidence;

/**
 * Maps indexed nodes to the declarations they refer to.
 *
 * <p>The compiler (through {@code CANONICALIZATION}) resolves classes, locals, parameters and fields.
 * Method and property targets on dynamic receivers are matched by name and arity on the receiver's
 * declared type when it is known; otherwise every workspace declaration of that name is a candidate and the
 * confidence is {@link Confidence#DYNAMIC_UNKNOWN}. The engine never promotes a guess.
 */
public final class Resolver {

    private final AstIndex index;
    // Shared by every request thread of a snapshot (LSP workers, the diagnostics thread, call graph building).
    private final Map<NodeEntry, Resolution> cache = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<ASTNode, ClassNode> variableTypes = new IdentityHashMap<>();

    public Resolver(AstIndex index) {
        this.index = index;
        for (NodeEntry entry : index.declarations()) {
            if (entry.node() instanceof VariableExpression variable && entry.parent() instanceof DeclarationExpression declaration) {
                ClassNode inferred = inferDeclaredType(variable, declaration, entry.enclosingClass());
                if (inferred != null) {
                    variableTypes.put(variable, inferred);
                }
            }
        }
    }

    public AstIndex index() {
        return index;
    }

    /** Resolves an entry, caching the answer for the life of the snapshot. */
    public Optional<Resolution> resolve(NodeEntry entry) {
        synchronized (cache) {
            if (cache.containsKey(entry)) {
                return Optional.ofNullable(cache.get(entry));
            }
        }
        Resolution resolution;
        try {
            resolution = compute(entry);
        } catch (LinkageError e) {
            // A classpath type references a class missing from the configured classpath: leave this entry
            // unresolved rather than failing the whole request (call graph, references, impact).
            resolution = null;
        }
        cache.put(entry, resolution);
        return Optional.ofNullable(resolution);
    }

    private Resolution compute(NodeEntry entry) {
        ASTNode node = entry.node();
        return switch (entry.role()) {
            case DECLARATION -> declarationOf(node);
            case TYPE_USAGE -> node instanceof ClassNode type ? classDeclaration(type) : null;
            case VARIABLE_USAGE -> node instanceof VariableExpression variable ? variable(variable, entry) : null;
            case METHOD_NAME -> entry.parent() instanceof MethodCallExpression call ? methodCall(call, entry) : null;
            case PROPERTY_NAME -> entry.parent() instanceof PropertyExpression property ? property(property, entry) : null;
            case CONSTRUCTOR_CALL -> node instanceof ConstructorCallExpression call ? constructorCall(call, entry) : null;
            case STATIC_CALL -> node instanceof StaticMethodCallExpression call ? staticCall(call) : null;
            case OTHER -> null;
        };
    }

    private Resolution declarationOf(ASTNode node) {
        if (node instanceof ImportNode importNode) {
            ClassNode type = importNode.getType();
            return type == null ? null : classDeclaration(type);
        }
        if (node instanceof FieldNode field) {
            return Resolution.of(canonicalField(field), Confidence.STATIC);
        }
        if (node instanceof PackageNode) {
            return null;
        }
        return Resolution.of(node, Confidence.STATIC);
    }

    /**
     * The workspace class a type refers to; classes from the JDK or classpath resolve to themselves. A name
     * the compiler could not resolve is matched against workspace classes by simple name, with inferred
     * confidence, so navigation survives a missing classpath.
     */
    public Resolution classDeclaration(ClassNode type) {
        if (type == null) {
            return null;
        }
        ClassNode target = type.redirect();
        if (target.isArray()) {
            target = target.getComponentType().redirect();
        }
        if (ClassHelper.isPrimitiveType(target)) {
            return null;
        }
        Optional<ClassNode> workspace = index.classNamed(target.getName());
        if (workspace.isPresent()) {
            return Resolution.of(workspace.get(), Confidence.STATIC);
        }
        if (Members.isKnown(target)) {
            return Resolution.of(target, Confidence.STATIC);
        }
        List<ClassNode> bySimpleName = index.classesWithSimpleName(simpleName(target.getName()));
        if (bySimpleName.size() == 1) {
            return Resolution.of(bySimpleName.get(0), Confidence.INFERRED);
        }
        if (bySimpleName.size() > 1) {
            return new Resolution(new ArrayList<>(bySimpleName), Confidence.DYNAMIC_UNKNOWN);
        }
        return null;
    }

    static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /**
     * A type usable for member lookup: the class itself when the compiler resolved it, otherwise the single
     * workspace class with the same simple name. {@code null} when nothing is known about it.
     */
    public ClassNode usable(ClassNode type) {
        if (type == null) {
            return null;
        }
        ClassNode target = type.redirect();
        if (target.isArray() || ClassHelper.isPrimitiveType(target) || ClassHelper.isDynamicTyped(target)
                || ClassHelper.isObjectType(target)) {
            return null;
        }
        if (Members.isKnown(target)) {
            return target;
        }
        Optional<ClassNode> exact = index.classNamed(target.getName());
        if (exact.isPresent()) {
            return exact.get();
        }
        List<ClassNode> bySimpleName = index.classesWithSimpleName(simpleName(target.getName()));
        return bySimpleName.size() == 1 ? bySimpleName.get(0) : null;
    }

    private Resolution variable(VariableExpression expression, NodeEntry entry) {
        if (expression.isThisExpression() || expression.isSuperExpression()) {
            ClassNode enclosing = entry.enclosingClass();
            if (enclosing == null) {
                return null;
            }
            ClassNode target = expression.isSuperExpression() ? enclosing.getSuperClass() : enclosing;
            return target == null ? null : classDeclaration(target);
        }
        Variable accessed = expression.getAccessedVariable();
        if (accessed instanceof VariableExpression declared) {
            return Resolution.of(declared, Confidence.STATIC);
        }
        if (accessed instanceof Parameter parameter) {
            return Resolution.of(parameter, Confidence.STATIC);
        }
        if (accessed instanceof FieldNode field) {
            return Resolution.of(canonicalField(field), Confidence.STATIC);
        }
        if (accessed instanceof PropertyNode property) {
            return Resolution.of(property, Confidence.STATIC);
        }
        return dynamicVariable(expression.getName(), entry);
    }

    private Resolution dynamicVariable(String name, NodeEntry entry) {
        if (entry.enclosingClass() != null) {
            List<ASTNode> members = Members.membersNamed(entry.enclosingClass(), name);
            if (!members.isEmpty()) {
                return Resolution.of(canonical(members.get(0)), Confidence.INFERRED);
            }
        }
        List<ASTNode> byName = workspaceMembersNamed(name);
        return byName.isEmpty() ? null : new Resolution(byName, Confidence.DYNAMIC_UNKNOWN);
    }

    private Resolution methodCall(MethodCallExpression call, NodeEntry entry) {
        String name = call.getMethodAsString();
        if (name == null) {
            return null;
        }
        int count = Members.argumentCount(call.getArguments());
        if (call.isImplicitThis() && entry.enclosingClosure() != null) {
            return closureCall(name, count, entry);
        }
        Receiver receiver = receiverOf(call.getObjectExpression(), call.isImplicitThis(), entry);
        if (receiver.type != null) {
            List<MethodNode> candidates = Members.accepting(Members.methodsNamed(receiver.type, name), count);
            if (candidates.size() == 1) {
                return Resolution.of(candidates.get(0), receiver.exact ? Confidence.STATIC : Confidence.INFERRED);
            }
            if (candidates.size() > 1) {
                return new Resolution(new ArrayList<>(candidates), Confidence.INFERRED);
            }
            List<ASTNode> closures = Members.membersNamed(receiver.type, name);
            if (!closures.isEmpty()) {
                return Resolution.of(canonical(closures.get(0)), Confidence.INFERRED);
            }
            if (receiver.exact && !receiver.type.isPrimaryClassNode()) {
                // A known classpath type without such a method: probably a GDK extension or a typo.
                return null;
            }
        }
        List<ASTNode> byName = workspaceMethodsNamed(name, count);
        return byName.isEmpty() ? null : new Resolution(byName, Confidence.DYNAMIC_UNKNOWN);
    }

    /**
     * An implicit-this call inside a closure goes to the closure's owner (the enclosing class) unless its delegate
     * answers first: a delegate known from a {@code @DelegatesTo} on the parameter that receives the closure is
     * looked up as well. One of them declaring the method gives an inferred answer; both declaring it is ambiguous
     * (the resolve strategy decides at run time), so the answer is dynamic and a rename refuses.
     */
    private Resolution closureCall(String name, int count, NodeEntry entry) {
        List<ASTNode> owner = new ArrayList<>();
        ClassNode enclosing = entry.enclosingClass();
        if (enclosing != null) {
            owner.addAll(Members.accepting(Members.methodsNamed(enclosing, name), count));
            if (owner.isEmpty()) {
                Members.membersNamed(enclosing, name).forEach(member -> owner.add(canonical(member)));
            }
        }
        List<ASTNode> delegated = new ArrayList<>();
        for (ClassNode delegate : delegateTypes(entry.enclosingClosure())) {
            for (MethodNode method : Members.accepting(Members.methodsNamed(delegate, name), count)) {
                if (!delegated.contains(method)) {
                    delegated.add(method);
                }
            }
        }
        if (!owner.isEmpty() && !delegated.isEmpty()) {
            List<ASTNode> both = new ArrayList<>(owner);
            both.addAll(delegated);
            return new Resolution(both, Confidence.DYNAMIC_UNKNOWN);
        }
        List<ASTNode> chosen = delegated.isEmpty() ? owner : delegated;
        if (!chosen.isEmpty()) {
            return new Resolution(chosen, Confidence.INFERRED);
        }
        List<ASTNode> byName = workspaceMethodsNamed(name, count);
        return byName.isEmpty() ? null : new Resolution(byName, Confidence.DYNAMIC_UNKNOWN);
    }

    /** The {@code @DelegatesTo} types of a closure and of the closures around it, innermost first. */
    private List<ClassNode> delegateTypes(ClosureExpression innermost) {
        List<ClassNode> types = new ArrayList<>();
        ClosureExpression closure = innermost;
        while (closure != null) {
            NodeEntry closureEntry = closureEntries().get(closure);
            if (closureEntry == null) {
                break;
            }
            for (ClassNode delegate : delegatesOf(closure, closureEntry.parent())) {
                if (!types.contains(delegate)) {
                    types.add(delegate);
                }
            }
            closure = closureEntry.enclosingClosure();
        }
        return types;
    }

    /** The types the {@code @DelegatesTo} of the parameter receiving {@code closure} in {@code call} names. */
    private List<ClassNode> delegatesOf(ClosureExpression closure, ASTNode call) {
        Expression arguments;
        List<ASTNode> targets;
        if (call instanceof MethodCallExpression methodCall) {
            NodeEntry name = methodNameEntries().get(methodCall);
            Resolution resolution = name == null ? null : resolve(name).orElse(null);
            targets = resolution == null ? List.of() : resolution.candidates();
            arguments = methodCall.getArguments();
        } else if (call instanceof StaticMethodCallExpression staticCall) {
            Resolution resolution = staticCall(staticCall);
            targets = resolution == null ? List.of() : resolution.candidates();
            arguments = staticCall.getArguments();
        } else {
            return List.of();
        }
        int position = -1;
        if (arguments instanceof org.codehaus.groovy.ast.expr.TupleExpression tuple) {
            for (int i = 0; i < tuple.getExpressions().size(); i++) {
                if (tuple.getExpression(i) == closure) {
                    position = i;
                }
            }
        }
        List<ClassNode> types = new ArrayList<>();
        for (ASTNode target : targets) {
            if (!(target instanceof MethodNode method) || method.getParameters().length == 0 || position < 0) {
                continue;
            }
            Parameter[] parameters = method.getParameters();
            Parameter receiving = parameters[Math.min(position, parameters.length - 1)];
            for (org.codehaus.groovy.ast.AnnotationNode annotation : receiving.getAnnotations()) {
                if (DELEGATES_TO.equals(annotation.getClassNode().getName())
                        && annotation.getMember("value") instanceof ClassExpression value) {
                    ClassNode delegate = usable(value.getType());
                    if (delegate != null && !types.contains(delegate)) {
                        types.add(delegate);
                    }
                }
            }
        }
        return types;
    }

    private static final String DELEGATES_TO = "groovy.lang.DelegatesTo";

    private volatile Map<ClosureExpression, NodeEntry> closures;
    private volatile Map<MethodCallExpression, NodeEntry> methodNames;

    /** Every closure's own entry (its parent is the call that receives it), built on first use. */
    private Map<ClosureExpression, NodeEntry> closureEntries() {
        Map<ClosureExpression, NodeEntry> built = closures;
        if (built == null) {
            built = new IdentityHashMap<>();
            for (NodeEntry candidate : index.allEntries()) {
                if (candidate.node() instanceof ClosureExpression closure) {
                    built.put(closure, candidate);
                }
            }
            closures = built;
        }
        return built;
    }

    /** The method name entry of every call, so a call's target can be resolved from the call, built on first use. */
    private Map<MethodCallExpression, NodeEntry> methodNameEntries() {
        Map<MethodCallExpression, NodeEntry> built = methodNames;
        if (built == null) {
            built = new IdentityHashMap<>();
            for (NodeEntry candidate : index.allEntries()) {
                if (candidate.role() == NodeEntry.Role.METHOD_NAME && candidate.parent() instanceof MethodCallExpression call) {
                    built.put(call, candidate);
                }
            }
            methodNames = built;
        }
        return built;
    }

    private Resolution property(PropertyExpression expression, NodeEntry entry) {
        String name = expression.getPropertyAsString();
        if (name == null) {
            return null;
        }
        Receiver receiver = receiverOf(expression.getObjectExpression(), expression.isImplicitThis(), entry);
        if (receiver.type != null) {
            List<ASTNode> members = Members.membersNamed(receiver.type, name);
            if (!members.isEmpty()) {
                return Resolution.of(canonical(members.get(0)), receiver.exact ? Confidence.STATIC : Confidence.INFERRED);
            }
            if (receiver.exact && !receiver.type.isPrimaryClassNode()) {
                return null;
            }
        }
        List<ASTNode> byName = workspaceMembersNamed(name);
        return byName.isEmpty() ? null : new Resolution(byName, Confidence.DYNAMIC_UNKNOWN);
    }

    private Resolution constructorCall(ConstructorCallExpression call, NodeEntry entry) {
        ClassNode type = call.isSpecialCall() ? entry.enclosingClass() : call.getType();
        if (call.isSuperCall() && entry.enclosingClass() != null) {
            type = entry.enclosingClass().getSuperClass();
        }
        ClassNode target = usable(type);
        if (target == null) {
            return type == null ? null : classDeclaration(type);
        }
        List<MethodNode> constructors = new ArrayList<>();
        for (ConstructorNode constructor : target.getDeclaredConstructors()) {
            if (Members.accepts(constructor, Members.argumentCount(call.getArguments()))) {
                constructors.add(constructor);
            }
        }
        if (constructors.size() == 1) {
            return Resolution.of(constructors.get(0), Confidence.STATIC);
        }
        if (constructors.size() > 1) {
            return new Resolution(new ArrayList<>(constructors), Confidence.INFERRED);
        }
        return classDeclaration(target);
    }

    private Resolution staticCall(StaticMethodCallExpression call) {
        List<MethodNode> candidates = Members.accepting(Members.methodsNamed(call.getOwnerType(), call.getMethod()),
                Members.argumentCount(call.getArguments()));
        if (candidates.size() == 1) {
            return Resolution.of(candidates.get(0), Confidence.STATIC);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return new Resolution(new ArrayList<>(candidates), Confidence.INFERRED);
    }

    /** The static type of an expression, when the engine can tell, with whether it is exact. */
    public Receiver receiverOf(Expression expression, boolean implicitThis, NodeEntry entry) {
        if (implicitThis || expression == null || expression instanceof VariableExpression v && v.isThisExpression()) {
            return enclosing(entry);
        }
        if (expression instanceof VariableExpression variable) {
            if (variable.isSuperExpression()) {
                ClassNode enclosing = entry.enclosingClass();
                return new Receiver(enclosing == null ? null : enclosing.getSuperClass(), true);
            }
            Variable accessed = variable.getAccessedVariable();
            if (accessed instanceof VariableExpression declared) {
                ClassNode inferred = usable(variableTypes.get(declared));
                if (inferred != null) {
                    return new Receiver(inferred, false);
                }
                return typedReceiver(declared.getOriginType());
            }
            if (accessed instanceof Parameter parameter) {
                return typedReceiver(parameter.getOriginType());
            }
            if (accessed instanceof FieldNode field) {
                return typedReceiver(field.getOriginType());
            }
            if (accessed instanceof PropertyNode property) {
                return typedReceiver(property.getOriginType());
            }
            if (accessed instanceof DynamicVariable && entry.enclosingClass() != null) {
                List<ASTNode> members = Members.membersNamed(entry.enclosingClass(), variable.getName());
                if (!members.isEmpty()) {
                    return new Receiver(usable(typeOfMember(members.get(0))), false);
                }
            }
            return Receiver.UNKNOWN;
        }
        if (expression instanceof ClassExpression classExpression) {
            return typedReceiver(classExpression.getType());
        }
        if (expression instanceof ConstructorCallExpression constructorCall) {
            return typedReceiver(constructorCall.getType());
        }
        if (expression instanceof CastExpression cast) {
            return typedReceiver(cast.getType());
        }
        if (expression instanceof MethodCallExpression chained) {
            Receiver inner = receiverOf(chained.getObjectExpression(), chained.isImplicitThis(), entry);
            if (inner.type != null && chained.getMethodAsString() != null) {
                List<MethodNode> methods = Members.accepting(
                        Members.methodsNamed(inner.type, chained.getMethodAsString()),
                        Members.argumentCount(chained.getArguments()));
                if (!methods.isEmpty()) {
                    return new Receiver(usable(methods.get(0).getReturnType()), false);
                }
            }
            return Receiver.UNKNOWN;
        }
        if (expression instanceof PropertyExpression propertyExpression) {
            Receiver inner = receiverOf(propertyExpression.getObjectExpression(), propertyExpression.isImplicitThis(), entry);
            if (inner.type != null && propertyExpression.getPropertyAsString() != null) {
                List<ASTNode> members = Members.membersNamed(inner.type, propertyExpression.getPropertyAsString());
                if (!members.isEmpty()) {
                    return new Receiver(usable(typeOfMember(members.get(0))), false);
                }
            }
            return Receiver.UNKNOWN;
        }
        if (expression instanceof ConstantExpression constant && constant.getValue() != null) {
            return new Receiver(ClassHelper.make(constant.getValue().getClass()), true);
        }
        ClassNode type = expression.getType();
        return typedReceiver(type);
    }

    /** A declared type as a receiver: exact when the compiler resolved it, inferred when matched by name. */
    private Receiver typedReceiver(ClassNode type) {
        ClassNode known = usable(type);
        if (known == null) {
            return Receiver.UNKNOWN;
        }
        boolean exact = Members.isKnown(type.redirect());
        return new Receiver(known, exact);
    }

    static ClassNode nullIfDynamic(ClassNode type) {
        if (type == null || ClassHelper.isDynamicTyped(type) || ClassHelper.isObjectType(type)) {
            return null;
        }
        return type;
    }

    /** The declared type of a member; {@code null} when dynamic. */
    public static ClassNode typeOfMember(ASTNode member) {
        if (member instanceof PropertyNode property) {
            return nullIfDynamic(property.getOriginType());
        }
        if (member instanceof FieldNode field) {
            return nullIfDynamic(field.getOriginType());
        }
        if (member instanceof MethodNode method) {
            return nullIfDynamic(method.getReturnType());
        }
        if (member instanceof Parameter parameter) {
            return nullIfDynamic(parameter.getOriginType());
        }
        if (member instanceof VariableExpression variable) {
            return nullIfDynamic(variable.getOriginType());
        }
        if (member instanceof ClassNode classNode) {
            return classNode;
        }
        return null;
    }

    /** The type of a declared local: its declared type, or the constructed type when declared with {@code def}. */
    public ClassNode typeOfVariable(VariableExpression declared) {
        ClassNode inferred = variableTypes.get(declared);
        return inferred != null ? inferred : nullIfDynamic(declared.getOriginType());
    }

    /** The receiver type of the enclosing class when the call has no explicit object. */
    private Receiver enclosing(NodeEntry entry) {
        ClassNode enclosing = entry.enclosingClass();
        return enclosing == null ? Receiver.UNKNOWN : new Receiver(enclosing, true);
    }

    /**
     * The type a {@code def} local holds from its initialiser: a constructor call, a cast, a constant, a call to
     * a method whose every overload of that arity declares one return type, or a call to a closure property whose
     * every return yields one constructed type. The answer is an inference (the variable may be reassigned), so
     * references through it are {@link Confidence#INFERRED}, never {@link Confidence#STATIC}.
     */
    static ClassNode inferDeclaredType(VariableExpression variable, DeclarationExpression declaration,
            ClassNode enclosing) {
        if (!variable.isDynamicTyped()) {
            return null;
        }
        return initialiserType(declaration.getRightExpression(), enclosing);
    }

    private static ClassNode initialiserType(Expression right, ClassNode enclosing) {
        if (right instanceof ConstructorCallExpression constructorCall && !constructorCall.isSpecialCall()) {
            return constructorCall.getType();
        }
        if (right instanceof CastExpression cast) {
            return cast.getType();
        }
        if (right instanceof ConstantExpression constant && constant.getValue() != null) {
            return ClassHelper.make(constant.getValue().getClass());
        }
        if (right instanceof StaticMethodCallExpression call) {
            return declaredReturn(Members.accepting(Members.methodsNamed(call.getOwnerType(), call.getMethod()),
                    Members.argumentCount(call.getArguments())));
        }
        if (right instanceof MethodCallExpression call && call.getMethodAsString() != null) {
            boolean onThis = call.isImplicitThis()
                    || call.getObjectExpression() instanceof VariableExpression receiver && receiver.isThisExpression();
            if (onThis && enclosing != null) {
                String name = call.getMethodAsString();
                ClassNode declared = declaredReturn(Members.accepting(Members.methodsNamed(enclosing, name),
                        Members.argumentCount(call.getArguments())));
                return declared != null ? declared : closureReturn(Members.membersNamed(enclosing, name));
            }
            return nullIfDynamic(org.groovyscope.engine.practices.PracticeFinder.typeOf(call));
        }
        return null;
    }

    /** The return type every candidate declares, or {@code null} when there is none or they disagree. */
    private static ClassNode declaredReturn(List<MethodNode> candidates) {
        ClassNode type = null;
        for (MethodNode method : candidates) {
            ClassNode returned = method.isDynamicReturnType() ? null : nullIfDynamic(method.getReturnType());
            if (returned == null || type != null && !type.equals(returned)) {
                return null;
            }
            type = returned;
        }
        return type;
    }

    /**
     * For a closure-valued field or property, the one type its returns construct: {@code return new X()}, a final
     * {@code new X()}, or a local declared as {@code def x = new X()} and returned. {@code null} otherwise.
     */
    private static ClassNode closureReturn(List<ASTNode> members) {
        if (members.size() != 1) {
            return null;
        }
        Expression initial = members.get(0) instanceof PropertyNode property ? property.getInitialExpression()
                : members.get(0) instanceof FieldNode field ? field.getInitialExpression() : null;
        if (!(initial instanceof org.codehaus.groovy.ast.expr.ClosureExpression closure)
                || !(closure.getCode() instanceof org.codehaus.groovy.ast.stmt.BlockStatement block)) {
            return null;
        }
        List<Expression> returned = new ArrayList<>();
        Map<String, ClassNode> locals = new java.util.HashMap<>();
        List<org.codehaus.groovy.ast.stmt.Statement> statements = block.getStatements();
        for (int i = 0; i < statements.size(); i++) {
            org.codehaus.groovy.ast.stmt.Statement statement = statements.get(i);
            if (statement instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement expression
                    && expression.getExpression() instanceof DeclarationExpression local
                    && local.getLeftExpression() instanceof VariableExpression name
                    && local.getRightExpression() instanceof ConstructorCallExpression constructed) {
                locals.put(name.getName(), constructed.getType());
            } else if (statement instanceof org.codehaus.groovy.ast.stmt.ReturnStatement result) {
                returned.add(result.getExpression());
            } else if (i == statements.size() - 1
                    && statement instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement last) {
                returned.add(last.getExpression());
            }
        }
        ClassNode type = null;
        for (Expression expression : returned) {
            ClassNode one = expression instanceof ConstructorCallExpression constructed ? constructed.getType()
                    : expression instanceof VariableExpression local ? locals.get(local.getName()) : null;
            if (one == null || type != null && !type.equals(one)) {
                return null;
            }
            type = one;
        }
        return type;
    }

    /** A property's backing field resolves to the property, so one declaration owns all references. */
    static ASTNode canonicalField(FieldNode field) {
        ClassNode owner = field.getOwner();
        if (owner != null) {
            PropertyNode property = owner.getProperty(field.getName());
            if (property != null && property.getField() == field) {
                return property;
            }
        }
        return field;
    }

    static ASTNode canonical(ASTNode member) {
        return member instanceof FieldNode field ? canonicalField(field) : member;
    }

    private List<ASTNode> workspaceMethodsNamed(String name, int count) {
        List<ASTNode> matches = new ArrayList<>();
        for (NodeEntry declaration : index.declarations()) {
            if (declaration.node() instanceof MethodNode method && !(method instanceof ConstructorNode)
                    && method.getName().equals(name) && Members.accepts(method, count)) {
                matches.add(method);
            }
        }
        return matches;
    }

    private List<ASTNode> workspaceMembersNamed(String name) {
        List<ASTNode> matches = new ArrayList<>();
        for (NodeEntry declaration : index.declarations()) {
            ASTNode node = declaration.node();
            if (node instanceof PropertyNode property && property.getName().equals(name)) {
                matches.add(property);
            } else if (node instanceof FieldNode field && field.getName().equals(name)) {
                matches.add(canonicalField(field));
            }
        }
        return matches;
    }

    /** A receiver's known static type and whether it was declared (exact) or inferred. */
    public record Receiver(ClassNode type, boolean exact) {
        static final Receiver UNKNOWN = new Receiver(null, false);
    }
}
