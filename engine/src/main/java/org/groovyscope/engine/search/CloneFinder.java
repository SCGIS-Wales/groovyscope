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
package org.groovyscope.engine.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.LambdaExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Range;

/**
 * AST clone detection. Every block's statements are normalised to token sequences (names become {@code ID},
 * literals {@code LIT}; method names, operators, statement kinds and type names stay) and hashed. Windows of
 * {@code minStatements} equal statement hashes seed candidate pairs, which are extended to their maximal common run;
 * runs spanning at least {@code minLines} lines at two or more non-overlapping places form a group. Groups whose
 * every occurrence lies inside an occurrence of a larger group are dropped.
 */
public final class CloneFinder {

    /** Seed buckets larger than this (highly repetitive code) are sampled to keep the pairing linear. */
    static final int MAX_SEED_OCCURRENCES = 64;

    /** Where one copy of a clone lies; lines are zero-based and inclusive. */
    public record Occurrence(String uri, int line, int endLine, String enclosing) {
    }

    /** Copies of the same normalised run; {@code exact} when their texts agree up to whitespace. */
    public record Group(int lines, int statements, boolean exact, List<Occurrence> occurrences) {
    }

    /** A run of statements with positions inside one block. */
    record Block(String uri, String enclosing, long[] hashes, Range[] ranges, TextLines text) {
    }

    private record Run(int block, int start, int length) {
    }

    private CloneFinder() {
    }

    /** Clone groups in the given documents (all compiled Groovy documents when {@code uris} is empty). */
    public static List<Group> find(Map<String, ModuleNode> modules, Function<String, TextLines> lines,
            Collection<String> uris, int minLines, int minStatements) {
        int window = Math.max(1, minStatements);
        int span = Math.max(1, minLines);
        List<Block> blocks = new ArrayList<>();
        for (String uri : CodeSearch.targets(modules, uris)) {
            BlockCollector collector = new BlockCollector(uri, lines.apply(uri), window, blocks);
            for (ClassNode classNode : modules.get(uri).getClasses()) {
                if (!CodeSearch.isGenerated(classNode)) {
                    collector.visitClass(classNode);
                }
            }
        }
        Map<Long, List<long[]>> seeds = new HashMap<>();
        for (int b = 0; b < blocks.size(); b++) {
            long[] hashes = blocks.get(b).hashes();
            for (int i = 0; i + window <= hashes.length; i++) {
                seeds.computeIfAbsent(runHash(hashes, i, window), k -> new ArrayList<>()).add(new long[] {b, i});
            }
        }
        Map<Long, List<Run>> groups = new LinkedHashMap<>();
        for (List<long[]> bucket : seeds.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            if (bucket.size() > MAX_SEED_OCCURRENCES) {
                EngineLog.debug("duplicates: sampling %d of %d repeated windows", MAX_SEED_OCCURRENCES, bucket.size());
            }
            int size = Math.min(bucket.size(), MAX_SEED_OCCURRENCES);
            for (int p = 0; p < size; p++) {
                for (int q = p + 1; q < size; q++) {
                    pair(blocks, (int) bucket.get(p)[0], (int) bucket.get(p)[1], (int) bucket.get(q)[0],
                            (int) bucket.get(q)[1], window, span, groups);
                }
            }
        }
        return report(blocks, groups);
    }

    /** Extends one seed pair to its maximal run and files both copies under the run's hash. */
    private static void pair(List<Block> blocks, int blockA, int startA, int blockB, int startB, int window, int span,
            Map<Long, List<Run>> groups) {
        long[] a = blocks.get(blockA).hashes();
        long[] b = blocks.get(blockB).hashes();
        if (startA > 0 && startB > 0 && a[startA - 1] == b[startB - 1]) {
            return; // not left-maximal: the pair one statement earlier covers this one
        }
        for (int k = 0; k < window; k++) {
            if (a[startA + k] != b[startB + k]) {
                return; // window hash collision
            }
        }
        int length = window;
        while (startA + length < a.length && startB + length < b.length && a[startA + length] == b[startB + length]) {
            length++;
        }
        if (blockA == blockB) {
            length = Math.min(length, Math.abs(startB - startA));
        }
        if (length < window) {
            return;
        }
        if (lines(blocks.get(blockA), startA, length) < span || lines(blocks.get(blockB), startB, length) < span) {
            return;
        }
        List<Run> runs = groups.computeIfAbsent(runHash(a, startA, length) * 31 + length, k -> new ArrayList<>());
        add(runs, new Run(blockA, startA, length));
        add(runs, new Run(blockB, startB, length));
    }

