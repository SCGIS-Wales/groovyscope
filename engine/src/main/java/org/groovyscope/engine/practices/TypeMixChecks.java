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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.Members;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * GS070 to GS076, one kind of value per place: a literal of one kind assigned, compared, passed or returned where
 * another kind is declared or inferred (GS070), a variable assigned values of different kinds (GS071), text used as
 * a boolean (GS072), a method returning different kinds on different paths (GS073), arrays and lists mixed up
 * (GS074), one map key written with different kinds (GS075) and an argument whose kind the parameter does not
 * take (GS076). Kinds are text, number, boolean, character, collection, map and array.
 *
 * <p>A kind is known from a literal, a declared type (confidence {@code STATIC}), or, for a {@code def} local,
 * from every value assigned to it in its method when they all agree ({@code INFERRED}, as the resolver infers a
 * {@code def} local from its initialiser). Anything else is unknown and never reported: dynamic code where nothing is
 * known stays silent. Calls are checked only where the target is certain: one method of that name and arity on the
 * enclosing class (outside closures, whose delegate may answer instead) or on a receiver of known type.
 */
final class TypeMixChecks {

    /** The kinds of value the checks tell apart, with how a message names them. */
    enum Kind {
        STRING("text"),
        NUMBER("a number"),
        BOOLEAN("a boolean"),
        CHAR("a character"),
        LIST("a collection"),
        MAP("a map"),
        ARRAY("an array");

        final String words;

        Kind(String words) {
            this.words = words;
        }
    }

    /**
     * What is known about a value: its kind, its type, how a message shows the type, how sure that is, and for an
     * inferred one the line it was inferred from (0 otherwise).
     */
    record Value(Kind kind, ClassNode type, String display, Confidence confidence, int line) {

        /** The same value, known only as surely as {@code weaker}. */
        Value withConfidence(Confidence weaker) {
            return new Value(kind, type, display, weaker, line);
        }
    }

    /** One write of a value to a variable, field or parameter, in source order. */
    private record Write(Object key, String name, Expression value, ASTNode node, int line, boolean append) {
    }

    /** One write of a value under a literal key of a map. */
    private record MapWrite(Value value, ASTNode node, int line) {
    }

