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
package org.groovyscope.engine.explain;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.semantics.Members;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * How a class is designed, as facts read from its resolved tree: its size, how its methods hang together over its
 * fields (LCOM4), the fields only one method needs, the methods that use another class's state more than their own,
 * the parameter lists several methods share, the branches on the type of a value, the state anyone may change, and
 * the methods that need no instance at all. The design checks (GS090 to GS097) and {@code groovyscope/design} both
 * read these, so they agree. Lines are 1-based.
 */
public final class DesignFacts {

    /** Names of String parameters that hold an identifier, a path or a code (GS096). */
    private static final Pattern IDENTIFIER_NAME = Pattern.compile("id|path|code|key|name|uri|url|email"
            + "|[a-z][a-zA-Z0-9]*(Id|Path|Code|Key)");
    /** Methods a data class may have besides its accessors (GS095). */
    private static final Set<String> VALUE_METHODS = Set.of("equals", "hashCode", "toString", "compareTo");
    /** Methods Groovy or the JVM call by name, never candidates to become static or to move. */
    private static final Set<String> CALLED_BY_NAME = Set.of("call", "doCall", "methodMissing", "propertyMissing",
            "invokeMethod", "getProperty", "setProperty", "main", "run", "readResolve", "writeReplace", "readObject",
            "writeObject", "finalize", "clone", "asType", "iterator");
    /** Annotations that make a class an immutable value: nothing can write its fields. */
    private static final Set<String> IMMUTABLE = Set.of("Immutable", "ImmutableBase", "RecordType", "KnownImmutable",
            "ImmutableOptions");
    /** Data read by this many workspace classes is shared: no one reader envies it (GS091). */
    static final int SHARED_READERS = 3;
    /** Mutable collection types a getter should not hand out (GS094). */
    private static final Set<String> COLLECTIONS = Set.of("java.util.List", "java.util.Set", "java.util.Map",
            "java.util.Collection", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.SortedSet", "java.util.NavigableSet",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.SortedMap",
            "java.util.NavigableMap", "java.util.Deque", "java.util.Queue", "java.util.ArrayDeque",
            "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.CopyOnWriteArrayList");
    /** How big a class is: declared methods (constructors left out), fields that hold state, and lines. */
    public record Size(int methods, int fields, int lines) {
    }

    /** One connected group of methods: they share fields or call each other. */
    public record Group(List<String> methods, List<String> fields) {
    }

    /**
     * LCOM4: the number of groups of methods that share no field and no call with each other, and the groups; and
     * {@code byFields}, the groups when only shared fields join methods (calls ignored, methods using no field left
     * out), which shows the jobs a coordinating method ties together. {@code separable} are the groups of
     * {@code byFields} with two methods or more that call no method of another such group: a job other methods only
     * call into, which can move to a class of its own.
     */
    public record Cohesion(int lcom4, List<Group> groups, List<Group> byFields, List<Group> separable) {
    }

    /** A field only one method writes and reads: a local variable of that method in disguise. */
    public record LocalCandidate(String field, String method, int line, FieldNode node) {
    }

    /** A method that uses another class's fields or accessors more than its own class's. */
    public record Envy(String method, int line, String target, int targetUses, int ownUses, MethodNode node) {
    }

    /** Parameters (type and name) that several methods all take: a parameter object candidate. */
    public record SharedParameters(List<String> parameters, List<String> methods, List<MethodNode> nodes) {
    }

    /** One branch on a type: the type and the lines of the code it runs. */
    public record Branch(String type, int startLine, int endLine) {
    }

    /**
     * Branches on the type of one value in one method ({@code instanceof} or {@code switch}): a polymorphism
     * candidate when the workspace declares every type tested ({@code workspaceTypes}).
     */
    public record TypeSwitch(String method, int line, String subject, String kind, List<Branch> branches,
            boolean workspaceTypes, MethodNode node) {
    }

    /** State anyone may change: a public mutable field, or a getter handing out a mutable collection field. */
    public record Exposed(String name, String type, int line, String kind, String field, AnnotatedNode node) {
    }

