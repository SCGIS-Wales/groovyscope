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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.semantics.Members;

/**
 * What one method does with state, read from its resolved tree for the design facts: the fields of its own class it
 * reads and writes, the methods of its own class it calls, whether it needs an instance at all, the fields and
 * accessors of other workspace classes it uses, and the branches it takes on the type of a value. Whatever cannot be
 * resolved counts as using the instance, so a method is never called stateless on a guess.
 */
final class DesignScan extends CodeVisitorSupport {

    /** Calls without a receiver that Groovy adds to every object and that never read the object. */
    private static final Set<String> DETACHED_CALLS = Set.of("println", "print", "printf", "sprintf", "sleep");

    /** One branch taken on a type: the type tested, whether the workspace declares it, the lines it runs (1-based). */
    record Branch(String type, boolean workspace, int startLine, int endLine) {
    }

    /** Three or more branches on the type of one value, in an if chain or a switch. */
    record Switch(String subject, String kind, int line, List<Branch> branches) {
    }

    /** One test of a chain: the value tested and the branch it guards. */
    private record Test(String subject, Branch branch) {
    }

    private final ClassNode owner;
    /** Instance fields declared by the owner that the method reads. */
    final Set<String> reads = new TreeSet<>();
    /** Instance fields declared by the owner that the method writes. */
    final Set<String> writes = new TreeSet<>();
    /** Methods declared by the owner that the method calls on itself. */
    final Set<String> ownCalls = new TreeSet<>();
    /** Uses of the owner's own fields, properties and accessors, inherited and static ones included. */
    int ownUses;
    /** Whether the method needs an instance: this, super, an instance member, or anything left unresolved. */
    boolean instanceState;
    /** Workspace classes whose fields or accessors the method uses, with how often it reads and writes them. */
    final Map<String, int[]> foreign = new TreeMap<>();
    /** Workspace classes the method calls or reads members of through a value of that class, with how often. */
    final Map<String, Integer> receivers = new TreeMap<>();
    /** Calls and member reads on values, every receiver counted. */
    int calls;
    /** The type switches of the method. */
    final List<Switch> switches = new ArrayList<>();
    private final Set<IfStatement> chained = Collections.newSetFromMap(new IdentityHashMap<>());

    private DesignScan(ClassNode owner) {
        this.owner = owner;
    }

    /** The scan of a method's body, parameter defaults included. */
    static DesignScan of(MethodNode method) {
        DesignScan scan = new DesignScan(method.getDeclaringClass());
        for (org.codehaus.groovy.ast.Parameter parameter : method.getParameters()) {
            if (parameter.hasInitialExpression()) {
                parameter.getInitialExpression().visit(scan);
            }
        }
        if (method.getCode() != null) {
            method.getCode().visit(scan);
        }
        return scan;
    }

    /** Every field of its own class the method touches. */
    Set<String> fields() {
        Set<String> all = new TreeSet<>(reads);
        all.addAll(writes);
        return all;
    }