    static final Set<String> STRING_TYPES = Set.of("java.lang.String", "groovy.lang.GString", "java.lang.CharSequence");
    static final Set<String> NUMBER_TYPES = Set.of("int", "long", "short", "byte", "float", "double",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Float",
            "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal", "java.lang.Number");
    static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "java.lang.Boolean");
    static final Set<String> CHAR_TYPES = Set.of("char", "java.lang.Character");
    static final Set<String> COLLECTION_TYPES = Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Collection", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.SortedSet", "java.util.NavigableSet", "java.util.Queue", "java.util.Deque",
            "java.util.ArrayDeque", "java.util.concurrent.CopyOnWriteArrayList");
    static final Set<String> MAP_TYPES = Set.of("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap", "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentHashMap");
    /** The kinds a literal can be compared with, assigned to or returned as only by a conversion. */
    static final Set<Kind> SCALARS = Set.of(Kind.STRING, Kind.NUMBER, Kind.BOOLEAN);
    /** The kinds Groovy never converts into one another when it picks a method for an argument. */
    static final Set<Kind> ARGUMENT_SCALARS = Set.of(Kind.STRING, Kind.NUMBER, Kind.BOOLEAN, Kind.CHAR);
    /** The text a value compared with it should be parsed from instead. */
    static final Set<String> BOOLEAN_WORDS = Set.of("true", "false");
    static final Set<String> DISPATCH_HOOKS = Set.of("methodMissing", "invokeMethod", "$static_methodMissing");
    static final int SHOWN_LENGTH = 40;

    private final PracticeContext context;
    private final ClassNode owner;
    /** Every write per variable, field or parameter of the class, in source order. */
    private final Map<Object, List<Write>> writes = new LinkedHashMap<>();
    /** The {@code def} locals the class's methods declare: the only variables whose kind is inferred. */
    private final Set<Object> dynamicLocals = new HashSet<>();
    /** Variables compared with {@code 'true'} or {@code 'false'} somewhere in the class. */
    private final Set<Object> comparedWithBooleanWords = new HashSet<>();
    private final Map<Object, Value> inferred = new HashMap<>();
    private final Set<Object> inferring = new HashSet<>();
    private final boolean dispatchHooks;

    private TypeMixChecks(PracticeContext context, ClassNode owner) {
        this.context = context;
        this.owner = owner;
        boolean hooks = false;
        for (ClassNode type : Members.hierarchy(owner)) {
            for (MethodNode method : Members.readable(type::getMethods)) {
                hooks |= DISPATCH_HOOKS.contains(method.getName()) && !"groovy.lang.GroovyObject".equals(type.getName())
                        && !"groovy.lang.GroovyObjectSupport".equals(type.getName());
            }
        }
        this.dispatchHooks = hooks;
    }

    /** Runs the value type checks over every class of the document. */
    static void find(PracticeContext context) {
        if (context.module() == null) {
            return;
        }
        for (ClassNode classNode : context.module().getClasses()) {
            if (classNode.isAnnotationDefinition() || classNode.getLineNumber() < 0 && !classNode.isScript()
                    || PracticeContext.isSpecification(classNode)) {
                continue;
            }
            new TypeMixChecks(context, classNode).scan();
        }
    }

    /**
     * Every value a local or parameter is assigned in {@code method} of {@code owner}, in source order, with its type
     * when known: what hover shows for a variable assigned more than once. Empty when it is assigned once or never;
     * null literals are left out.
     */
    static List<org.groovyscope.engine.model.Hover.InferredType> assignedTypes(ClassNode owner, MethodNode method,
            Object key) {
        if (owner == null || method == null || method.getCode() == null || key == null) {
            return List.of();
        }
        TypeMixChecks scan = new TypeMixChecks(null, owner);
        method.getCode().visit(scan.new Collector());
        List<Write> list = scan.writes.getOrDefault(key, List.of());
        if (list.size() < 2) {
            return List.of();
        }
        List<org.groovyscope.engine.model.Hover.InferredType> types = new ArrayList<>();
        for (Write write : list) {
            if (write.value() != null && isNull(write.value())) {
                continue;
            }
            Value value = write.value() == null || write.append() ? null : scan.valueOf(write.value());
            types.add(new org.groovyscope.engine.model.Hover.InferredType(value == null ? null : value.display(),
                    write.line()));
        }
        return types;
    }

    private void scan() {
        List<MethodNode> methods = methods();
        for (FieldNode field : owner.getFields()) {
            Expression initial = field.getInitialExpression();
            if (!written(field) || initial == null || initial.getLineNumber() < 1) {
                continue;
            }
            Object key = keyOf(field);
            if (field.isDynamicTyped()) {
                add(new Write(key, field.getName(), initial, initial, initial.getLineNumber(), false));
            }
        }
        for (MethodNode method : methods) {
            method.getCode().visit(new Collector());
        }
        for (Map.Entry<Object, List<Write>> entry : writes.entrySet()) {
            manyKinds(entry.getKey(), entry.getValue());
        }
        for (FieldNode field : owner.getFields()) {
            Expression initial = field.getInitialExpression();
            if (written(field) && initial != null && initial.getLineNumber() > 0 && !field.isDynamicTyped()) {
                assigned(field.getOriginType(), field.getName(), initial);
            }
        }
        for (MethodNode method : methods) {
            Checker checker = new Checker(method);
            method.getCode().visit(checker);
            checker.finish();
        }
    }

    /** The methods and constructors with code of their own, transformation output left out. */
    private List<MethodNode> methods() {
        List<MethodNode> methods = new ArrayList<>();
        List<MethodNode> all = new ArrayList<>(owner.getMethods());
        all.addAll(owner.getDeclaredConstructors());
        for (MethodNode method : all) {
            if (method.getCode() == null || method.isSynthetic() || method.getLineNumber() < 1 && !method.isScriptBody()
                    || generated(method)) {
                continue;
            }
            methods.add(method);
        }
        return methods;
    }

    /** Whether the code declares a field: its own, or the field behind a property (which Groovy marks synthetic). */
    private boolean written(FieldNode field) {
        return !field.isSynthetic() || owner.getProperty(field.getName()) != null;
    }

    private static boolean generated(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            if ("Generated".equals(annotation.getClassNode().getNameWithoutPackage())) {
                return true;
            }
        }
        return false;
    }

    private void add(Write write) {
        writes.computeIfAbsent(write.key(), k -> new ArrayList<>()).add(write);
    }

    /**
     * The identity of what a variable expression reads or writes: the declaring expression of a local, the
     * parameter, or the field (a property's field); {@code null} for a script binding variable.
     */
    static Object keyOf(Variable variable) {
        if (variable instanceof PropertyNode property) {
            return property.getField();
        }
        return variable instanceof DynamicVariable ? null : variable;
    }

    private static Object keyOf(VariableExpression expression) {
        if (expression.isThisExpression() || expression.isSuperExpression()) {
            return null;
        }
        Variable accessed = expression.getAccessedVariable();
        return accessed == null || accessed == expression ? expression : keyOf(accessed);
    }

    /** The variable a write to {@code target} reaches: a variable, or a field of this class through {@code this}. */
    private Object writtenKey(Expression target) {
        if (target instanceof VariableExpression variable) {
            return keyOf(variable);
        }
        if (target instanceof PropertyExpression property
                && property.getObjectExpression() instanceof VariableExpression receiver
                && receiver.isThisExpression() && property.getPropertyAsString() != null) {
            FieldNode field = owner.getField(property.getPropertyAsString());
            return field == null ? null : field;
        }
        return null;
    }

    private static String nameOf(Object key, Expression target) {
        if (key instanceof Variable variable) {
            return variable.getName();
        }
        return target instanceof PropertyExpression property ? property.getPropertyAsString() : target.getText();
    }

    /** Pass one: every write, the {@code def} locals, and the variables compared with boolean words. */
    private final class Collector extends CodeVisitorSupport {

        @Override
        public void visitDeclarationExpression(DeclarationExpression declaration) {
            if (declaration.isMultipleAssignmentDeclaration()) {
                for (Expression element : declaration.getTupleExpression().getExpressions()) {
                    if (element instanceof VariableExpression variable) {
                        add(new Write(variable, variable.getName(), null, declaration, declaration.getLineNumber(),
                                false));
                    }
                }
            } else {
                VariableExpression variable = declaration.getVariableExpression();
                if (variable.isDynamicTyped()) {
                    dynamicLocals.add(variable);
                }
                Expression right = declaration.getRightExpression();
                if (!(right instanceof EmptyExpression)) {
                    add(new Write(variable, variable.getName(), right, declaration, declaration.getLineNumber(),
                            false));
                }
            }
            declaration.getRightExpression().visit(this); // not the visit of the binary expression it also is
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            int operation = expression.getOperation().getType();
            Expression left = expression.getLeftExpression();
            if (operation == Types.ASSIGN && (left instanceof TupleExpression tuple)) {
                for (Expression element : tuple.getExpressions()) {
                    Object key = writtenKey(element);
                    if (key != null) {
                        add(new Write(key, nameOf(key, element), null, expression, expression.getLineNumber(), false));
                    }
                }
            } else if (operation == Types.ASSIGN || operation == Types.PLUS_EQUAL) {
                Object key = writtenKey(left);
                if (key != null) {
                    add(new Write(key, nameOf(key, left), expression.getRightExpression(), expression,
                            expression.getLineNumber(), operation == Types.PLUS_EQUAL));
                }
            }
            if (operation == Types.COMPARE_EQUAL || operation == Types.COMPARE_NOT_EQUAL) {
                booleanWord(left, expression.getRightExpression());
                booleanWord(expression.getRightExpression(), left);
            }
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            List<Expression> arguments = arguments(call.getArguments());
            if (("equals".equals(name) || "equalsIgnoreCase".equals(name)) && arguments.size() == 1) {
                booleanWord(call.getObjectExpression(), arguments.get(0));
                booleanWord(arguments.get(0), call.getObjectExpression());
            }
            super.visitMethodCallExpression(call);
        }

        private void booleanWord(Expression variable, Expression word) {
            if (variable instanceof VariableExpression named && isBooleanWord(word)) {
                Object key = keyOf(named);
                if (key != null) {
                    comparedWithBooleanWords.add(key);
                }
            }
        }
    }

    static boolean isBooleanWord(Expression expression) {
        return expression instanceof ConstantExpression constant && constant.getValue() instanceof String text
                && BOOLEAN_WORDS.contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    static List<Expression> arguments(Expression arguments) {
        if (arguments instanceof TupleExpression tuple) {
            return tuple.getExpressions();
        }
        return arguments == null ? List.of() : List.of(arguments);
    }

    /** GS071: a variable, field or parameter declared without a type and assigned values of two kinds. */
    private void manyKinds(Object key, List<Write> list) {
        if (!(key instanceof Variable variable) || !variable.isDynamicTyped() || variable instanceof DynamicVariable) {
            return;
        }
        Value first = null;
        for (Write write : list) {
            Value value = writeValue(write);
            if (value == null) {
                continue;
            }
            if (first == null) {
                first = value;
            } else if (first.kind() != value.kind()) {
                context.report("GS071", write.node(), write.name() + " holds " + first.display() + " (line "
                        + first.line() + ") and " + value.display() + " (line " + value.line() + "): declare one type, "
                        + "or keep the second value in a second variable", weaker(first.confidence(),
                        value.confidence()), null, List.of());
                return;
            }
        }
    }

    /** The kind a write stores, with the line of the write; {@code null} when unknown, null, or adding nothing. */
    private Value writeValue(Write write) {
        if (write.value() == null || isNull(write.value())) {
            return null;
        }
        Value value = valueOf(write.value());
        if (value == null || write.append() && value.kind() != Kind.STRING) {
            return null;
        }
        return new Value(value.kind(), value.type(), value.display(), value.confidence(), write.line());
    }

    static boolean isNull(Expression expression) {
        return expression instanceof ConstantExpression constant && constant.getValue() == null;
    }

    /**
     * The kind of a {@code def} local from every value its method assigns it: all known and of one kind; a write
     * that only feeds the variable back to itself ({@code count = count + 1}) adds nothing.
     */
    private Value inferredOf(Object key) {
        if (inferred.containsKey(key)) {
            return inferred.get(key);
        }
        if (!dynamicLocals.contains(key) || !inferring.add(key)) {
            return null;
        }
        Value result = null;
        boolean known = true;
        for (Write write : writes.getOrDefault(key, List.of())) {
            if (write.value() == null) {
                known = false;
                break;
            }
            if (isNull(write.value()) || readsItself(write.value(), key)) {
                continue;
            }
            Value value = valueOf(write.value());
            if (write.append() && (value == null || value.kind() != Kind.STRING)) {
                continue;
            }
            if (value == null || result != null && result.kind() != value.kind()) {
                known = false;
                break;
            }
            if (result == null) {
                result = new Value(value.kind(), value.type(), value.display(), Confidence.INFERRED, write.line());
            }
        }
        inferring.remove(key);
        Value answer = known ? result : null;
        inferred.put(key, answer);
        return answer;
    }

    private static boolean readsItself(Expression value, Object key) {
        if (!(value instanceof BinaryExpression binary)) {
            return false;
        }
        for (Expression side : List.of(binary.getLeftExpression(), binary.getRightExpression())) {
            if (side instanceof VariableExpression variable && keyOf(variable) == key) {
                return true;
            }
        }
        return false;
    }

    /** The kind of a type, or {@code null} for {@code def}, {@code Object}, a type parameter and the rest. */
    static Kind kindOf(ClassNode type) {
        if (type == null || type.isGenericsPlaceHolder() || ClassHelper.isDynamicTyped(type)) {
            return null;
        }
        if (type.isArray()) {
            return Kind.ARRAY;
        }
        String name = type.getName();
        if (STRING_TYPES.contains(name)) {
            return Kind.STRING;
        }
        if (NUMBER_TYPES.contains(name)) {
            return Kind.NUMBER;
        }
        if (BOOLEAN_TYPES.contains(name)) {
            return Kind.BOOLEAN;
        }
        if (CHAR_TYPES.contains(name)) {
            return Kind.CHAR;
        }
        if (COLLECTION_TYPES.contains(name)) {
            return Kind.LIST;
        }
        return MAP_TYPES.contains(name) ? Kind.MAP : null;
    }

    /** The kind of a literal: {@code 'x'}, {@code 5}, {@code -1}, {@code true}, a GString, {@code []}, {@code [:]}. */
    static Kind literalKind(Expression expression) {
        if (expression instanceof ConstantExpression constant) {
            Object value = constant.getValue();
            if (value instanceof String) {
                return Kind.STRING;
            }
            if (value instanceof Number) {
                return Kind.NUMBER;
            }
            if (value instanceof Boolean) {
                return Kind.BOOLEAN;
            }
            return value instanceof Character ? Kind.CHAR : null;
        }
        if (expression instanceof UnaryMinusExpression minus) {
            return literalKind(minus.getExpression()) == Kind.NUMBER ? Kind.NUMBER : null;
        }
        if (expression instanceof GStringExpression) {
            return Kind.STRING;
        }
        if (expression instanceof ListExpression) {
            return Kind.LIST;
        }
        return expression instanceof MapExpression ? Kind.MAP : null;
    }

    private static Value known(Kind kind, ClassNode type, String display) {
        return new Value(kind, type, display, Confidence.STATIC, 0);
    }

    private static Value declared(ClassNode type) {
        Kind kind = kindOf(type);
        return kind == null ? null : known(kind, type, TypeNames.simple(type));
    }

    private static Confidence weaker(Confidence left, Confidence right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /** What is known about the value of an expression, or {@code null}. */
    Value valueOf(Expression expression) {
        if (expression instanceof ConstantExpression constant) {
            Object value = constant.getValue();
            if (value == null) {
                return null;
            }
            Kind kind = literalKind(constant);
            ClassNode type = ClassHelper.make(value.getClass());
            return kind == null ? null : known(kind, type, type.getNameWithoutPackage());
        }
        if (expression instanceof GStringExpression) {
            return known(Kind.STRING, ClassHelper.GSTRING_TYPE, "GString");
        }
        if (expression instanceof ListExpression) {
            return known(Kind.LIST, ClassHelper.LIST_TYPE, "List");
        }
        if (expression instanceof MapExpression) {
            return known(Kind.MAP, ClassHelper.MAP_TYPE, "Map");
        }
        if (expression instanceof UnaryMinusExpression || expression instanceof UnaryPlusExpression) {
            Expression inner = expression instanceof UnaryMinusExpression minus ? minus.getExpression()
                    : ((UnaryPlusExpression) expression).getExpression();
            Value value = valueOf(inner);
            return value != null && value.kind() == Kind.NUMBER ? value : null;
        }
        if (expression instanceof BooleanExpression) {
            return known(Kind.BOOLEAN, ClassHelper.boolean_TYPE, "boolean");
        }
        if (expression instanceof BinaryExpression binary) {
            return binaryValue(binary);
        }
        if (expression instanceof TernaryExpression ternary) {
            Expression whenTrue = ternary instanceof ElvisOperatorExpression ? ternary.getBooleanExpression()
                    .getExpression() : ternary.getTrueExpression();
            Value left = isNull(whenTrue) ? null : valueOf(whenTrue);
            Value right = isNull(ternary.getFalseExpression()) ? null : valueOf(ternary.getFalseExpression());
            if (left == null || right == null) {
                return isNull(whenTrue) ? right : isNull(ternary.getFalseExpression()) ? left : null;
            }
            return left.kind() == right.kind() ? new Value(left.kind(), left.type(), left.display(),
                    weaker(left.confidence(), right.confidence()), Math.max(left.line(), right.line())) : null;
        }
        if (expression instanceof CastExpression cast) {
            return declared(cast.getType());
        }
        if (expression instanceof ConstructorCallExpression constructor) {
            return constructor.isSpecialCall() ? null : declared(constructor.getType());
        }
        if (expression instanceof VariableExpression variable) {
            return variableValue(variable);
        }
        if (expression instanceof PropertyExpression property) {
            Value receiver = valueOf(property.getObjectExpression());
            if (receiver != null && receiver.kind() == Kind.MAP || property.isSpreadSafe()) {
                return null; // a property of a map is the value under that key, whatever getter the type declares
            }
            return declared(PracticeFinder.typeOf(property));
        }
        if (expression instanceof MethodCallExpression call) {
            return callValue(call);
        }
        if (expression instanceof StaticMethodCallExpression call) {
            return declared(PracticeFinder.typeOf(call));
        }
        return null;
    }

    private Value variableValue(VariableExpression variable) {
        if (variable.isThisExpression() || variable.isSuperExpression()) {
            return null;
        }
        Variable accessed = variable.getAccessedVariable() == null ? variable : variable.getAccessedVariable();
        if (accessed instanceof DynamicVariable) {
            return null;
        }
        if (!accessed.isDynamicTyped()) {
            return declared(accessed.getOriginType());
        }
        Object key = keyOf(variable);
        return key == null ? null : inferredOf(key);
    }

    private Value callValue(MethodCallExpression call) {
        String name = call.getMethodAsString();
        if (name == null || call.isSpreadSafe()) {
            return null;
        }
        if ("toString".equals(name) && arguments(call.getArguments()).isEmpty()) {
            return known(Kind.STRING, ClassHelper.STRING_TYPE, "String");
        }
        if (implicitThis(call)) {
            return declared(singleReturn(Members.accepting(Members.methodsNamed(owner, name),
                    Members.argumentCount(call.getArguments()))));
        }
        return declared(PracticeFinder.typeOf(call));
    }

    private static boolean implicitThis(MethodCallExpression call) {
        return call.isImplicitThis()
                || call.getObjectExpression() instanceof VariableExpression receiver && receiver.isThisExpression();
    }

    /** The one return type every candidate declares, or {@code null}. */
    private static ClassNode singleReturn(List<MethodNode> candidates) {
        ClassNode type = null;
        for (MethodNode method : candidates) {
            if (method.isDynamicReturnType()) {
                return null;
            }
            if (type == null) {
                type = method.getReturnType();
            } else if (!type.getName().equals(method.getReturnType().getName())) {
                return null;
            }
        }
        return type;
    }

    private Value binaryValue(BinaryExpression binary) {
        int operation = binary.getOperation().getType();
        switch (operation) {
            case Types.COMPARE_EQUAL, Types.COMPARE_NOT_EQUAL, Types.COMPARE_LESS_THAN,
                    Types.COMPARE_LESS_THAN_EQUAL, Types.COMPARE_GREATER_THAN, Types.COMPARE_GREATER_THAN_EQUAL,
                    Types.COMPARE_IDENTICAL, Types.COMPARE_NOT_IDENTICAL, Types.KEYWORD_IN, Types.COMPARE_NOT_IN,
                    Types.KEYWORD_INSTANCEOF, Types.COMPARE_NOT_INSTANCEOF, Types.LOGICAL_AND, Types.LOGICAL_OR,
                    Types.MATCH_REGEX -> {
                return known(Kind.BOOLEAN, ClassHelper.boolean_TYPE, "boolean");
            }
            case Types.COMPARE_TO -> {
                return known(Kind.NUMBER, ClassHelper.int_TYPE, "int");
            }
            default -> {
                // arithmetic below
            }
        }
        if (operation != Types.PLUS && operation != Types.MINUS && operation != Types.MULTIPLY
                && operation != Types.DIVIDE && operation != Types.MOD && operation != Types.POWER
                && operation != Types.INTDIV) {
            return null;
        }
        Value left = valueOf(binary.getLeftExpression());
        Value right = valueOf(binary.getRightExpression());
        if (left == null) {
            return null;
        }
        if (left.kind() == Kind.STRING && (operation == Types.PLUS || operation == Types.MINUS
                || operation == Types.MULTIPLY)) {
            return known(Kind.STRING, ClassHelper.STRING_TYPE, "String").withConfidence(left.confidence());
        }
        if (operation == Types.PLUS && left.kind() == Kind.LIST) {
            return left;
        }
        if (right == null) {
            return null;
        }
        if (operation == Types.PLUS && left.kind() == Kind.NUMBER && right.kind() == Kind.STRING) {
            return known(Kind.STRING, ClassHelper.STRING_TYPE, "String").withConfidence(weaker(left.confidence(),
                    right.confidence()));
        }
        if (left.kind() == Kind.NUMBER && right.kind() == Kind.NUMBER) {
            return new Value(Kind.NUMBER, left.type(), left.display(), weaker(left.confidence(), right.confidence()),
                    Math.max(left.line(), right.line()));
        }
        return null;
    }

    /** A short, one line rendering of an expression for a message. */
    private String shown(ASTNode node) {
        String text = context.source(node);
        if (text == null) {
            text = node instanceof Expression expression ? expression.getText() : node.getText();
        }
        text = text.strip().replaceAll("\\s+", " ");
        return text.length() > SHOWN_LENGTH ? text.substring(0, SHOWN_LENGTH - 3) + "..." : text;
    }

    /** How a message says where a known kind comes from: the declared type, or the inference and its line. */
    private static String origin(Value value) {
        return value.confidence() == Confidence.STATIC ? value.display()
                : value.display() + " (inferred from line " + value.line() + ")";
    }

    /** Whether storing, comparing or returning a literal of one kind against another needs a conversion. */
    static boolean mixes(Kind one, Kind other) {
        return one != null && other != null && one != other && SCALARS.contains(one) && SCALARS.contains(other);
    }

    /** Whether Groovy finds no method when an argument of one kind meets a parameter of another. */
    static boolean failsAsArgument(Kind argument, Kind parameter) {
        if (argument == null || parameter == null || argument == parameter) {
            return false;
        }
        if (ARGUMENT_SCALARS.contains(argument) && ARGUMENT_SCALARS.contains(parameter)) {
            return true;
        }
        return argument == Kind.LIST && parameter == Kind.ARRAY || argument == Kind.ARRAY && parameter == Kind.LIST;
    }

    /** What Groovy does with a literal of kind {@code literal} stored in, or returned as, kind {@code target}. */
    static String stored(Kind literal, Kind target) {
        return switch (target) {
            case NUMBER -> literal == Kind.STRING
                    ? "Groovy stores the character code of a one character text ('5' becomes 53) and fails on longer "
                    + "text" : "Groovy fails at run time";
            case STRING -> "Groovy stores its text, so the value changes kind unseen";
            case BOOLEAN -> literal == Kind.STRING ? "by Groovy truth any text but '' is true, 'false' included"
                    : "by Groovy truth any number but 0 is true";
            default -> "Groovy converts it at run time";
        };
    }

    /**
     * Whether an expression is a variable the class compares with {@code 'true'} or {@code 'false'}: text that holds
     * a flag. Other text stored as a boolean (a name tested for being empty) is Groovy truth on purpose.
     */
    private boolean booleanFlag(Expression expression) {
        return expression instanceof VariableExpression variable
                && comparedWithBooleanWords.contains(keyOf(variable));
    }

    /** GS070 (a literal) and GS072 (text) stored into a declared type: a declaration, an assignment or a field. */
    private void assigned(ClassNode target, String name, Expression value) {
        Kind targetKind = kindOf(target);
        if (targetKind == null) {
            return;
        }
        Kind literal = literalKind(value);
        if (mixes(literal, targetKind)) {
            context.report("GS070", value, shown(value) + " is " + literal.words + " assigned to " + name
                    + ", declared " + TypeNames.simple(target) + ": " + stored(literal, targetKind) + "; assign "
                    + targetKind.words + ", or convert the value once where it enters", Confidence.STATIC, null,
                    List.of());
        } else if (literal == null && targetKind == Kind.BOOLEAN) {
            Value text = valueOf(value);
            if (text != null && text.kind() == Kind.STRING && booleanFlag(value)) {
                context.report("GS072", value, shown(value) + " is text (" + origin(text) + ") stored in " + name
                        + ", declared " + TypeNames.simple(target) + ": by Groovy truth any text but '' is true, "
                        + "'false' included; parse it with Boolean.parseBoolean(" + shown(value) + ")",
                        text.confidence(), null, List.of());
            }
        }
    }

    /** Pass two: the checks on comparisons, calls, properties, conditions, returns and map writes of one method. */
    private final class Checker extends CodeVisitorSupport {

        private final MethodNode method;
        private int closures;
        private final List<Expression> returned = new ArrayList<>();
        private final Map<String, List<MapWrite>> mapWrites = new LinkedHashMap<>();
        private final Map<String, String> mapNames = new HashMap<>();

        Checker(MethodNode method) {
            this.method = method;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression declaration) {
            if (!declaration.isMultipleAssignmentDeclaration()) {
                VariableExpression variable = declaration.getVariableExpression();
                Expression right = declaration.getRightExpression();
                if (!(right instanceof EmptyExpression)) {
                    if (!variable.isDynamicTyped()) {
                        assigned(variable.getOriginType(), variable.getName(), right);
                    }
                    if (right instanceof MapExpression map) {
                        mapLiteral(variable, map);
                    }
                }
            }
            declaration.getRightExpression().visit(this); // not the visit of the binary expression it also is
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            int operation = expression.getOperation().getType();
            Expression left = expression.getLeftExpression();
            Expression right = expression.getRightExpression();
            switch (operation) {
                case Types.ASSIGN -> assignment(left, right);
                case Types.COMPARE_EQUAL, Types.COMPARE_NOT_EQUAL -> {
                    compared(expression, left, right);
                    booleanWordCompared(expression, left, right);
                    arrayAgainstList(expression, left, right);
                }
                case Types.COMPARE_LESS_THAN, Types.COMPARE_LESS_THAN_EQUAL, Types.COMPARE_GREATER_THAN,
                        Types.COMPARE_GREATER_THAN_EQUAL, Types.COMPARE_TO -> compared(expression, left, right);
                case Types.KEYWORD_IN, Types.COMPARE_NOT_IN -> membership(expression, left, right);
                case Types.LOGICAL_AND, Types.LOGICAL_OR -> {
                    truth(left);
                    truth(right);
                }
                default -> {
                    // nothing to check
                }
            }
            super.visitBinaryExpression(expression);
        }

        private void assignment(Expression left, Expression right) {
            if (left instanceof VariableExpression variable) {
                Variable accessed = variable.getAccessedVariable();
                if (accessed != null && !(accessed instanceof DynamicVariable) && !accessed.isDynamicTyped()) {
                    assigned(accessed.getOriginType(), variable.getName(), right);
                }
                if (right instanceof MapExpression map) {
                    mapLiteral(variable, map);
                }
            } else if (left instanceof PropertyExpression property && property.getPropertyAsString() != null) {
                Value receiver = valueOf(property.getObjectExpression());
                if (receiver != null && receiver.kind() == Kind.MAP) {
                    mapWrite(property.getObjectExpression(), property.getPropertyAsString(), right);
                } else if (!property.isSpreadSafe()) {
                    ClassNode type = PracticeFinder.typeOf(property);
                    if (type != null) {
                        assigned(type, shown(property), right);
                    }
                }
            } else if (left instanceof BinaryExpression index
                    && index.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                    && index.getRightExpression() instanceof ConstantExpression key && key.getValue() instanceof String
                    name) {
                Value receiver = valueOf(index.getLeftExpression());
                if (receiver != null && receiver.kind() == Kind.MAP) {
                    mapWrite(index.getLeftExpression(), name, right);
                }
            }
        }

        /** GS070: a literal compared with a value of another known kind. */
        private void compared(BinaryExpression expression, Expression left, Expression right) {
            Kind leftLiteral = literalKind(left);
            Kind rightLiteral = literalKind(right);
            if (leftLiteral != null == (rightLiteral != null)) {
                return;
            }
            Kind literal = leftLiteral != null ? leftLiteral : rightLiteral;
            Expression other = leftLiteral != null ? right : left;
            Value value = valueOf(other);
            if (value == null || !mixes(literal, value.kind())) {
                return;
            }
            int operation = expression.getOperation().getType();
            String outcome = operation == Types.COMPARE_EQUAL ? "never equal in Groovy"
                    : operation == Types.COMPARE_NOT_EQUAL ? "always unequal in Groovy, so the test is always true"
                    : "Groovy converts neither side, so the order it answers is meaningless or it fails";
            context.report("GS070", expression, shown(expression) + " compares " + value.kind().words + " ("
                    + shown(other) + " is " + origin(value) + ") with " + literal.words + ": " + outcome
                    + "; compare values of one kind, converted once where the value enters", value.confidence(),
                    null, List.of());
        }

        /** GS070 and GS074: {@code x in [literals]} and {@code literal in typedCollection} of another kind. */
        private void membership(BinaryExpression expression, Expression left, Expression right) {
            if (right instanceof ListExpression list && literalKind(left) == null) {
                Kind elements = elementKind(list);
                Value value = valueOf(left);
                if (value != null && mixes(elements, value.kind())) {
                    context.report("GS070", expression, shown(expression) + " looks for " + value.kind().words + " ("
                            + shown(left) + " is " + origin(value) + ") among " + plural(elements) + ": it is never "
                            + "found; list values of one kind", value.confidence(), null, List.of());
                }
                return;
            }
            Kind literal = literalKind(left);
            Value container = literal == null ? null : valueOf(right);
            if (container == null || !SCALARS.contains(literal)) {
                return;
            }
            if (container.kind() == Kind.ARRAY) {
                Kind component = kindOf(container.type().getComponentType());
                if (mixes(literal, component)) {
                    context.report("GS074", expression, shown(expression) + " looks for " + literal.words + " in "
                            + shown(right) + ", " + origin(container) + ": it is never found; look for "
                            + component.words, container.confidence(), null, List.of());
                }
            } else if (container.kind() == Kind.LIST) {
                Kind element = elementKind(container.type());
                if (mixes(literal, element)) {
                    context.report("GS070", expression, shown(expression) + " looks for " + literal.words + " in "
                            + shown(right) + ", " + origin(container) + ": it is never found; look for "
                            + element.words, container.confidence(), null, List.of());
                }
            }
        }

        /** GS072: text compared with {@code 'true'} or {@code 'false'}. */
        private void booleanWordCompared(BinaryExpression expression, Expression left, Expression right) {
            Expression text = isBooleanWord(right) ? left : isBooleanWord(left) ? right : null;
            if (text == null || literalKind(text) != null) {
                return;
            }
            Value value = valueOf(text);
            if (value != null && value.kind() == Kind.STRING) {
                reportBooleanWord(expression, text, value);
            }
        }

        private void reportBooleanWord(ASTNode at, Expression text, Value value) {
            String name = shown(text);
            context.report("GS072", at, shown(at) + " uses text (" + name + " is " + origin(value) + ") as a "
                    + "boolean: parse it once with Boolean.parseBoolean(" + name + ") and keep a boolean; tested "
                    + "directly, Groovy truth makes any text but '' true, 'false' included", value.confidence(),
                    null, List.of());
        }

        /** GS074: {@code ==} between an array and a list literal. */
        private void arrayAgainstList(BinaryExpression expression, Expression left, Expression right) {
            Expression array = right instanceof ListExpression ? left : left instanceof ListExpression ? right : null;
            Value value = array == null ? null : valueOf(array);
            if (value != null && value.kind() == Kind.ARRAY) {
                context.report("GS074", expression, shown(expression) + " compares an array (" + shown(array)
                        + " is " + origin(value) + ") with a list: Groovy matches them element by element, but "
                        + "equals, Java and @CompileStatic code do not; compare " + shown(array) + " as List",
                        value.confidence(), null, List.of());
            }
        }

        /** GS072: a text value tested for truth that the class also compares with {@code 'true'} or {@code 'false'}. */
        private void truth(Expression condition) {
            Expression inner = condition instanceof BooleanExpression wrapped && !(condition instanceof NotExpression)
                    ? wrapped.getExpression() : condition;
            if (!(inner instanceof VariableExpression variable)) {
                return;
            }
            Object key = keyOf(variable);
            if (key == null || !comparedWithBooleanWords.contains(key)) {
                return;
            }
            Value value = valueOf(variable);
            if (value != null && value.kind() == Kind.STRING) {
                context.report("GS072", variable, variable.getName() + " is text (" + origin(value) + ") tested "
                        + "for truth and compared with 'true' or 'false' elsewhere: by Groovy truth any text but '' "
                        + "is true, 'false' included; parse it once with Boolean.parseBoolean(" + variable.getName()
                        + ")", value.confidence(), null, List.of());
            }
        }

        @Override
        public void visitBooleanExpression(BooleanExpression expression) {
            truth(expression.getExpression());
            super.visitBooleanExpression(expression);
        }

        @Override
        public void visitNotExpression(NotExpression expression) {
            truth(expression.getExpression());
            super.visitNotExpression(expression);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            if ("length".equals(expression.getPropertyAsString()) && !expression.isSpreadSafe()) {
                lengthOfCollection(expression, expression.getObjectExpression(), expression.getProperty());
            }
            super.visitPropertyExpression(expression);
        }

        /** GS074: {@code .length} on a collection, with {@code size()} as the fix. */
        private void lengthOfCollection(Expression at, Expression receiver, Expression name) {
            Value value = valueOf(receiver);
            if (value == null || value.kind() != Kind.LIST) {
                return;
            }
            Range range = AstRanges.rangeOf(name);
            boolean property = at instanceof PropertyExpression;
            List<TextEdit> fix = List.of();
            if (range != null && range.start().line() == range.end().line()
                    && "length".equals(context.lines().slice(range))) {
                fix = List.of(new TextEdit(range, property ? "size()" : "size"));
            } else {
                Range whole = AstRanges.rangeOf(at);
                String text = whole == null ? null : context.lines().slice(whole);
                String tail = property ? "length" : "length()";
                if (text != null && text.endsWith(tail) && whole.start().line() == whole.end().line()) {
                    Position end = whole.end();
                    fix = List.of(new TextEdit(new Range(new Position(end.line(), end.column() - tail.length()), end),
                            "size()"));
                }
            }
            context.report("GS074", at, shown(at) + ": " + shown(receiver) + " is " + origin(value) + ", which has "
                    + "no length; Groovy looks it up on each element instead and fails or answers a list; use "
                    + shown(receiver) + ".size()", value.confidence(), fix.isEmpty() ? null : "Use size()", fix);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            List<Expression> arguments = arguments(call.getArguments());
            if (name != null && !call.isSpreadSafe()) {
                if ("length".equals(name) && arguments.isEmpty()) {
                    lengthOfCollection(call, call.getObjectExpression(), call.getMethod());
                } else if (("equals".equals(name) || "equalsIgnoreCase".equals(name)) && arguments.size() == 1) {
                    Expression text = isBooleanWord(arguments.get(0)) ? call.getObjectExpression()
                            : isBooleanWord(call.getObjectExpression()) ? arguments.get(0) : null;
                    Value value = text == null || literalKind(text) != null ? null : valueOf(text);
                    if (value != null && value.kind() == Kind.STRING) {
                        reportBooleanWord(call, text, value);
                    }
                } else if ("put".equals(name) && arguments.size() == 2
                        && arguments.get(0) instanceof ConstantExpression key && key.getValue() instanceof String
                        keyName) {
                    Value receiver = valueOf(call.getObjectExpression());
                    if (receiver != null && receiver.kind() == Kind.MAP) {
                        mapWrite(call.getObjectExpression(), keyName, arguments.get(1));
                    }
                }
                Target target = target(call, name);
                if (target != null) {
                    passed(call, name, target, arguments);
                }
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            List<MethodNode> candidates = Members.accepting(Members.methodsNamed(call.getOwnerType(), call.getMethod()),
                    Members.argumentCount(call.getArguments()));
            if (candidates.size() == 1 && closures == 0) {
                passed(call, call.getMethod(), new Target(candidates.get(0), Confidence.STATIC),
                        arguments(call.getArguments()));
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            ClassNode type = call.getType();
            if (!call.isSpecialCall() && Members.isKnown(type) && !type.isInterface()) {
                List<MethodNode> constructors = new ArrayList<>(Members.readable(type::getDeclaredConstructors));
                List<MethodNode> candidates = Members.accepting(constructors,
                        Members.argumentCount(call.getArguments()));
                if (candidates.size() == 1) {
                    passed(call, "new " + type.getNameWithoutPackage(), new Target(candidates.get(0),
                            Confidence.STATIC), arguments(call.getArguments()));
                }
            }
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            if (expression instanceof ElvisOperatorExpression) {
                // ?: defaults an empty or missing value: Groovy truth is what it is for, so it is no test of text
                expression.getTrueExpression().visit(this);
                expression.getFalseExpression().visit(this);
            } else {
                super.visitTernaryExpression(expression);
            }
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            closures++;
            super.visitClosureExpression(expression);
            closures--;
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            if (closures == 0) {
                returned.add(statement.getExpression());
                returnedValue(statement.getExpression());
            }
            super.visitReturnStatement(statement);
        }

        /** GS070 and GS072 on a value returned from a method that declares its return type. */
        private void returnedValue(Expression value) {
            if (method.isDynamicReturnType() || method.isVoidMethod() || method instanceof org.codehaus.groovy.ast
                    .ConstructorNode) {
                return;
            }
            ClassNode type = method.getReturnType();
            Kind target = kindOf(type);
            Kind literal = literalKind(value);
            if (mixes(literal, target)) {
                context.report("GS070", value, "return " + shown(value) + " gives " + literal.words + " where "
                        + method.getName() + " declares " + TypeNames.simple(type) + ": " + stored(literal, target)
                        + "; return " + target.words, Confidence.STATIC, null, List.of());
            } else if (literal == null && target == Kind.BOOLEAN) {
                Value text = valueOf(value);
                if (text != null && text.kind() == Kind.STRING && booleanFlag(value)) {
                    context.report("GS072", value, "return " + shown(value) + " gives text (" + origin(text)
                            + ") where " + method.getName() + " declares " + TypeNames.simple(type) + ": by Groovy "
                            + "truth any text but '' is true, 'false' included; parse it with Boolean.parseBoolean",
                            text.confidence(), null, List.of());
                }
            }
        }

        /** The target of a call when it is certain, with how sure: on this class, or on a receiver of known type. */
        private Target target(MethodCallExpression call, String name) {
            int count = Members.argumentCount(call.getArguments());
            if (implicitThis(call)) {
                if (closures > 0 || dispatchHooks || owner.isScript()) {
                    return null;
                }
                List<MethodNode> candidates = Members.accepting(Members.methodsNamed(owner, name), count);
                return candidates.size() == 1 ? new Target(candidates.get(0), Confidence.STATIC) : null;
            }
            Expression receiver = call.getObjectExpression();
            if (receiver instanceof ClassExpression type) {
                List<MethodNode> candidates = new ArrayList<>();
                for (MethodNode method : Members.accepting(Members.methodsNamed(type.getType(), name), count)) {
                    if (method.isStatic()) {
                        candidates.add(method);
                    }
                }
                return candidates.size() == 1 ? new Target(candidates.get(0), Confidence.STATIC) : null;
            }
            Value value = valueOf(receiver);
            if (value == null || value.type() == null || !Members.isKnown(value.type()) || value.kind() == Kind.MAP) {
                return null;
            }
            List<MethodNode> candidates = Members.accepting(Members.methodsNamed(value.type(), name), count);
            return candidates.size() == 1 ? new Target(candidates.get(0), value.confidence()) : null;
        }

        /** GS070 (a literal), GS074 (a list literal for an array) and GS076 (a known value) on each argument. */
        private void passed(Expression call, String name, Target target, List<Expression> arguments) {
            Parameter[] parameters = target.method().getParameters();
            if (arguments.size() != parameters.length) {
                return;
            }
            for (Expression argument : arguments) {
                if (argument instanceof MapExpression || argument instanceof SpreadExpression
                        || argument instanceof MapEntryExpression) {
                    return;
                }
            }
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                ClassNode type = parameter.getType();
                Kind expected = parameter.isDynamicTyped() ? null : kindOf(type);
                if (expected == null) {
                    continue;
                }
                boolean varargs = i == parameters.length - 1 && type.isArray();
                Expression argument = arguments.get(i);
                Kind literal = literalKind(argument);
                String where = name + " declares " + TypeNames.simple(type) + " " + parameter.getName();
                if (literal == Kind.LIST && expected == Kind.ARRAY) {
                    listForArray(argument, type, where, target.confidence());
                } else if (literal != null) {
                    if (!varargs && failsAsArgument(literal, expected)) {
                        context.report("GS070", argument, shown(argument) + " is " + literal.words + " passed where "
                                + where + ": Groovy finds no method for it and fails with MissingMethodException "
                                + "at run time; pass " + expected.words, target.confidence(), null, List.of());
                    }
                } else {
                    Value value = valueOf(argument);
                    if (value != null && (!varargs || value.kind() == Kind.LIST)
                            && failsAsArgument(value.kind(), expected)) {
                        context.report("GS076", argument, shown(argument) + " is " + origin(value) + " passed where "
                                + where + ": Groovy does not convert " + value.kind().words + " into " + expected.words
                                + " for a call and fails with MissingMethodException at run time; convert it first",
                                weaker(target.confidence(), value.confidence()), null, List.of());
                    }
                }
            }
        }

        /** GS074: a list literal passed for an array parameter; the fix converts it with {@code as T[]}. */
        private void listForArray(Expression argument, ClassNode type, String where, Confidence confidence) {
            ClassNode component = type.getComponentType();
            if (component == null || ClassHelper.isObjectType(component) || ClassHelper.isDynamicTyped(component)) {
                return;
            }
            String text = context.source(argument);
            String array = TypeNames.simple(type);
            List<TextEdit> fix = text == null ? List.of() : List.of(context.replace(argument, text + " as " + array));
            context.report("GS074", argument, shown(argument) + " is a list passed where " + where + ": Groovy does "
                    + "not convert a list into an array for a call and fails at run time; pass " + shown(argument)
                    + " as " + array, confidence, fix.isEmpty() ? null : "Convert with as " + array, fix);
        }

        private void mapLiteral(Expression map, MapExpression literal) {
            for (MapEntryExpression entry : literal.getMapEntryExpressions()) {
                if (entry.getKeyExpression() instanceof ConstantExpression key
                        && key.getValue() instanceof String name) {
                    mapWrite(map, name, entry.getValueExpression());
                }
            }
        }

        /** Remembers a write under a literal key of a map held in a variable, for GS075. */
        private void mapWrite(Expression map, String key, Expression value) {
            if (!(map instanceof VariableExpression variable) || isNull(value)) {
                return;
            }
            Object holder = keyOf(variable);
            Value known = valueOf(value);
            if (holder == null || known == null) {
                return;
            }
            Value receiver = valueOf(variable);
            if (receiver != null) {
                known = known.withConfidence(weaker(known.confidence(), receiver.confidence()));
            }
            String id = System.identityHashCode(holder) + "." + key;
            mapNames.putIfAbsent(id, variable.getName() + "." + key);
            mapWrites.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new MapWrite(known, value, value.getLineNumber()));
        }

        /** The checks that need the whole method: GS073 and GS075. */
        void finish() {
            if (method.isDynamicReturnType() && !(method instanceof org.codehaus.groovy.ast.ConstructorNode)) {
                lastExpression(method.getCode());
                mixedReturns();
            }
            for (Map.Entry<String, List<MapWrite>> entry : mapWrites.entrySet()) {
                MapWrite first = entry.getValue().get(0);
                for (MapWrite write : entry.getValue()) {
                    if (write.value().kind() != first.value().kind()) {
                        context.report("GS075", write.node(), mapNames.get(entry.getKey()) + " holds "
                                + first.value().display() + " (line " + first.line() + ") and "
                                + write.value().display() + " (line " + write.line()
                                + "): keep one kind of value per key, or a class with typed "
                                + "properties", weaker(first.value().confidence(), write.value().confidence()), null,
                                List.of());
                        break;
                    }
                }
            }
        }

        /** The value of the last statement, which Groovy returns, followed into the branches of a final if. */
        private void lastExpression(Statement code) {
            Statement last = code;
            while (last instanceof BlockStatement block && !block.getStatements().isEmpty()) {
                last = block.getStatements().get(block.getStatements().size() - 1);
            }
            if (last instanceof ExpressionStatement statement && !(statement.getExpression()
                    instanceof DeclarationExpression)) {
                returned.add(statement.getExpression());
            } else if (last instanceof IfStatement branch) {
                lastExpression(branch.getIfBlock());
                lastExpression(branch.getElseBlock());
            }
        }

        /** GS073: the method returns values of two kinds. */
        private void mixedReturns() {
            Value first = null;
            for (Expression expression : returned) {
                Value value = isNull(expression) ? null : valueOf(expression);
                if (value == null) {
                    continue;
                }
                int line = expression.getLineNumber();
                if (first == null) {
                    first = new Value(value.kind(), value.type(), value.display(), value.confidence(), line);
                } else if (first.kind() != value.kind()) {
                    Range name = AstRanges.nameRange(method, AstRanges.rangeOf(method), context.lines());
                    context.report("GS073", name, method.getName() + " returns " + first.display() + " (line "
                            + first.line() + ") and " + value.display() + " (line " + line + "): declare the return "
                            + "type and return one kind; a result object carries several outcomes",
                            weaker(first.confidence(), value.confidence()), null, List.of());
                    return;
                }
            }
        }
    }

    /** A call's certain target and how sure it is. */
    private record Target(MethodNode method, Confidence confidence) {
    }

    /** The one kind of a list literal's elements, or {@code null} when they are empty, mixed or not literals. */
    static Kind elementKind(ListExpression list) {
        Kind kind = null;
        for (Expression element : list.getExpressions()) {
            Kind one = literalKind(element);
            if (one == null || kind != null && kind != one) {
                return null;
            }
            kind = one;
        }
        return kind;
    }

    /** The kind of a collection type's declared element type ({@code List<Integer>}), or {@code null}. */
    static Kind elementKind(ClassNode collection) {
        GenericsType[] generics = collection == null ? null : collection.getGenericsTypes();
        if (generics == null || generics.length != 1 || generics[0].isPlaceholder() || generics[0].isWildcard()) {
            return null;
        }
        return kindOf(generics[0].getType());
    }

    private static String plural(Kind kind) {
        return switch (kind) {
            case STRING -> "texts";
            case NUMBER -> "numbers";
            case BOOLEAN -> "booleans";
            default -> kind.words;
        };
    }
}