    private static void add(List<Run> runs, Run run) {
        for (Run existing : runs) {
            if (existing.block() == run.block() && existing.start() < run.start() + run.length()
                    && run.start() < existing.start() + existing.length()) {
                return; // the same copy, or one overlapping a copy already filed
            }
        }
        runs.add(run);
    }

    private static int lines(Block block, int start, int length) {
        return block.ranges()[start + length - 1].end().line() - block.ranges()[start].start().line() + 1;
    }

    private static List<Group> report(List<Block> blocks, Map<Long, List<Run>> groups) {
        List<Group> candidates = new ArrayList<>();
        for (List<Run> runs : groups.values()) {
            if (runs.size() < 2) {
                continue;
            }
            List<Occurrence> occurrences = new ArrayList<>();
            Set<String> texts = new HashSet<>();
            int maxLines = 0;
            for (Run run : runs) {
                Block block = blocks.get(run.block());
                Range first = block.ranges()[run.start()];
                Range last = block.ranges()[run.start() + run.length() - 1];
                occurrences.add(new Occurrence(block.uri(), first.start().line(), last.end().line(), block.enclosing()));
                texts.add(block.text().slice(new Range(first.start(), last.end())).replaceAll("\\s+", " ").trim());
                maxLines = Math.max(maxLines, last.end().line() - first.start().line() + 1);
            }
            occurrences.sort(Comparator.comparing(Occurrence::uri).thenComparingInt(Occurrence::line));
            candidates.add(new Group(maxLines, runs.get(0).length(), texts.size() == 1, List.copyOf(occurrences)));
        }
        candidates.sort(Comparator.comparingInt((Group g) -> g.statements()).thenComparingInt(Group::lines).reversed());
        List<Group> kept = new ArrayList<>();
        for (Group candidate : candidates) {
            if (!containedInKept(candidate, kept)) {
                kept.add(candidate);
            }
        }
        kept.sort(Comparator.comparingLong((Group g) -> -(long) g.lines() * g.occurrences().size())
                .thenComparing(g -> g.occurrences().get(0).uri())
                .thenComparingInt(g -> g.occurrences().get(0).line()));
        return kept;
    }

    /** True when every occurrence of {@code group} lies inside an occurrence of a group already kept. */
    private static boolean containedInKept(Group group, List<Group> kept) {
        for (Occurrence occurrence : group.occurrences()) {
            boolean inside = false;
            for (Group larger : kept) {
                for (Occurrence other : larger.occurrences()) {
                    if (other.uri().equals(occurrence.uri()) && other.line() <= occurrence.line()
                            && occurrence.endLine() <= other.endLine()) {
                        inside = true;
                        break;
                    }
                }
                if (inside) {
                    break;
                }
            }
            if (!inside) {
                return false;
            }
        }
        return true;
    }