    /**
     * The workspace class whose fields and getters the method reads most, with the reads, or {@code null}. Writes
     * are left out: setting another object's fields is building it, not envying it.
     */
    Map.Entry<String, Integer> mostReadForeign() {
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, int[]> entry : foreign.entrySet()) {
            int reads = entry.getValue()[0];
            if (reads > 0 && (best == null || reads > best.getValue())) {
                best = Map.entry(entry.getKey(), reads);
            }
        }
        return best;
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        int type = expression.getOperation().getType();
        if (Types.isAssignment(type) && !(expression instanceof DeclarationExpression)) {
            assign(expression.getLeftExpression(), type != Types.ASSIGN);
            expression.getRightExpression().visit(this);
            return;
        }
        super.visitBinaryExpression(expression);
    }

    @Override
    public void visitPostfixExpression(PostfixExpression expression) {
        assign(expression.getExpression(), true);
    }

    @Override
    public void visitPrefixExpression(PrefixExpression expression) {
        assign(expression.getExpression(), true);
    }

    private void assign(Expression left, boolean alsoRead) {
        if (left instanceof VariableExpression variable && !variable.isThisExpression()
                && !variable.isSuperExpression()) {
            Variable accessed = variable.getAccessedVariable();
            if (accessed instanceof FieldNode || accessed instanceof PropertyNode) {
                own(accessed, true, alsoRead);
            } else if (accessed instanceof DynamicVariable) {
                instanceState = true;
            }
            return;
        }
        if (left instanceof PropertyExpression property) {
            property(property, true, alsoRead);
            return;
        }
        if (left instanceof FieldExpression field) {
            own(field.getField(), true, alsoRead);
            return;
        }
        left.visit(this);
    }

    @Override
    public void visitVariableExpression(VariableExpression expression) {
        if (expression.isThisExpression() || expression.isSuperExpression()) {
            instanceState = true;
            return;
        }
        Variable accessed = expression.getAccessedVariable();
        if (accessed instanceof FieldNode || accessed instanceof PropertyNode) {
            own(accessed, false, false);
        } else if (accessed instanceof DynamicVariable) {
            instanceState = true;
        }
    }

    @Override
    public void visitFieldExpression(FieldExpression expression) {
        own(expression.getField(), false, false);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression expression) {
        property(expression, false, false);
    }

    /** A read or write of {@code receiver.name}: this class's own member, a static member, or another object's. */
    private void property(PropertyExpression expression, boolean write, boolean alsoRead) {
        Expression receiver = expression.getObjectExpression();
        String name = expression.getPropertyAsString();
        if (isSelf(receiver)) {
            instanceState = true;
            if (name != null) {
                ownUses += write && alsoRead ? 2 : 1;
                if (declaresField(name)) {
                    record(name, write, alsoRead);
                }
            }
            return;
        }
        if (receiver instanceof ClassExpression type) {
            if (sameClass(type.getType(), owner)) {
                ownUses++;
            }
            return;
        }
        receiver.visit(this);
        calls++;
        ClassNode type = receiverType(receiver);
        if (name == null || !isWorkspace(type) || related(owner, type)) {
            return;
        }
        receivers.merge(type.redirect().getName(), 1, Integer::sum);
        if (isInstanceField(type, name)) {
            int[] uses = foreign.computeIfAbsent(type.redirect().getName(), k -> new int[2]);
            if (write) {
                uses[1]++;
            }
            if (!write || alsoRead) {
                uses[0]++;
            }
        }
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        calls++;
        String name = call.getMethodAsString();
        Expression receiver = call.getObjectExpression();
        int arguments = Members.argumentCount(call.getArguments());
        if (call.isImplicitThis()) {
            implicitCall(name, arguments);
        } else if (isSelf(receiver)) {
            instanceState = true;
            if (name != null && !owner.getDeclaredMethods(name).isEmpty()) {
                ownCalls.add(name);
            }
            ownAccessor(name, arguments);
        } else if (receiver instanceof VariableExpression variable && variable.isSuperExpression()) {
            instanceState = true;
        } else {
            receiver.visit(this);
            ClassNode type = receiverType(receiver);
            if (name != null && isWorkspace(type) && !related(owner, type)) {
                receivers.merge(type.redirect().getName(), 1, Integer::sum);
                String field = accessorField(name, arguments);
                if (field != null && isInstanceField(type, field)) {
                    int[] uses = foreign.computeIfAbsent(type.redirect().getName(), k -> new int[2]);
                    uses[name.startsWith("set") ? 1 : 0]++;
                }
            }
        }
        call.getMethod().visit(this);
        call.getArguments().visit(this);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        calls++;
        super.visitStaticMethodCallExpression(call);
    }

    /** A call without a receiver: a method of this class or its supertypes, or a Groovy method every object has. */
    private void implicitCall(String name, int arguments) {
        if (name == null) {
            instanceState = true;
            return;
        }
        List<MethodNode> declared = new ArrayList<>();
        for (MethodNode method : owner.getDeclaredMethods(name)) {
            if (!method.isSynthetic()) {
                declared.add(method);
            }
        }
        if (!declared.isEmpty()) {
            ownCalls.add(name);
            if (declared.stream().anyMatch(method -> !method.isStatic())) {
                instanceState = true;
            }
            return;
        }
        if (ownAccessor(name, arguments)) {
            instanceState = true;
            return;
        }
        List<MethodNode> inherited = Members.methodsNamed(owner, name);
        if (inherited.isEmpty() ? !DETACHED_CALLS.contains(name)
                : inherited.stream().anyMatch(method -> !method.isStatic())) {
            instanceState = true;
        }
    }

    /** A getter or setter of one of this class's fields called on itself; counted as using that field. */
    private boolean ownAccessor(String name, int arguments) {
        String field = name == null ? null : accessorField(name, arguments);
        if (field == null || Members.membersNamed(owner, field).stream()
                .noneMatch(member -> member instanceof FieldNode || member instanceof PropertyNode)) {
            return false;
        }
        ownUses++;
        if (declaresField(field)) {
            record(field, name.startsWith("set"), false);
        }
        return true;
    }

    /** A field or property of this class or of a class around or above it, read or written. */
    private void own(Variable accessed, boolean write, boolean alsoRead) {
        boolean isStatic;
        ClassNode declaring;
        if (accessed instanceof FieldNode field) {
            isStatic = field.isStatic();
            declaring = field.getOwner();
        } else {
            PropertyNode property = (PropertyNode) accessed;
            isStatic = property.isStatic();
            declaring = property.getDeclaringClass();
        }
        if (!isStatic) {
            instanceState = true;
        }
        ownUses += write && alsoRead ? 2 : 1;
        if (!isStatic && declaring != null && sameClass(declaring, owner)) {
            record(accessed.getName(), write, alsoRead);
        }
    }

    private void record(String field, boolean write, boolean alsoRead) {
        if (write) {
            writes.add(field);
        }
        if (!write || alsoRead) {
            reads.add(field);
        }
    }

    private boolean declaresField(String name) {
        FieldNode field = owner.getDeclaredField(name);
        PropertyNode property = owner.getProperty(name);
        return field != null && !field.isStatic() || property != null && !property.isStatic();
    }

    @Override
    public void visitIfElse(IfStatement statement) {
        if (!chained.contains(statement)) {
            List<Test> tests = new ArrayList<>();
            IfStatement current = statement;
            while (current != null) {
                chained.add(current);
                Test test = test(current);
                if (test != null) {
                    tests.add(test);
                }
                current = elseIf(current.getElseBlock());
            }
            addChain(tests, statement.getLineNumber());
        }
        super.visitIfElse(statement);
    }

    /**
     * Consecutive {@code if} statements without an {@code else}, each testing the type of the same value, are a type
     * switch too: the early return form of an else-if chain.
     */
    @Override
    public void visitBlockStatement(BlockStatement block) {
        List<Test> run = new ArrayList<>();
        int start = 0;
        for (Statement statement : block.getStatements()) {
            Test test = statement instanceof IfStatement branch && branch.getElseBlock() instanceof EmptyStatement
                    ? test(branch) : null;
            if (test == null || !run.isEmpty() && !run.get(0).subject().equals(test.subject())) {
                addChain(run, start);
                run = new ArrayList<>();
            }
            if (test != null) {
                if (run.isEmpty()) {
                    start = statement.getLineNumber();
                }
                run.add(test);
            }
        }
        addChain(run, start);
        super.visitBlockStatement(block);
    }

    @Override
    public void visitSwitch(SwitchStatement statement) {
        List<Branch> branches = new ArrayList<>();
        for (CaseStatement option : statement.getCaseStatements()) {
            if (option.getExpression() instanceof ClassExpression type && !empty(option.getCode())) {
                branches.add(new Branch(type.getType().getNameWithoutPackage(), isWorkspace(type.getType()),
                        firstLine(option.getCode()), lastLine(option.getCode())));
            }
        }
        if (branches.size() >= 3) {
            switches.add(new Switch(statement.getExpression().getText(), "switch", statement.getLineNumber(),
                    branches));
        }
        super.visitSwitch(statement);
    }

    /** The tests of one chain, grouped by the value tested; a value tested three times or more is a type switch. */
    private void addChain(List<Test> tests, int line) {
        Map<String, List<Branch>> bySubject = new LinkedHashMap<>();
        for (Test test : tests) {
            bySubject.computeIfAbsent(test.subject(), k -> new ArrayList<>()).add(test.branch());
        }
        for (Map.Entry<String, List<Branch>> entry : bySubject.entrySet()) {
            if (entry.getValue().size() >= 3) {
                switches.add(new Switch(entry.getKey(), "instanceof", line, entry.getValue()));
            }
        }
    }

    /** The type test of an if statement ({@code x instanceof T}, alone or first in an and), or {@code null}. */
    private static Test test(IfStatement statement) {
        BooleanExpression condition = statement.getBooleanExpression();
        Expression expression = condition.getExpression();
        while (expression instanceof BinaryExpression binary
                && binary.getOperation().getType() == Types.LOGICAL_AND) {
            expression = binary.getLeftExpression();
        }
        if (expression instanceof BinaryExpression binary
                && binary.getOperation().getType() == Types.KEYWORD_INSTANCEOF
                && binary.getRightExpression() instanceof ClassExpression type) {
            return new Test(binary.getLeftExpression().getText(), new Branch(type.getType().getNameWithoutPackage(),
                    isWorkspace(type.getType()), firstLine(statement.getIfBlock()), lastLine(statement.getIfBlock())));
        }
        return null;
    }

    /** The if statement an else block continues the chain with, or {@code null}. */
    private static IfStatement elseIf(Statement elseBlock) {
        if (elseBlock instanceof IfStatement next) {
            return next;
        }
        if (elseBlock instanceof BlockStatement block && block.getStatements().size() == 1
                && block.getStatements().get(0) instanceof IfStatement next) {
            return next;
        }
        return null;
    }

    /** A case that falls through to the next one: it shares that case's branch rather than being one. */
    private static boolean empty(Statement code) {
        return code == null || code instanceof EmptyStatement
                || code instanceof BlockStatement block && block.getStatements().isEmpty();
    }

    /** The first line of the code a block runs, its braces left out (1-based). */
    static int firstLine(Statement code) {
        if (code instanceof BlockStatement block && !block.getStatements().isEmpty()) {
            return block.getStatements().get(0).getLineNumber();
        }
        return code == null ? -1 : code.getLineNumber();
    }

    /** The last line of the code a block runs, its braces left out (1-based). */
    static int lastLine(Statement code) {
        if (code instanceof BlockStatement block && !block.getStatements().isEmpty()) {
            return block.getStatements().get(block.getStatements().size() - 1).getLastLineNumber();
        }
        return code == null ? -1 : code.getLastLineNumber();
    }

    private static boolean isSelf(Expression receiver) {
        return receiver instanceof VariableExpression variable && variable.isThisExpression();
    }

    private ClassNode receiverType(Expression receiver) {
        return isSelf(receiver) ? owner : PracticeFinder.typeOf(receiver);
    }

    /** The field a getter ({@code getName()}, {@code isOpen()}) or a setter ({@code setName(x)}) is named for. */
    static String accessorField(String name, int arguments) {
        String rest;
        if (arguments == 0 && name.length() > 3 && name.startsWith("get")) {
            rest = name.substring(3);
        } else if (arguments == 0 && name.length() > 2 && name.startsWith("is")) {
            rest = name.substring(2);
        } else if (arguments == 1 && name.length() > 3 && name.startsWith("set")) {
            rest = name.substring(3);
        } else {
            return null;
        }
        if (!Character.isUpperCase(rest.charAt(0))) {
            return null;
        }
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    /** Whether a type is a class of the workspace (declared in its sources), not an array or a library class. */
    static boolean isWorkspace(ClassNode type) {
        return type != null && !type.isArray() && type.redirect().isPrimaryClassNode();
    }

    /** Whether a type is the owner, one of its supertypes or subtypes, or a class around it. */
    static boolean related(ClassNode owner, ClassNode type) {
        ClassNode other = type.redirect();
        for (ClassNode around = owner; around != null; around = around.getOuterClass()) {
            if (sameClass(around, other) || around.isDerivedFrom(other) || other.isDerivedFrom(around)
                    || around.implementsInterface(other) || other.implementsInterface(around)) {
                return true;
            }
        }
        return false;
    }

    static boolean sameClass(ClassNode left, ClassNode right) {
        return left.redirect().getName().equals(right.redirect().getName());
    }

    /** Whether a workspace type has an instance field or property by that name, its superclasses included. */
    static boolean isInstanceField(ClassNode type, String name) {
        for (ASTNode member : Members.membersNamed(type, name)) {
            if (member instanceof FieldNode field && !field.isStatic()
                    || member instanceof PropertyNode property && !property.isStatic()) {
                return true;
            }
        }
        return false;
    }
}