    /** A static field whose value can change: shared by every instance and every thread. */
    public record StaticState(String name, String type, int line, String kind) {
    }

    /** A method taking more parameters than {@code design.maxParameters}. */
    public record LongParameters(String method, int line, List<String> parameters, MethodNode node) {
    }

    /** A method taking three or more String parameters named as identifiers, paths or codes. */
    public record PrimitiveObsession(String method, int line, List<String> parameters, MethodNode node) {
    }

    /** A method that uses no instance state, and the workspace class whose objects it mostly works on, if any. */
    public record Stateless(String method, int line, String target, MethodNode node) {
    }

    /** A class that only holds data, and the one other class that reads and writes its fields. */
    public record DataOwner(String owner, String method, int reads, int writes) {
    }

    /** Everything known about one class's design. */
    public record ClassDesign(String name, String simpleName, int line, Size size, Cohesion cohesion,
            List<LocalCandidate> fieldsUsedByOneMethod, List<Envy> featureEnvy,
            List<SharedParameters> sharedParameterLists, List<TypeSwitch> typeSwitches,
            List<Exposed> publicMutableFields, List<StaticState> staticMutableState, List<LongParameters> longParameterLists,
            List<PrimitiveObsession> primitiveObsession, List<Stateless> statelessMethods, DataOwner dataOwner) {
    }

    private DesignFacts() {
    }

