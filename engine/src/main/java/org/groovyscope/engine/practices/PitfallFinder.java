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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.RecordComponentNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * Groovy pitfalls proven from the tree and declared types: {@code return} inside {@code each} (GS037), a GString
 * looked up among Strings (GS018), a number used as a condition (GS028), a public {@code Closure} parameter the type
 * checker cannot see into (GS017), a multi-line closure on the implicit {@code it} (GS029) and a record that keeps a
 * mutable component as given (GS034).
 */
final class PitfallFinder {

    static final Set<String> EACH_METHODS = Set.of("each", "eachWithIndex", "reverseEach");
    static final Set<String> COLLECTION_LOOKUPS = Set.of("contains", "indexOf", "lastIndexOf");
    static final Set<String> MAP_LOOKUPS = Set.of("get", "containsKey", "remove", "getAt");
    static final Set<String> BOXED_NUMBERS = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger",
            "java.lang.Number");
    static final Set<String> PRIMITIVE_NUMBERS = Set.of("int", "long", "short", "byte", "double", "float");
    static final Set<String> MUTABLE_TYPES = Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Collection", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.Map",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.Date", "java.lang.StringBuilder");
    static final Set<String> CLOSURE_HINTS = Set.of("DelegatesTo", "ClosureParams");

    private PitfallFinder() {
    }

    static void find(PracticeContext context) {
        Visitor visitor = new Visitor(context);
        for (ClassNode classNode : context.module().getClasses()) {
            checkRecord(context, classNode);
            checkClosureParameters(context, classNode);
            visitor.visitClass(classNode);
        }
    }

    /** GS034: a record component of a mutable type that no constructor assigns (copies). */
    static void checkRecord(PracticeContext context, ClassNode classNode) {
        if (!classNode.isRecord() || classNode.getRecordComponents() == null) {
            return;
        }
        String text = context.source(classNode);
        List<String> exposed = new ArrayList<>();
        for (RecordComponentNode component : classNode.getRecordComponents()) {
            ClassNode type = component.getType();
            boolean mutable = type.isArray() || MUTABLE_TYPES.contains(type.getName());
            if (mutable && (text == null || !Pattern.compile("\\b" + Pattern.quote(component.getName())
                    + "\\s*=(?!=)").matcher(text).find())) {
                exposed.add(component.getName());
            }
        }
        if (!exposed.isEmpty()) {
            context.report("GS034", classNode, "record " + classNode.getNameWithoutPackage() + " keeps "
                    + String.join(", ", exposed) + " as given, so the caller can still change it; copy it in a "
                    + "compact constructor (" + exposed.get(0) + " = List.copyOf(" + exposed.get(0) + "), or the "
                    + "matching copy for the type)");
        }
    }

    /** GS017: a public method's Closure parameter with neither @DelegatesTo nor @ClosureParams. */
    static void checkClosureParameters(PracticeContext context, ClassNode classNode) {
        if (context.test() || context.gradle() || context.jenkins()) {
            return;
        }
        for (MethodNode method : classNode.getMethods()) {
            if (method.isSynthetic() || method.getLineNumber() < 1 || method.isScriptBody()
                    || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                if (!parameter.isDynamicTyped() && ClassHelper.CLOSURE_TYPE.getName().equals(parameter.getType().getName())
                        && parameter.getAnnotations().stream().map(AnnotationNode::getClassNode)
                                .noneMatch(type -> CLOSURE_HINTS.contains(type.getNameWithoutPackage()))) {
                    context.report("GS017", parameter, "'" + parameter.getName() + "' of " + method.getName()
                            + " is a Closure the type checker cannot see into: annotate it @DelegatesTo(Type) for a "
                            + "DSL body or @ClosureParams for the arguments it receives");
                }
            }
        }
    }

    private static boolean ofStrings(ClassNode type, boolean map) {
        if (type == null) {
            return false;
        }
        boolean kind = map ? type.equals(ClassHelper.MAP_TYPE) || type.implementsInterface(ClassHelper.MAP_TYPE)
                : type.equals(ClassHelper.make(java.util.Collection.class))
                        || type.implementsInterface(ClassHelper.make(java.util.Collection.class));
        GenericsType[] generics = type.getGenericsTypes();
        return kind && generics != null && generics.length > 0 && generics[0].getType() != null
                && ClassHelper.STRING_TYPE.getName().equals(generics[0].getType().getName());
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final PracticeContext context;

        Visitor(PracticeContext context) {
            this.context = context;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return context.module().getContext();
        }

        private void lookup(GStringExpression gstring, String where) {
            String text = context.source(gstring);
            context.report("GS018", gstring, "a GString never equals a String (equals and hashCode differ), so "
                    + where + " misses; convert it with toString()", "Convert to String",
                    text == null ? List.of() : List.of(context.replace(gstring, text + ".toString()")));
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            List<Expression> args = call.getArguments() instanceof TupleExpression tuple ? tuple.getExpressions()
                    : List.of();
            if (name != null && args.size() == 1 && args.get(0) instanceof GStringExpression gstring) {
                ClassNode receiver = PracticeFinder.typeOf(call.getObjectExpression());
                if (COLLECTION_LOOKUPS.contains(name) && ofStrings(receiver, false)) {
                    lookup(gstring, name + " on a collection of Strings");
                } else if (MAP_LOOKUPS.contains(name) && ofStrings(receiver, true)) {
                    lookup(gstring, name + " on a map with String keys");
                } else if ("equals".equals(name) && receiver != null
                        && ClassHelper.STRING_TYPE.getName().equals(receiver.getName())) {
                    lookup(gstring, "String.equals");
                }
            }
            if (name != null && EACH_METHODS.contains(name) && !args.isEmpty()
                    && args.get(args.size() - 1) instanceof ClosureExpression closure) {
                checkReturns(closure, name);
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            int op = expression.getOperation().getType();
            if (op == Types.KEYWORD_IN && expression.getLeftExpression() instanceof GStringExpression gstring) {
                ClassNode container = PracticeFinder.typeOf(expression.getRightExpression());
                if (ofStrings(container, false) || ofStrings(container, true)) {
                    lookup(gstring, "'in'");
                }
            }
            if (op == Types.LEFT_SQUARE_BRACKET && expression.getRightExpression() instanceof GStringExpression gstring
                    && ofStrings(PracticeFinder.typeOf(expression.getLeftExpression()), true)) {
                lookup(gstring, "the subscript on a map with String keys");
            }
            if (op == Types.LOGICAL_AND || op == Types.LOGICAL_OR) {
                // `count || count == 0` already says what it means about zero
                if (!comparedBeside(expression.getLeftExpression(), expression.getRightExpression())) {
                    condition(expression.getLeftExpression());
                }
                if (!comparedBeside(expression.getRightExpression(), expression.getLeftExpression())) {
                    condition(expression.getRightExpression());
                }
            }
            super.visitBinaryExpression(expression);
        }

        /** Whether {@code other} compares {@code operand} explicitly (to 0, null or anything else). */
        private boolean comparedBeside(Expression operand, Expression other) {
            String text = context.source(operand instanceof BooleanExpression bool ? bool.getExpression() : operand);
            String beside = context.source(other);
            return text != null && beside != null && Pattern.compile(Pattern.quote(text) + "\\s*(==|!=|<|>|<=|>=)")
                    .matcher(beside).find();
        }

        /** GS037: an explicit return in an each closure only ends that element's turn. */
        private void checkReturns(ClosureExpression closure, String method) {
            if (closure.getCode() == null) {
                return;
            }
            closure.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitReturnStatement(ReturnStatement statement) {
                    String text = context.source(statement);
                    if (text != null && text.startsWith("return") && !statement.isReturningNullOrVoid()) {
                        context.report("GS037", statement, "the value returned inside " + method + " is discarded and "
                                + "the loop goes on with the next element; use find, collect, any or every for a result");
                    } else if (text != null && text.startsWith("return")) {
                        context.reportAtMost(Severity.INFORMATION, "GS037", statement, "return inside " + method
                                + " only skips to the next element, like continue; fine when that is meant, else use "
                                + "find, any or every to stop early");
                    }
                    super.visitReturnStatement(statement);
                }

                @Override
                public void visitClosureExpression(ClosureExpression nested) {
                    // a nested closure's return ends that closure, not this one
                }
            });
        }

        /** GS028: a number standing alone as a condition. */
        private void condition(Expression expression) {
            Expression tested = expression instanceof BooleanExpression bool ? bool.getExpression() : expression;
            if (tested instanceof NotExpression not) {
                condition(not.getExpression());
                return;
            }
            ClassNode type = PracticeFinder.typeOf(tested);
            if (type == null || tested instanceof BinaryExpression) {
                return;
            }
            String text = context.source(tested);
            String name = text == null ? "the value" : "'" + text + "'";
            if (BOXED_NUMBERS.contains(type.getName())) {
                context.report("GS028", tested, name + " is a " + type.getNameWithoutPackage() + " used as a "
                        + "condition: both null and 0 are false; say which you mean (!= null, != 0)");
            } else if (PRIMITIVE_NUMBERS.contains(type.getName())) {
                context.reportAtMost(Severity.INFORMATION, "GS028", tested, name + " is a number used as a "
                        + "condition: 0 is false; " + (text == null ? "compare it" : "write " + text + " != 0")
                        + " to say so");
            }
        }

        @Override
        public void visitIfElse(IfStatement statement) {
            condition(statement.getBooleanExpression());
            super.visitIfElse(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement loop) {
            condition(loop.getBooleanExpression());
            super.visitWhileLoop(loop);
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            if (!(expression instanceof ElvisOperatorExpression)) {
                condition(expression.getBooleanExpression());
            }
            super.visitTernaryExpression(expression);
        }

        @Override
        public void visitNotExpression(NotExpression expression) {
            condition(expression.getExpression());
            super.visitNotExpression(expression);
        }

        /** GS029: a closure spanning lines whose body leans on the implicit it. */
        @Override
        public void visitClosureExpression(ClosureExpression closure) {
            if (!context.gradle() && !closure.isParameterSpecified() && closure.getCode() != null
                    && closure.getLastLineNumber() > closure.getLineNumber() && hasBody(closure) && usesIt(closure)) {
                context.reportAtMost(Severity.INFORMATION, "GS029", closure, "this closure spans "
                        + (closure.getLastLineNumber() - closure.getLineNumber() + 1) + " lines on the implicit 'it'; "
                        + "name the parameter (row -> ...) so the body says what it works on");
            }
            super.visitClosureExpression(closure);
        }

        /**
         * Whether a closure has a body worth naming its parameter for: several statements, or a block statement
         * ({@code if}, a loop); one expression wrapped over lines reads fine with {@code it}.
         */
        private static boolean hasBody(ClosureExpression closure) {
            List<org.codehaus.groovy.ast.stmt.Statement> statements =
                    closure.getCode() instanceof org.codehaus.groovy.ast.stmt.BlockStatement block
                            ? block.getStatements() : List.of(closure.getCode());
            return statements.size() > 1 || statements.size() == 1
                    && !(statements.get(0) instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement)
                    && !(statements.get(0) instanceof ReturnStatement);
        }

        private static boolean usesIt(ClosureExpression closure) {
            boolean[] found = {false};
            closure.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitVariableExpression(VariableExpression variable) {
                    found[0] |= "it".equals(variable.getName());
                }

                @Override
                public void visitClosureExpression(ClosureExpression nested) {
                    if (nested.isParameterSpecified()) {
                        super.visitClosureExpression(nested); // its own it would shadow ours otherwise
                    }
                }
            });
            return found[0];
        }
    }
}