    /** Hash of {@code length} statement hashes starting at {@code start}. */
    static long runHash(long[] hashes, int start, int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = start; i < start + length; i++) {
            hash = (hash ^ hashes[i]) * 0x100000001b3L;
            hash ^= hash >>> 29;
        }
        return hash;
    }

    /** The normalised hash of one statement. */
    static long hash(Statement statement) {
        Normaliser normaliser = new Normaliser();
        statement.visit(normaliser);
        return normaliser.hash;
    }

    /** The normalised token sequence of one statement, for tests and debugging. */
    static List<String> tokens(Statement statement) {
        Normaliser normaliser = new Normaliser();
        normaliser.tokens = new ArrayList<>();
        statement.visit(normaliser);
        return normaliser.tokens;
    }

    /** Collects every block's positioned statements, splitting a block where a statement has no position. */
    private static final class BlockCollector extends ClassCodeVisitorSupport {

        private final String uri;
        private final TextLines text;
        private final int window;
        private final List<Block> out;
        private ClassNode currentClass;
        private MethodNode currentMethod;

        BlockCollector(String uri, TextLines text, int window, List<Block> out) {
            this.uri = uri;
            this.text = text;
            this.window = window;
            this.out = out;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitClass(ClassNode node) {
            ClassNode previous = currentClass;
            currentClass = node;
            try {
                super.visitClass(node);
            } finally {
                currentClass = previous;
            }
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            MethodNode previous = currentMethod;
            currentMethod = node;
            try {
                super.visitConstructorOrMethod(node, isConstructor);
            } finally {
                currentMethod = previous;
            }
        }

        private String enclosing() {
            String owner = currentClass == null ? "" : currentClass.getNameWithoutPackage();
            if (currentMethod == null || currentMethod.getLineNumber() < 1 || currentMethod.isScriptBody()) {
                return owner;
            }
            String method = currentMethod instanceof ConstructorNode ? "<init>" : currentMethod.getName();
            return owner.isEmpty() ? method : owner + "." + method;
        }

        @Override
        public void visitBlockStatement(BlockStatement block) {
            List<Statement> statements = block.getStatements();
            if (statements.size() >= window) {
                List<Long> hashes = new ArrayList<>();
                List<Range> ranges = new ArrayList<>();
                for (Statement statement : statements) {
                    Range range = AstRanges.rangeOf(statement);
                    if (range == null) {
                        flush(hashes, ranges);
                        continue;
                    }
                    hashes.add(hash(statement));
                    ranges.add(range);
                }
                flush(hashes, ranges);
            }
            super.visitBlockStatement(block);
        }

        private void flush(List<Long> hashes, List<Range> ranges) {
            if (hashes.size() >= window) {
                long[] array = new long[hashes.size()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = hashes.get(i);
                }
                out.add(new Block(uri, enclosing(), array, ranges.toArray(new Range[0]), text));
            }
            hashes.clear();
            ranges.clear();
        }
    }

    /** Folds a statement's normalised tokens into a 64-bit hash (and optionally records them). */
    private static final class Normaliser extends CodeVisitorSupport {

        long hash = 0xcbf29ce484222325L;
        List<String> tokens;

        private void token(String token) {
            if (tokens != null) {
                tokens.add(token);
            }
            hash = (hash ^ token.hashCode()) * 0x100000001b3L;
            hash = (hash ^ 0x1f) * 0x100000001b3L;
        }

        private void type(ClassNode type) {
            token(type == null || ClassHelper.isDynamicTyped(type) ? "def" : type.getName());
        }

        @Override
        public void visitBlockStatement(BlockStatement block) {
            token("{");
            super.visitBlockStatement(block);
            token("}");
        }

        @Override
        public void visitForLoop(ForStatement statement) {
            token("for");
            Parameter variable = statement.getValueVariable();
            if (variable != null && variable.getLineNumber() > 0) {
                type(variable.getOriginType());
                token("ID");
            }
            super.visitForLoop(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement statement) {
            token("while");
            super.visitWhileLoop(statement);
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement statement) {
            token("do");
            super.visitDoWhileLoop(statement);
        }

        @Override
        public void visitIfElse(IfStatement statement) {
            token("if");
            super.visitIfElse(statement);
        }

        @Override
        public void visitExpressionStatement(ExpressionStatement statement) {
            token("expr");
            super.visitExpressionStatement(statement);
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            token("return");
            super.visitReturnStatement(statement);
        }

        @Override
        public void visitAssertStatement(AssertStatement statement) {
            token("assert");
            super.visitAssertStatement(statement);
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement statement) {
            token("try");
            super.visitTryCatchFinally(statement);
        }

        @Override
        public void visitCatchStatement(CatchStatement statement) {
            token("catch");
            type(statement.getExceptionType());
            super.visitCatchStatement(statement);
        }

        @Override
        public void visitSwitch(SwitchStatement statement) {
            token("switch");
            super.visitSwitch(statement);
        }

        @Override
        public void visitCaseStatement(CaseStatement statement) {
            token("case");
            super.visitCaseStatement(statement);
        }

        @Override
        public void visitBreakStatement(BreakStatement statement) {
            token("break");
        }

        @Override
        public void visitContinueStatement(ContinueStatement statement) {
            token("continue");
        }

        @Override
        public void visitSynchronizedStatement(SynchronizedStatement statement) {
            token("synchronized");
            super.visitSynchronizedStatement(statement);
        }

        @Override
        public void visitThrowStatement(ThrowStatement statement) {
            token("throw");
            super.visitThrowStatement(statement);
        }

        @Override
        public void visitEmptyStatement(EmptyStatement statement) {
            token("empty");
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            token(call.isSpreadSafe() ? "*call" : call.isSafe() ? "?call" : "call");
            String name = call.getMethodAsString();
            token(name == null ? "?" : name);
            if (call.isImplicitThis()) {
                token("this");
            } else {
                call.getObjectExpression().visit(this);
            }
            if (name == null) {
                call.getMethod().visit(this);
            }
            call.getArguments().visit(this);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            token("scall");
            type(call.getOwnerType());
            token(call.getMethod());
            call.getArguments().visit(this);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            token("new");
            type(call.getType());
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            token("bin");
            token(expression.getOperation().getText());
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            token(expression instanceof ElvisOperatorExpression ? "?:" : "?");
            super.visitTernaryExpression(expression);
        }

        @Override
        public void visitShortTernaryExpression(ElvisOperatorExpression expression) {
            token("?:");
            expression.getTrueExpression().visit(this);
            expression.getFalseExpression().visit(this);
        }

        @Override
        public void visitPostfixExpression(PostfixExpression expression) {
            token("post" + expression.getOperation().getText());
            super.visitPostfixExpression(expression);
        }

        @Override
        public void visitPrefixExpression(PrefixExpression expression) {
            token("pre" + expression.getOperation().getText());
            super.visitPrefixExpression(expression);
        }

        @Override
        public void visitBooleanExpression(BooleanExpression expression) {
            token("bool");
            super.visitBooleanExpression(expression);
        }

        @Override
        public void visitNotExpression(NotExpression expression) {
            token("!");
            super.visitNotExpression(expression);
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            token("closure");
            Parameter[] parameters = expression.getParameters();
            token(Integer.toString(parameters == null ? -1 : parameters.length));
            super.visitClosureExpression(expression);
        }

        @Override
        public void visitLambdaExpression(LambdaExpression expression) {
            token("lambda");
            super.visitLambdaExpression(expression);
        }

        @Override
        public void visitListExpression(ListExpression expression) {
            token("[");
            super.visitListExpression(expression);
            token("]");
        }

        @Override
        public void visitArrayExpression(ArrayExpression expression) {
            token("array");
            type(expression.getElementType());
            super.visitArrayExpression(expression);
        }

        @Override
        public void visitMapExpression(MapExpression expression) {
            token("[:");
            super.visitMapExpression(expression);
            token(":]");
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression expression) {
            token(":");
            super.visitMapEntryExpression(expression);
        }

        @Override
        public void visitRangeExpression(RangeExpression expression) {
            token(expression.isInclusive() ? ".." : "..<");
            super.visitRangeExpression(expression);
        }

        @Override
        public void visitSpreadExpression(SpreadExpression expression) {
            token("*");
            super.visitSpreadExpression(expression);
        }

        @Override
        public void visitMethodPointerExpression(MethodPointerExpression expression) {
            token(".&");
            expression.getExpression().visit(this);
            Expression name = expression.getMethodName();
            if (name instanceof ConstantExpression constant) {
                token(String.valueOf(constant.getValue()));
            } else {
                name.visit(this);
            }
        }

        @Override
        public void visitUnaryMinusExpression(UnaryMinusExpression expression) {
            token("-");
            super.visitUnaryMinusExpression(expression);
        }

        @Override
        public void visitUnaryPlusExpression(UnaryPlusExpression expression) {
            token("+");
            super.visitUnaryPlusExpression(expression);
        }

        @Override
        public void visitBitwiseNegationExpression(BitwiseNegationExpression expression) {
            token("~");
            super.visitBitwiseNegationExpression(expression);
        }

        @Override
        public void visitCastExpression(CastExpression expression) {
            token("cast");
            type(expression.getType());
            super.visitCastExpression(expression);
        }

        @Override
        public void visitConstantExpression(ConstantExpression expression) {
            token("LIT");
        }

        @Override
        public void visitClassExpression(ClassExpression expression) {
            token("class");
            type(expression.getType());
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            token(expression.isThisExpression() ? "this" : expression.isSuperExpression() ? "super" : "ID");
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            token("decl");
            if (expression.isMultipleAssignmentDeclaration()) {
                for (Expression element : expression.getTupleExpression().getExpressions()) {
                    type(element.getType());
                    token("ID");
                }
            } else {
                type(expression.getVariableExpression().getOriginType());
                token("ID");
            }
            expression.getRightExpression().visit(this);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            token(expression.isSpreadSafe() ? "*." : expression.isSafe() ? "?." : ".");
            expression.getObjectExpression().visit(this);
            if (expression.getProperty() instanceof ConstantExpression) {
                token("ID");
            } else {
                expression.getProperty().visit(this);
            }
        }

        @Override
        public void visitAttributeExpression(AttributeExpression expression) {
            token(".@");
            visitPropertyExpression(expression);
        }

        @Override
        public void visitFieldExpression(FieldExpression expression) {
            token("ID");
        }

        @Override
        public void visitGStringExpression(GStringExpression expression) {
            token("gstring");
            for (Expression value : expression.getValues()) {
                value.visit(this);
            }
        }

        @Override
        public void visitArgumentlistExpression(ArgumentListExpression expression) {
            token("(");
            super.visitArgumentlistExpression(expression);
            token(")");
        }
    }
}