    /** The methods and constructors written in a class's source, generated ones left out. */
    static List<MethodNode> members(ClassNode classNode) {
        List<MethodNode> members = new ArrayList<>();
        for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
            if (written(constructor)) {
                members.add(constructor);
            }
        }
        for (MethodNode method : classNode.getMethods()) {
            if (written(method) && !method.isScriptBody()) {
                members.add(method);
            }
        }
        return members;
    }

    private static boolean written(MethodNode method) {
        return method.getLineNumber() > 0 && !method.isSynthetic() && !annotated(method, Set.of("Generated"));
    }

    private static boolean annotated(AnnotatedNode node, Set<String> names) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (names.contains(annotation.getClassNode().getNameWithoutPackage())) {
                return true;
            }
        }
        return false;
    }

    /** A method's name, with its parameter types when the class declares another method of that name. */
    static String label(MethodNode method) {
        String name = method instanceof ConstructorNode ? "<init>" : method.getName();
        ClassNode owner = method.getDeclaringClass();
        long same = method instanceof ConstructorNode ? owner.getDeclaredConstructors().size()
                : owner.getMethods(name).stream().filter(other -> !other.isSynthetic()).count();
        if (same <= 1) {
            return name;
        }
        List<String> types = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            types.add(parameter.isDynamicTyped() ? "def" : TypeNames.simple(parameter.getType()));
        }
        return name + "(" + String.join(", ", types) + ")";
    }

    /** The design of one class; {@code index} answers what the rest of the workspace does with it. */
    public static ClassDesign of(ClassNode classNode, DesignIndex index, EngineConfig.Design limits) {
        List<MethodNode> members = members(classNode);
        Map<MethodNode, DesignScan> scans = new LinkedHashMap<>();
        for (MethodNode member : members) {
            scans.put(member, DesignScan.of(member));
        }
        List<MethodNode> methods = members.stream().filter(member -> !(member instanceof ConstructorNode)).toList();
        Range range = AstRanges.rangeOf(classNode);
        int lines = range == null ? 0 : range.end().line() - range.start().line() + 1;
        Size size = new Size(methods.size(), stateFields(classNode).size(), lines);
        String simpleName = classNode.getNameWithoutPackage().replace('$', '.');
        return new ClassDesign(classNode.getName(), simpleName, classNode.getLineNumber(), size,
                cohesion(methods, scans), localCandidates(classNode, scans), envy(methods, scans, limits, index),
                sharedParameters(members), typeSwitches(scans), exposed(classNode, methods), staticState(classNode),
                longParameters(members, limits), primitiveObsession(members),
                stateless(classNode, methods, scans, index), dataOwner(classNode, methods, index));
    }

    /** Fields and properties that hold state: static final constants left out. */
    static List<String> stateFields(ClassNode classNode) {
        Set<String> names = new LinkedHashSet<>();
        for (PropertyNode property : classNode.getProperties()) {
            if (!(property.isStatic() && Modifier.isFinal(property.getModifiers()))) {
                names.add(property.getName());
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (!field.isSynthetic() && !field.getName().startsWith("$") && !(field.isStatic() && field.isFinal())
                    && field.getLineNumber() > 0) {
                names.add(field.getName());
            }
        }
        return new ArrayList<>(names);
    }

    /** LCOM4 over the instance methods: methods are joined when they share an instance field or one calls the other. */
    static Cohesion cohesion(List<MethodNode> methods, Map<MethodNode, DesignScan> scans) {
        List<MethodNode> instance = methods.stream().filter(method -> !method.isStatic() && !method.isAbstract())
                .toList();
        List<Group> groups = groups(instance, scans, true);
        List<MethodNode> stateful = instance.stream().filter(method -> !scans.get(method).fields().isEmpty()).toList();
        List<Group> byFields = groups(stateful, scans, false);
        return new Cohesion(groups.size(), groups, byFields, separable(byFields, stateful, scans));
    }

    /** The field groups of two methods or more whose methods call no method of another field group. */
    private static List<Group> separable(List<Group> byFields, List<MethodNode> stateful,
            Map<MethodNode, DesignScan> scans) {
        List<Group> found = new ArrayList<>();
        if (byFields.size() < 2) {
            return found;
        }
        Map<String, Group> groupOf = new HashMap<>();
        Map<String, MethodNode> byLabel = new HashMap<>();
        for (MethodNode method : stateful) {
            byLabel.put(label(method), method);
        }
        for (Group group : byFields) {
            for (String method : group.methods()) {
                groupOf.put(byLabel.get(method).getName(), group);
            }
        }
        for (Group group : byFields) {
            boolean callsOut = group.methods().stream().flatMap(method -> scans.get(byLabel.get(method)).ownCalls
                    .stream()).anyMatch(called -> groupOf.containsKey(called) && groupOf.get(called) != group);
            if (group.methods().size() >= 2 && !callsOut) {
                found.add(group);
            }
        }
        return found;
    }

    /** Connected groups of methods, joined by shared fields and, when {@code calls}, by calls between them. */
    private static List<Group> groups(List<MethodNode> instance, Map<MethodNode, DesignScan> scans, boolean calls) {
        int[] parent = new int[instance.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<String, Integer> firstByField = new HashMap<>();
        Map<String, List<Integer>> byName = new HashMap<>();
        for (int i = 0; i < instance.size(); i++) {
            byName.computeIfAbsent(instance.get(i).getName(), k -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < instance.size(); i++) {
            DesignScan scan = scans.get(instance.get(i));
            for (String field : scan.fields()) {
                Integer first = firstByField.putIfAbsent(field, i);
                if (first != null) {
                    union(parent, first, i);
                }
            }
            for (String called : calls ? scan.ownCalls : Set.<String>of()) {
                for (int j : byName.getOrDefault(called, List.of())) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < instance.size(); i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<Group> groups = new ArrayList<>();
        for (List<Integer> component : components.values()) {
            List<String> names = new ArrayList<>();
            Set<String> fields = new TreeSet<>();
            for (int i : component) {
                names.add(label(instance.get(i)));
                fields.addAll(scans.get(instance.get(i)).fields());
            }
            groups.add(new Group(names, new ArrayList<>(fields)));
        }
        groups.sort(Comparator.comparingInt((Group group) -> -group.methods().size())
                .thenComparingInt(group -> -group.fields().size()));
        return groups;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /**
     * Private instance fields with no initial value and no annotation that exactly one method uses, and writes: no
     * constructor, other method or inner class touches them, so they carry nothing between calls a local could not.
     */
    static List<LocalCandidate> localCandidates(ClassNode classNode, Map<MethodNode, DesignScan> scans) {
        Set<String> fromInner = innerClassReferences(classNode);
        List<LocalCandidate> found = new ArrayList<>();
        for (FieldNode field : classNode.getFields()) {
            if (field.isStatic() || field.isSynthetic() || field.getLineNumber() <= 0 || field.hasInitialExpression()
                    || !field.getAnnotations().isEmpty() || classNode.getProperty(field.getName()) != null
                    || !Modifier.isPrivate(field.getModifiers()) || fromInner.contains(field.getName())) {
                continue;
            }
            MethodNode only = null;
            int users = 0;
            for (Map.Entry<MethodNode, DesignScan> entry : scans.entrySet()) {
                if (entry.getValue().fields().contains(field.getName())) {
                    users++;
                    only = entry.getKey();
                }
            }
            if (users == 1 && !(only instanceof ConstructorNode) && scans.get(only).writes.contains(field.getName())) {
                found.add(new LocalCandidate(field.getName(), label(only), only.getLineNumber(), field));
            }
        }
        return found;
    }

    /** Names of the class's fields that its inner classes refer to. */
    private static Set<String> innerClassReferences(ClassNode classNode) {
        Set<String> names = new TreeSet<>();
        java.util.Iterator<InnerClassNode> inner = classNode.getInnerClasses();
        while (inner.hasNext()) {
            ClassNode nested = inner.next();
            CodeVisitorSupport collector = new CodeVisitorSupport() {
                @Override
                public void visitVariableExpression(VariableExpression expression) {
                    if (expression.getAccessedVariable() instanceof FieldNode field && field.getOwner() != null
                            && DesignScan.sameClass(field.getOwner(), classNode)) {
                        names.add(field.getName());
                    }
                }
            };
            for (MethodNode method : members(nested)) {
                if (method.getCode() != null) {
                    method.getCode().visit(collector);
                }
            }
            for (FieldNode field : nested.getFields()) {
                if (field.hasInitialExpression()) {
                    field.getInitialExpression().visit(collector);
                }
            }
            names.addAll(innerClassReferences(nested));
        }
        return names;
    }

    /**
     * Methods reading one other workspace class's fields and getters at least {@code envyMinUses} times, and more
     * often than their own class's. Overrides are left out (their place is set by the supertype), and so is data
     * that {@link #SHARED_READERS} classes or more read: a value many classes consume, each in its own way (a report
     * writer formatting a result), is not envied by any one of them.
     */
    static List<Envy> envy(List<MethodNode> methods, Map<MethodNode, DesignScan> scans, EngineConfig.Design limits,
            DesignIndex index) {
        List<Envy> found = new ArrayList<>();
        for (MethodNode method : methods) {
            DesignScan scan = scans.get(method);
            Map.Entry<String, Integer> target = scan.mostReadForeign();
            if (target != null && target.getValue() >= limits.envyMinUses() && target.getValue() > scan.ownUses
                    && !method.isAbstract() && !overrides(method) && readers(index, target.getKey()) < SHARED_READERS) {
                found.add(new Envy(label(method), method.getLineNumber(), target.getKey(), target.getValue(),
                        scan.ownUses, method));
            }
        }
        return found;
    }

    private static long readers(DesignIndex index, String type) {
        return index.fieldUsers(type).values().stream().filter(use -> use.reads() > 0).count();
    }

    /** Sets of three or more parameters (type and name) that three or more methods or constructors all take. */
    static List<SharedParameters> sharedParameters(List<MethodNode> members) {
        List<Set<String>> lists = new ArrayList<>();
        for (MethodNode member : members) {
            Set<String> parameters = new LinkedHashSet<>();
            for (Parameter parameter : member.getParameters()) {
                parameters.add(parameterText(parameter));
            }
            lists.add(parameters);
        }
        Map<Set<String>, List<MethodNode>> shared = new LinkedHashMap<>();
        for (int i = 0; i < lists.size(); i++) {
            for (int j = i + 1; j < lists.size(); j++) {
                Set<String> common = new LinkedHashSet<>(lists.get(i));
                common.retainAll(lists.get(j));
                if (common.size() < 3 || shared.containsKey(common)) {
                    continue;
                }
                List<MethodNode> takers = new ArrayList<>();
                for (int k = 0; k < lists.size(); k++) {
                    if (lists.get(k).containsAll(common)) {
                        takers.add(members.get(k));
                    }
                }
                if (takers.size() >= 3) {
                    shared.put(common, takers);
                }
            }
        }
        List<SharedParameters> found = new ArrayList<>();
        for (Map.Entry<Set<String>, List<MethodNode>> entry : shared.entrySet()) {
            boolean covered = shared.entrySet().stream().anyMatch(other -> other != entry
                    && other.getKey().size() > entry.getKey().size() && other.getKey().containsAll(entry.getKey())
                    && other.getValue().equals(entry.getValue()));
            if (!covered) {
                found.add(new SharedParameters(new ArrayList<>(entry.getKey()),
                        entry.getValue().stream().map(DesignFacts::label).toList(), entry.getValue()));
            }
        }
        return found;
    }

    private static String parameterText(Parameter parameter) {
        return (parameter.isDynamicTyped() ? "def" : TypeNames.simple(parameter.getType())) + " " + parameter.getName();
    }

    static List<TypeSwitch> typeSwitches(Map<MethodNode, DesignScan> scans) {
        List<TypeSwitch> found = new ArrayList<>();
        for (Map.Entry<MethodNode, DesignScan> entry : scans.entrySet()) {
            for (DesignScan.Switch typeSwitch : entry.getValue().switches) {
                List<Branch> branches = new ArrayList<>();
                boolean workspaceTypes = true;
                for (DesignScan.Branch branch : typeSwitch.branches()) {
                    branches.add(new Branch(branch.type(), branch.startLine(), branch.endLine()));
                    workspaceTypes &= branch.workspace();
                }
                found.add(new TypeSwitch(label(entry.getKey()), typeSwitch.line(), typeSwitch.subject(),
                        typeSwitch.kind(), branches, workspaceTypes, entry.getKey()));
            }
        }
        found.sort(Comparator.comparingInt(TypeSwitch::line));
        return found;
    }

    /** Public fields that are not final, and public getters returning a mutable collection field itself. */
    static List<Exposed> exposed(ClassNode classNode, List<MethodNode> methods) {
        List<Exposed> found = new ArrayList<>();
        if (classNode.isInterface() || annotated(classNode, IMMUTABLE) || classNode.isRecord()) {
            return found;
        }
        for (FieldNode field : classNode.getFields()) {
            if (field.getLineNumber() > 0 && !field.isSynthetic() && field.isPublic() && !field.isFinal()
                    && !field.isEnum() && classNode.getProperty(field.getName()) == null
                    && !field.getName().startsWith("$")) {
                found.add(new Exposed(field.getName(), typeText(field), field.getLineNumber(), "field", field.getName(),
                        field));
            }
        }
        for (MethodNode method : methods) {
            if (!method.isPublic() || method.getParameters().length != 0 || method.isAbstract()) {
                continue;
            }
            FieldNode returned = returnedField(classNode, method);
            if (returned != null && isMutableCollection(classNode, returned)) {
                found.add(new Exposed(label(method), typeText(returned), method.getLineNumber(), "getter",
                        returned.getName(), method));
            }
        }
        return found;
    }

    /** The field of the class a method's only statement returns as it is ({@code return items}), or {@code null}. */
    private static FieldNode returnedField(ClassNode classNode, MethodNode method) {
        Statement code = method.getCode();
        if (code instanceof BlockStatement block && block.getStatements().size() == 1) {
            code = block.getStatements().get(0);
        }
        Expression value = code instanceof ReturnStatement ret ? ret.getExpression()
                : code instanceof ExpressionStatement statement ? statement.getExpression() : null;
        // A property's own getter returning it is what Groovy writes anyway: properties are not reported.
        if (value instanceof VariableExpression variable && !variable.isThisExpression()
                && variable.getAccessedVariable() instanceof FieldNode field && field.getOwner() != null
                && DesignScan.sameClass(field.getOwner(), classNode)
                && classNode.getProperty(field.getName()) == null) {
            return field;
        }
        return null;
    }

    /**
     * A field of a collection type that provably holds a mutable collection: every value the class gives it (its
     * initial value and each assignment) is a list or map literal or a new collection. A value from a parameter or
     * from another object may already be unmodifiable, so such a field is not reported.
     */
    private static boolean isMutableCollection(ClassNode classNode, FieldNode field) {
        if (field.isDynamicTyped() || !COLLECTIONS.contains(field.getOriginType().redirect().getName())) {
            return false;
        }
        List<Expression> values = assignedValues(classNode, field);
        return !values.isEmpty() && values.stream().allMatch(DesignFacts::newCollection);
    }

    /** A list or map literal, or a new collection: a value nothing else holds and anyone may change. */
    private static boolean newCollection(Expression value) {
        if (value instanceof ListExpression || value instanceof MapExpression) {
            return true;
        }
        return value instanceof ConstructorCallExpression constructor
                && COLLECTIONS.contains(constructor.getType().redirect().getName());
    }

    /** The values a class gives a field: its initial value and the right side of every assignment to it. */
    private static List<Expression> assignedValues(ClassNode classNode, FieldNode field) {
        List<Expression> values = new ArrayList<>();
        if (field.hasInitialExpression()) {
            values.add(field.getInitialExpression());
        }
        CodeVisitorSupport assignments = new CodeVisitorSupport() {
            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                if (expression.getOperation().getType() == Types.ASSIGN && names(expression.getLeftExpression())) {
                    values.add(expression.getRightExpression());
                }
                super.visitBinaryExpression(expression);
            }

            private boolean names(Expression left) {
                if (left instanceof VariableExpression variable) {
                    return variable.getAccessedVariable() == field
                            || variable.getAccessedVariable() instanceof PropertyNode property
                            && property.getField() == field;
                }
                return left instanceof PropertyExpression property
                        && property.getObjectExpression() instanceof VariableExpression self && self.isThisExpression()
                        && field.getName().equals(property.getPropertyAsString());
            }
        };
        for (MethodNode member : members(classNode)) {
            if (member.getCode() != null) {
                member.getCode().visit(assignments);
            }
        }
        return values;
    }

    private static String typeText(FieldNode field) {
        return field.isDynamicTyped() ? "def" : TypeNames.simple(field.getOriginType());
    }

    /** Static fields that are not final, and static final collections whose value is not unmodifiable. */
    static List<StaticState> staticState(ClassNode classNode) {
        List<StaticState> found = new ArrayList<>();
        if (classNode.isInterface()) {
            return found;
        }
        for (FieldNode field : classNode.getFields()) {
            // A property's backing field is marked synthetic, yet it is the property's state.
            boolean generated = field.isSynthetic() && classNode.getProperty(field.getName()) == null;
            if (!field.isStatic() || generated || field.getLineNumber() <= 0 || field.isEnum()
                    || field.getName().startsWith("$") || isLogger(field)) {
                continue;
            }
            if (!field.isFinal()) {
                found.add(new StaticState(field.getName(), typeText(field), field.getLineNumber(), "field"));
            } else if (isMutableCollection(classNode, field)) {
                found.add(new StaticState(field.getName(), typeText(field), field.getLineNumber(), "collection"));
            }
        }
        return found;
    }

    private static boolean isLogger(FieldNode field) {
        String type = field.getOriginType().getName();
        return type.endsWith("Logger") || type.endsWith(".Log");
    }

    /** Methods and constructors taking more than {@code maxParameters}; overrides are left out, their list is set. */
    static List<LongParameters> longParameters(List<MethodNode> members, EngineConfig.Design limits) {
        List<LongParameters> found = new ArrayList<>();
        for (MethodNode member : members) {
            if (member.getParameters().length > limits.maxParameters() && !overrides(member)) {
                List<String> parameters = new ArrayList<>();
                for (Parameter parameter : member.getParameters()) {
                    parameters.add(parameterText(parameter));
                }
                found.add(new LongParameters(label(member), member.getLineNumber(), parameters, member));
            }
        }
        return found;
    }

    /** Methods and constructors with three or more String parameters named as identifiers, paths or codes. */
    static List<PrimitiveObsession> primitiveObsession(List<MethodNode> members) {
        List<PrimitiveObsession> found = new ArrayList<>();
        for (MethodNode member : members) {
            List<String> names = new ArrayList<>();
            for (Parameter parameter : member.getParameters()) {
                if (!parameter.isDynamicTyped() && ClassHelper.STRING_TYPE.equals(parameter.getType().redirect())
                        && IDENTIFIER_NAME.matcher(parameter.getName()).matches()) {
                    names.add(parameter.getName());
                }
            }
            if (names.size() >= 3 && !overrides(member)) {
                found.add(new PrimitiveObsession(label(member), member.getLineNumber(), names, member));
            }
        }
        return found;
    }

    /**
     * Instance methods that read and write no instance field, call no instance method of their class and do not use
     * {@code this}: they could be static, or live with the objects they work on. Abstract methods, overrides, methods
     * a subclass declares again, accessors, methods called by name, annotated or empty methods, and the methods of
     * interfaces, traits, scripts, anonymous and inner classes are left out.
     */
    static List<Stateless> stateless(ClassNode classNode, List<MethodNode> methods, Map<MethodNode, DesignScan> scans,
            DesignIndex index) {
        List<Stateless> found = new ArrayList<>();
        if (classNode.isInterface() || classNode.isScript() || isTrait(classNode)
                || classNode instanceof InnerClassNode inner && (inner.isAnonymous() || !inner.isStaticClass())) {
            return found;
        }
        for (MethodNode method : methods) {
            DesignScan scan = scans.get(method);
            if (method.isStatic() || method.isAbstract() || scan.instanceState || !method.getAnnotations().isEmpty()
                    || CALLED_BY_NAME.contains(method.getName())
                    || DesignScan.accessorField(method.getName(), method.getParameters().length) != null
                    || trivial(method.getCode()) || overrides(method)
                    || index.redeclaredBelow(classNode, method.getName(), method.getParameters().length)) {
                continue;
            }
            found.add(new Stateless(label(method), method.getLineNumber(), target(scan), method));
        }
        return found;
    }

    /** The workspace class most of a method's calls and member reads go to, when it takes more than half of them. */
    private static String target(DesignScan scan) {
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> entry : scan.receivers.entrySet()) {
            if (best == null || entry.getValue() > best.getValue()) {
                best = entry;
            }
        }
        return best != null && best.getValue() >= 2 && best.getValue() * 2 > scan.calls ? best.getKey() : null;
    }

    /** An empty body, or one that only throws: a placeholder, not logic. */
    private static boolean trivial(Statement code) {
        if (code == null) {
            return true;
        }
        if (code instanceof BlockStatement block) {
            List<Statement> statements = block.getStatements();
            return statements.isEmpty() || statements.size() == 1 && statements.get(0) instanceof ThrowStatement;
        }
        return code instanceof ThrowStatement;
    }

    private static boolean isTrait(ClassNode classNode) {
        return annotated(classNode, Set.of("Trait"));
    }

    /**
     * Whether a method overrides or implements one of a supertype (same name and parameter count, the supertype's
     * not private), or carries {@code @Override}: its signature and its place are set by that supertype.
     */
    static boolean overrides(MethodNode method) {
        if (method instanceof ConstructorNode) {
            return false;
        }
        if (annotated(method, Set.of("Override"))) {
            return true;
        }
        ClassNode owner = method.getDeclaringClass();
        Set<ClassNode> above = new LinkedHashSet<>();
        try {
            above.addAll(owner.getAllInterfaces());
            for (ClassNode parent = owner.getSuperClass(); parent != null; parent = parent.getSuperClass()) {
                above.add(parent);
                above.addAll(parent.getAllInterfaces());
            }
        } catch (LinkageError e) {
            return true; // a supertype whose classes are missing: assume the method is bound to it
        }
        for (ClassNode type : above) {
            if (DesignScan.sameClass(type, owner)) {
                continue;
            }
            for (MethodNode other : Members.readable(() -> type.getDeclaredMethods(method.getName()))) {
                if (!other.isPrivate() && other.getParameters().length == method.getParameters().length) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The one other workspace class that writes this class's fields and reads them too, when the class only holds
     * data (fields, their accessors, and at most {@code equals}, {@code hashCode}, {@code toString} and
     * {@code compareTo}); {@code null} otherwise. Immutable values and records have no writer, so never an owner.
     */
    static DataOwner dataOwner(ClassNode classNode, List<MethodNode> methods, DesignIndex index) {
        if (!isDataClass(classNode, methods)) {
            return null;
        }
        Map<String, DesignIndex.Use> users = index.fieldUsers(classNode.getName());
        String writer = null;
        for (Map.Entry<String, DesignIndex.Use> entry : users.entrySet()) {
            if (entry.getValue().writes() > 0) {
                if (writer != null) {
                    return null;
                }
                writer = entry.getKey();
            }
        }
        if (writer == null || users.get(writer).reads() == 0 || nested(classNode, writer)) {
            return null;
        }
        DesignIndex.Use use = users.get(writer);
        String method = use.byMethod().entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(null);
        return new DataOwner(writer, method, use.reads(), use.writes());
    }

    /** Whether one class is declared inside the other: a nested holder is the outer class's own business. */
    private static boolean nested(ClassNode classNode, String other) {
        for (ClassNode outer = classNode.getOuterClass(); outer != null; outer = outer.getOuterClass()) {
            if (outer.getName().equals(other)) {
                return true;
            }
        }
        return other.startsWith(classNode.getName() + "$");
    }

    static boolean isDataClass(ClassNode classNode, List<MethodNode> methods) {
        if (classNode.isInterface() || classNode.isEnum() || classNode.isScript() || classNode.isAbstract()
                || classNode.isRecord() || annotated(classNode, IMMUTABLE) || isTrait(classNode)
                || classNode.getSuperClass() != null && !ClassHelper.isObjectType(classNode.getSuperClass())
                && !"groovy.lang.GroovyObjectSupport".equals(classNode.getSuperClass().getName())) {
            return false;
        }
        Set<String> fields = new TreeSet<>();
        for (PropertyNode property : classNode.getProperties()) {
            if (!property.isStatic()) {
                fields.add(property.getName());
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (!field.isStatic() && !field.isSynthetic() && field.getLineNumber() > 0) {
                fields.add(field.getName());
            }
        }
        if (fields.size() < 2) {
            return false;
        }
        for (MethodNode method : methods) {
            String field = DesignScan.accessorField(method.getName(), method.getParameters().length);
            if (!VALUE_METHODS.contains(method.getName()) && (field == null || !fields.contains(field))) {
                return false;
            }
        }
        return true;
    }

    /** The simple name of a qualified class name, nested classes joined with a dot. */
    public static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return (dot >= 0 ? qualified.substring(dot + 1) : qualified).replace('$', '.');
    }

    /** A map of a node to the type switches found in it, for callers that report per method. */
    public static Map<ASTNode, List<TypeSwitch>> byMethod(List<TypeSwitch> switches) {
        Map<ASTNode, List<TypeSwitch>> grouped = new LinkedHashMap<>();
        for (TypeSwitch typeSwitch : switches) {
            grouped.computeIfAbsent(typeSwitch.node(), k -> new ArrayList<>()).add(typeSwitch);
        }
        return grouped;
    }
}
