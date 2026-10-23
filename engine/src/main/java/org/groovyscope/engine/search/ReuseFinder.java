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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
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
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.Declarations;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Range;

/**
 * Code that already does what a candidate does, so the candidate can call it instead of repeating it. A candidate is
 * a text about to be written, an existing declaration, or lines of a document (the lines a branch added). Three kinds
 * of match are looked for:
 *
 * <ul>
 *   <li>{@code exact} and {@code similar}: a run of statements with the candidate's shape, normalised as
 *       {@link CloneFinder} normalises them ({@link CloneFinder#looseHash}, so a text parsed on its own compares with
 *       the workspace's resolved trees); exact when the texts agree up to whitespace;</li>
 *   <li>{@code signature}: a method with the same name stem (camel case words, a leading verb such as get, find or
 *       build dropped) and the same parameter types, in the candidate's package or its superclasses;</li>
 *   <li>{@code calls}: a method whose called APIs (method names, qualified by the receiver's type when it is known)
 *       overlap the candidate's by at least {@code reuse.callOverlap} (Jaccard), common calls left out.</li>
 * </ul>
 *
 * <p>The workspace side (statement hashes, method stems, parameter types and calls) is computed once per snapshot
 * and kept while the snapshot lives. Lines in matches are 1-based and inclusive.
 */
public final class ReuseFinder {

    /** A copy of the candidate's statements, the same text up to whitespace. */
    public static final String EXACT = "exact";
    /** A run with the candidate's shape whose names or literals differ. */
    public static final String SIMILAR = "similar";
    /** A method with the same name stem and parameter types. */
    public static final String SIGNATURE = "signature";
    /** A method that makes mostly the same calls. */
    public static final String CALLS = "calls";

    /** How much a shape match that differs in names or literals counts against an exact one. */
    static final double SIMILAR_WEIGHT = 0.9;
    /** The score of a method with the candidate's very name and parameter types. */
    static final double SAME_NAME_SCORE = 0.7;
    /** The score of a method with the candidate's name stem and parameter types. */
    static final double SAME_STEM_SCORE = 0.6;
    /** How much the call overlap counts: a perfect overlap ranks under an exact copy. */
    static final double CALLS_WEIGHT = 0.8;
    /** The fewest shared calls that make two methods alike; one shared call says nothing. */
    static final int MIN_SHARED_CALLS = 2;
    /** A call made by more than this share of the workspace's methods is too common to compare methods by. */
    static final double COMMON_CALL_SHARE = 0.02;
    /** ... and by more than this many methods, so a small workspace keeps all its calls. */
    static final int MIN_COMMON_CALLERS = 20;
    /** The shortest name stem compared; shorter ones ({@code id}, {@code go}) say nothing. */
    static final int MIN_STEM = 3;
    /** Occurrences of one statement window looked at, as {@link CloneFinder} samples repetitive code. */
    static final int MAX_SEED_OCCURRENCES = 256;
    /** Shared calls named in a reason. */
    static final int REASON_CALLS = 4;
    /** The class a text is wrapped in when it does not parse as it is. */
    static final String WRAPPER = "SimilarCandidate";

    /** Leading words that name an action rather than what a method is about. */
    static final Set<String> VERBS = Set.of("get", "find", "build", "make", "parse", "to", "as", "create", "compute",
            "calculate", "load", "read", "fetch", "retrieve", "generate", "format", "convert", "extract", "resolve",
            "is", "has", "do", "new", "render", "write");

    /** Workspace facts by snapshot index, kept while that snapshot lives. */
    private static final Map<AstIndex, Workspace> CACHE = new WeakHashMap<>();

    private ReuseFinder() {
    }

    /**
     * One piece of existing code the candidate could reuse.
     *
     * @param symbol the method ({@code pkg.Class.method}, {@code pkg.Class.<init>}) or, for a script body, the class
     * @param uri the document it lies in
     * @param line first line (1-based) of the matching run, or of the method for signature and calls matches
     * @param endLine last line (1-based, inclusive)
     * @param kind {@link #EXACT}, {@link #SIMILAR}, {@link #SIGNATURE} or {@link #CALLS}
     * @param score from 0 to 1; the strongest match first
     * @param reason one line saying why it matched
     */
    public record Match(String symbol, String uri, int line, int endLine, String kind, double score, String reason) {
    }

    /**
     * What to search for and where.
     *
     * @param settings the {@code reuse.*} thresholds
     * @param minLines the fewest lines a candidate needs for shape matches (the request's, or the setting)
     * @param limit the most matches answered
     * @param uris the documents searched; {@code null} for every compiled Groovy document
     */
    public record Query(EngineConfig.Reuse settings, int minLines, int limit, Set<String> uris) {
    }

    /** A called method: its name and, when known, the simple name of the type it is called on. */
    record CallKey(String owner, String name) {

        boolean compatible(CallKey other) {
            return name.equals(other.name) && (owner == null || other.owner == null || owner.equals(other.owner));
        }

        @Override
        public String toString() {
            return owner == null ? name : owner + "." + name;
        }
    }

    /**
     * The statements of one block with their normalised hashes, places and statement counts (nested included).
     *
     * @param bodyLines when the statements are the whole body of a member, the lines the member spans; else 0
     */
    record Block(int unit, long[] hashes, Range[] ranges, int[] counts, int bodyLines) {

        boolean body() {
            return bodyLines > 0;
        }
    }

    /**
     * A method, a constructor or a script body of the workspace.
     *
     * @param method the method, or {@code null} for a script body
     * @param line first line (1-based) of the declaration
     * @param endLine its last line (1-based)
     * @param statements the statements of its body, nested ones included
     * @param stem its name stem, empty for constructors and script bodies
     */
    record Unit(String uri, ClassNode owner, MethodNode method, String symbol, int line, int endLine, int statements,
            String stem, List<String> parameters, List<CallKey> calls) {
    }

    /**
     * The workspace's units, their blocks, the blocks' statement windows by hash, the bodies shorter than a window
     * (whole, by the hash of all their statements, which only a whole body can match), and the names of the calls
     * so many methods make that sharing them says nothing (a framework's {@code addViolation}).
     */
    record Workspace(List<Unit> units, List<Block> blocks, Map<Long, List<int[]>> seeds, Map<Long, List<Integer>> bodies,
            Set<String> common, int window) {
    }

    /**
     * What the candidate is; built by {@link #ofText}, {@link #ofSymbol} or {@link #ofRange}.
     *
     * @param uri the document it lies in, or {@code null} for a text
     * @param text the text its ranges point into
     * @param blocks its statements
     * @param statements its statements, nested ones included
     * @param lines its size for the {@code minLines} rule: its longest declared method or statement run, in lines
     * @param methods the methods it declares (for signature matches)
     * @param calls the calls it makes
     * @param packageName its package, or {@code null} when unknown (the whole workspace is then its scope)
     * @param context the workspace class it sits in, or {@code null}
     * @param superName the superclass its text names, when it has no context
     * @param excludeFrom first line (1-based) of {@code uri} never matched (the candidate itself), 0 for none
     * @param excludeTo last such line
     * @param name the declaration it is ({@code Class.method}), for a candidate named by symbol; else {@code null}
     */
    public record Candidate(String uri, TextLines text, List<Block> blocks, int statements, int lines,
            List<MethodNode> methods, List<CallKey> calls, String packageName, ClassNode context, String superName,
            int excludeFrom, int excludeTo, String name) {

        boolean covers(String otherUri, int line, int endLine) {
            return uri != null && uri.equals(otherUri) && excludeFrom > 0 && line <= excludeTo && excludeFrom <= endLine;
        }
    }

    /**
     * The candidate a text makes: a whole file, members of a class, one method, or bare statements. Members are
     * wrapped in a class, and statements in a method and a class, when they do not parse as they are.
     *
     * @param text the candidate text
     * @param parse parses one text on its own (syntax only): its tree, or empty when it does not parse
     * @param context the workspace class the text is meant for, or {@code null}
     * @return the candidate, or empty when no form of the text parses
     */
    public static Optional<Candidate> ofText(String text, Function<String, Optional<ModuleNode>> parse,
            ClassNode context) {
        String[] forms = {text, "class " + WRAPPER + " {\n" + text + "\n}",
            "class " + WRAPPER + " {\ndef similarCandidate() {\n" + text + "\n}\n}"};
        for (int form = 0; form < forms.length; form++) {
            Optional<ModuleNode> module = parse.apply(forms[form]);
            if (module.isPresent()) {
                return Optional.of(fromText(module.get(), new TextLines(forms[form]), form == 2, context));
            }
        }
        return Optional.empty();
    }

    private static Candidate fromText(ModuleNode module, TextLines text, boolean wrapped, ClassNode context) {
        Collected collected = new Collected(0, Integer.MAX_VALUE);
        String superName = null;
        for (ClassNode classNode : module.getClasses()) {
            ClassNode declared = classNode.getUnresolvedSuperClass(false);
            if (superName == null && !classNode.isScript() && declared != null
                    && !"java.lang.Object".equals(declared.getName())) {
                superName = declared.getName();
            }
            for (MethodNode method : members(classNode)) {
                collected.add(method, !method.isScriptBody() && !wrapped);
            }
        }
        String packageName = trimDot(module.getPackageName());
        if (packageName == null && context != null) {
            packageName = context.getPackageName();
        }
        return new Candidate(null, text, collected.blocks, collected.statements, collected.lines, collected.methods,
                collected.keys(), packageName, context, superName, 0, 0, null);
    }

    /**
     * The candidate an existing declaration makes, found by name as {@code groovyscope/edit} finds it: a method, a
     * constructor or a class (all its methods).
     *
     * @param module the document's tree in the snapshot
     * @param uri the document
     * @param text the document's text
     * @param symbol {@code method}, {@code Class.method}, {@code Class#method}, {@code Class} or {@code Class.<init>},
     *     the package optional
     * @param line a 1-based line inside the declaration, to pick one of several of the same name; or {@code null}
     * @return the candidate; it never matches itself
     * @throws IllegalArgumentException when the symbol is not found, is ambiguous or is a field
     */
    public static Candidate ofSymbol(ModuleNode module, String uri, TextLines text, String symbol, Integer line) {
        String packageName = module == null ? null : trimDot(module.getPackageName());
        Declarations.Located located = SymbolEditor.resolve(Declarations.located(module),
                packageName == null ? "" : packageName + ".", symbol, line);
        if (located.node() instanceof ClassNode type) {
            return ofDeclaration(located, uri, text, type);
        }
        if (located.node() instanceof MethodNode method) {
            return ofDeclaration(located, uri, text, method.getDeclaringClass());
        }
        throw new IllegalArgumentException(located.declaration().name() + " is a field; similar takes a method, a "
                + "constructor or a class");
    }

    private static Candidate ofDeclaration(Declarations.Located located, String uri, TextLines text,
            ClassNode classNode) {
        Collected collected = new Collected(0, Integer.MAX_VALUE);
        if (located.node() instanceof MethodNode method) {
            collected.add(method, true);
        } else if (located.node() instanceof ClassNode type) {
            for (MethodNode method : members(type)) {
                collected.add(method, false);
            }
        }
        Declarations.Declaration declaration = located.declaration();
        return new Candidate(uri, text, collected.blocks, collected.statements,
                declaration.endLine() - declaration.line() + 1, collected.methods, collected.keys(),
                classNode.getPackageName(), classNode, null, declaration.line(), declaration.endLine(),
                declaration.name());
    }

    /**
     * The candidate lines of a document make: the statements wholly inside them, the methods declared inside them
     * and the calls made on them.
     *
     * @param module the document's tree in the snapshot
     * @param uri the document
     * @param text the document's text
     * @param start first line (1-based)
     * @param end last line (1-based, inclusive)
     * @return the candidate; it never matches its own lines
     */
    public static Candidate ofRange(ModuleNode module, String uri, TextLines text, int start, int end) {
        Collected collected = new Collected(start, end);
        ClassNode context = null;
        for (ClassNode classNode : module.getClasses()) {
            for (MethodNode method : members(classNode)) {
                boolean declaredInside = !method.isScriptBody() && method.getLineNumber() >= start
                        && method.getLastLineNumber() <= end;
                if (context == null && !CodeSearch.isGenerated(classNode) && (method.isScriptBody()
                        || method.getLineNumber() <= end && start <= method.getLastLineNumber())) {
                    context = classNode;
                }
                collected.add(method, declaredInside);
            }
        }
        List<Block> inside = new ArrayList<>();
        for (Block block : collected.blocks) {
            int from = -1;
            for (int i = 0; i <= block.hashes().length; i++) {
                boolean in = i < block.hashes().length && block.ranges()[i].start().line() + 1 >= start
                        && block.ranges()[i].end().line() + 1 <= end;
                if (in && from < 0) {
                    from = i;
                } else if (!in && from >= 0) {
                    inside.add(new Block(block.unit(), Arrays.copyOfRange(block.hashes(), from, i),
                            Arrays.copyOfRange(block.ranges(), from, i), Arrays.copyOfRange(block.counts(), from, i),
                            from == 0 && i == block.hashes().length ? block.bodyLines() : 0));
                    collected.lines = Math.max(collected.lines,
                            block.ranges()[i - 1].end().line() - block.ranges()[from].start().line() + 1);
                    from = -1;
                }
            }
        }
        String packageName = context == null ? trimDot(module.getPackageName()) : context.getPackageName();
        return new Candidate(uri, text, inside, collected.statements, collected.lines, collected.methods,
                collected.keys(), packageName, context, null, start, end, null);
    }

    /**
     * Existing code the candidate could reuse, the strongest match first: one match per method (its strongest
     * kind, the others named in its reason), at most {@code query.limit()} of them.
     *
     * @param candidate what to look for
     * @param modules the snapshot's trees by document
     * @param index the snapshot's index: the documents' texts, and the key the workspace facts are kept under
     * @param query the thresholds and the documents searched
     * @return the matches
     */
    public static List<Match> find(Candidate candidate, Map<String, ModuleNode> modules, AstIndex index,
            Query query) {
        Workspace workspace = workspace(modules, index, query.settings().minStatements());
        Map<Integer, List<Match>> found = new LinkedHashMap<>();
        if (candidate.lines() >= query.minLines()) {
            shapes(candidate, workspace, index, query, found);
        }
        signatures(candidate, workspace, index, query, found);
        calls(candidate, workspace, query, found);
        List<Match> matches = new ArrayList<>();
        for (List<Match> unitMatches : found.values()) {
            unitMatches.sort(Comparator.comparingDouble(Match::score).reversed());
            Match strongest = unitMatches.get(0);
            Set<String> also = new LinkedHashSet<>();
            for (Match other : unitMatches.subList(1, unitMatches.size())) {
                if (!other.kind().equals(strongest.kind())) {
                    also.add(other.kind() + " " + String.format(Locale.ROOT, "%.2f", other.score()));
                }
            }
            matches.add(also.isEmpty() ? strongest : new Match(strongest.symbol(), strongest.uri(), strongest.line(),
                    strongest.endLine(), strongest.kind(), strongest.score(),
                    strongest.reason() + "; also " + String.join(", ", also)));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::symbol)
                .thenComparingInt(Match::line));
        return matches.size() > query.limit() ? List.copyOf(matches.subList(0, query.limit())) : matches;
    }

    private static boolean searched(Query query, String uri) {
        return query.uris() == null || query.uris().contains(uri);
    }

    /** Runs of the candidate's statements that the workspace already holds, the longest per method. */
    private static void shapes(Candidate candidate, Workspace workspace, AstIndex index, Query query,
            Map<Integer, List<Match>> found) {
        int window = workspace.window();
        Map<Integer, int[]> runs = new LinkedHashMap<>(); // unit -> {candidate block, start, target block, start, length}
        for (int c = 0; c < candidate.blocks().size(); c++) {
            Cancellation.checkpoint();
            Block block = candidate.blocks().get(c);
            long[] hashes = block.hashes();
            for (int i = 0; i + window <= hashes.length; i++) {
                List<int[]> bucket = workspace.seeds().get(CloneFinder.runHash(hashes, i, window));
                if (bucket == null) {
                    continue;
                }
                for (int k = 0; k < Math.min(bucket.size(), MAX_SEED_OCCURRENCES); k++) {
                    extend(candidate, workspace, query, new int[] {c, i, bucket.get(k)[0], bucket.get(k)[1]}, window,
                            runs);
                }
            }
            if (block.body() && hashes.length < window && block.bodyLines() >= query.minLines()) {
                // A member of minLines whose body is too short for a window matches whole bodies of its shape only.
                List<Integer> bodies = workspace.bodies().getOrDefault(
                        CloneFinder.runHash(hashes, 0, hashes.length), List.of());
                for (int k = 0; k < Math.min(bodies.size(), MAX_SEED_OCCURRENCES); k++) {
                    if (Arrays.equals(hashes, workspace.blocks().get(bodies.get(k)).hashes())) {
                        extend(candidate, workspace, query, new int[] {c, 0, bodies.get(k), 0}, hashes.length, runs);
                    }
                }
            }
        }
        for (Map.Entry<Integer, int[]> entry : runs.entrySet()) {
            int[] run = entry.getValue();
            Unit unit = workspace.units().get(entry.getKey());
            Block mine = candidate.blocks().get(run[0]);
            Block theirs = workspace.blocks().get(run[2]);
            int length = run[4];
            int matched = sum(mine.counts(), run[1], length);
            double share = Math.min(1.0, (double) matched / Math.max(1, candidate.statements()));
            double shareThere = Math.min(1.0, (double) sum(theirs.counts(), run[3], length)
                    / Math.max(1, unit.statements()));
            Range first = theirs.ranges()[run[3]];
            Range last = theirs.ranges()[run[3] + length - 1];
            String there = index.lines(unit.uri()).slice(new Range(first.start(), last.end()));
            String here = candidate.text().slice(new Range(mine.ranges()[run[1]].start(),
                    mine.ranges()[run[1] + length - 1].end()));
            boolean exact = collapse(there).equals(collapse(here));
            String reason = (exact ? "the same code: " : "the same shape, other names or literals: ") + matched
                    + " of the candidate's " + candidate.statements() + " statements"
                    + (shareThere >= 1.0 ? ", the whole of " + simpleName(unit.symbol()) : "");
            found.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new Match(unit.symbol(), unit.uri(),
                    first.start().line() + 1, last.end().line() + 1, exact ? EXACT : SIMILAR,
                    round(Math.max(share, shareThere) * (exact ? 1.0 : SIMILAR_WEIGHT)), reason));
        }
    }

    /**
     * Extends one seed ({@code candidate block, start, workspace block, start}) to its maximal run, and keeps it as
     * the method's run when it passes the thresholds and is the longest so far.
     */
    private static void extend(Candidate candidate, Workspace workspace, Query query, int[] seed, int window,
            Map<Integer, int[]> runs) {
        int c = seed[0];
        int i = seed[1];
        int j = seed[3];
        Block mine = candidate.blocks().get(c);
        Block target = workspace.blocks().get(seed[2]);
        long[] a = mine.hashes();
        long[] b = target.hashes();
        if (i > 0 && j > 0 && a[i - 1] == b[j - 1]) {
            return; // not left-maximal: the run one statement earlier covers this one
        }
        for (int k = 0; k < window; k++) {
            if (a[i + k] != b[j + k]) {
                return; // window hash collision
            }
        }
        int length = window;
        while (i + length < a.length && j + length < b.length && a[i + length] == b[j + length]) {
            length++;
        }
        Unit unit = workspace.units().get(target.unit());
        Range first = target.ranges()[j];
        Range last = target.ranges()[j + length - 1];
        if (!searched(query, unit.uri())
                || candidate.covers(unit.uri(), first.start().line() + 1, last.end().line() + 1)) {
            return;
        }
        int matched = sum(mine.counts(), i, length);
        int spanHere = mine.ranges()[i + length - 1].end().line() - mine.ranges()[i].start().line() + 1;
        boolean wholeHere = matched >= candidate.statements();
        boolean wholeThere = sum(target.counts(), j, length) >= unit.statements();
        if (spanHere < query.minLines() && !wholeHere && !wholeThere) {
            return; // a short run inside both: a common shape, not a copy
        }
        int[] current = runs.get(target.unit());
        if (current == null || matched > sum(candidate.blocks().get(current[0]).counts(), current[1], current[4])) {
            runs.put(target.unit(), new int[] {c, i, seed[2], j, length});
        }
    }

    /** Methods with the candidate's name stem and parameter types, in its package or superclasses. */
    private static void signatures(Candidate candidate, Workspace workspace, AstIndex index, Query query,
            Map<Integer, List<Match>> found) {
        Set<ClassNode> supers = supertypes(candidate, index);
        for (MethodNode method : candidate.methods()) {
            String stem = method instanceof ConstructorNode ? "" : stem(method.getName());
            if (stem.length() < MIN_STEM) {
                continue;
            }
            List<String> parameters = parameterTypes(method.getParameters());
            for (int u = 0; u < workspace.units().size(); u++) {
                Unit unit = workspace.units().get(u);
                if (unit.method() == null || unit.method() == method || !stem.equals(unit.stem())
                        || !parameters.equals(unit.parameters()) || unit.method().isAbstract()
                        || !searched(query, unit.uri()) || candidate.covers(unit.uri(), unit.line(), unit.endLine())) {
                    continue;
                }
                boolean inScope = candidate.packageName() == null || supers.contains(unit.owner())
                        || candidate.packageName().equals(unit.owner().getPackageName());
                boolean sameName = method.getName().equals(unit.method().getName());
                if (!inScope || sameName && (supers.contains(unit.owner()) || overrides(unit.method())
                        || overrides(method))) {
                    continue; // out of scope, or an override: polymorphism rather than a copy
                }
                String reason = (sameName ? "the same name and parameters (" : "the same name stem '" + stem
                        + "' and parameters (") + String.join(", ", parameters) + ")";
                found.computeIfAbsent(u, k -> new ArrayList<>()).add(new Match(unit.symbol(), unit.uri(), unit.line(),
                        unit.endLine(), SIGNATURE, sameName ? SAME_NAME_SCORE : SAME_STEM_SCORE, reason));
            }
        }
    }

    /** Methods whose calls overlap the candidate's by {@code reuse.callOverlap} or more. */
    private static void calls(Candidate candidate, Workspace workspace, Query query, Map<Integer, List<Match>> found) {
        Set<String> ignored = new HashSet<>(query.settings().ignoreCalls());
        ignored.addAll(workspace.common());
        List<CallKey> mine = significant(candidate.calls(), ignored);
        if (mine.size() < MIN_SHARED_CALLS) {
            return;
        }
        for (int u = 0; u < workspace.units().size(); u++) {
            if ((u & 255) == 0) {
                Cancellation.checkpoint();
            }
            Unit unit = workspace.units().get(u);
            if (unit.method() == null || !searched(query, unit.uri())
                    || candidate.covers(unit.uri(), unit.line(), unit.endLine())
                    || candidate.methods().contains(unit.method())) {
                continue;
            }
            List<CallKey> theirs = significant(unit.calls(), ignored);
            List<String> shared = new ArrayList<>();
            for (CallKey key : mine) {
                for (CallKey other : theirs) {
                    if (key.compatible(other)) {
                        shared.add(key.owner() != null ? key.toString() : other.toString());
                        break;
                    }
                }
            }
            int union = mine.size() + theirs.size() - shared.size();
            double overlap = (double) shared.size() / union;
            if (shared.size() < MIN_SHARED_CALLS || overlap < query.settings().callOverlap()) {
                continue;
            }
            String reason = "makes " + shared.size() + " of the same " + union + " calls: "
                    + String.join(", ", shared.subList(0, Math.min(REASON_CALLS, shared.size())))
                    + (shared.size() > REASON_CALLS ? ", ..." : "");
            found.computeIfAbsent(u, k -> new ArrayList<>()).add(new Match(unit.symbol(), unit.uri(), unit.line(),
                    unit.endLine(), CALLS, round(overlap * CALLS_WEIGHT), reason));
        }
    }

    private static List<CallKey> significant(List<CallKey> calls, Set<String> ignored) {
        List<CallKey> kept = new ArrayList<>();
        for (CallKey key : calls) {
            if (!ignored.contains(key.name())) {
                kept.add(key);
            }
        }
        return kept;
    }

    /** The candidate's workspace superclasses and traits: from its class, or from the superclass its text names. */
    private static Set<ClassNode> supertypes(Candidate candidate, AstIndex index) {
        Set<ClassNode> supers = Collections.newSetFromMap(new IdentityHashMap<>());
        if (candidate.context() != null) {
            supers.addAll(ReuseHelpers.workspaceSupertypes(candidate.context()));
        } else if (candidate.superName() != null) {
            String name = candidate.superName();
            List<ClassNode> named = new ArrayList<>(index.classNamed(name).map(List::of).orElse(List.of()));
            if (named.isEmpty()) {
                named.addAll(index.classesWithSimpleName(name.substring(name.lastIndexOf('.') + 1)));
            }
            for (ClassNode type : named) {
                supers.add(type);
                supers.addAll(ReuseHelpers.workspaceSupertypes(type));
            }
        }
        return supers;
    }

    /** The workspace's units and blocks for a snapshot, built once and kept while the snapshot lives. */
    static Workspace workspace(Map<String, ModuleNode> modules, AstIndex index, int window) {
        synchronized (CACHE) {
            Workspace cached = CACHE.get(index);
            if (cached != null && cached.window() == window) {
                return cached;
            }
        }
        Workspace built = build(modules, window);
        synchronized (CACHE) {
            CACHE.put(index, built);
        }
        return built;
    }

    private static Workspace build(Map<String, ModuleNode> modules, int window) {
        List<Unit> units = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        int files = 0;
        for (String uri : CodeSearch.targets(modules, List.of())) {
            if ((++files & 31) == 0) {
                Cancellation.checkpoint();
            }
            for (ClassNode classNode : modules.get(uri).getClasses()) {
                if (CodeSearch.isGenerated(classNode)) {
                    continue;
                }
                for (MethodNode method : members(classNode)) {
                    if (method.getCode() == null) {
                        continue;
                    }
                    int index = units.size();
                    CallCollector calls = new CallCollector(0, Integer.MAX_VALUE);
                    method.getCode().visit(calls);
                    boolean script = method.isScriptBody() || method.getLineNumber() < 1;
                    Range body = AstRanges.rangeOf(method.getCode());
                    int line = script ? (body == null ? 1 : body.start().line() + 1) : method.getLineNumber();
                    int endLine = script ? (body == null ? line : body.end().line() + 1) : method.getLastLineNumber();
                    new BlockCollector(blocks, window, index).collect(method.getCode(), Math.max(1, endLine - line + 1));
                    boolean constructor = method instanceof ConstructorNode;
                    units.add(new Unit(uri, classNode, script ? null : method, script ? className(classNode)
                            : className(classNode) + "." + (constructor ? "<init>" : method.getName()), line, endLine,
                            StatementCounter.count(method.getCode(), 0, Integer.MAX_VALUE),
                            script || constructor ? "" : stem(method.getName()),
                            parameterTypes(method.getParameters()), calls.keys()));
                }
            }
        }
        Map<Long, List<int[]>> seeds = new HashMap<>();
        Map<Long, List<Integer>> bodies = new HashMap<>();
        Map<String, Integer> callers = new HashMap<>();
        for (Unit unit : units) {
            Set<String> names = new HashSet<>();
            for (CallKey key : unit.calls()) {
                if (names.add(key.name())) {
                    callers.merge(key.name(), 1, Integer::sum);
                }
            }
        }
        int ubiquitous = Math.max(MIN_COMMON_CALLERS, (int) (units.size() * COMMON_CALL_SHARE));
        Set<String> common = new HashSet<>();
        callers.forEach((name, count) -> {
            if (count > ubiquitous) {
                common.add(name);
            }
        });
        for (int b = 0; b < blocks.size(); b++) {
            long[] hashes = blocks.get(b).hashes();
            for (int i = 0; i + window <= hashes.length; i++) {
                seeds.computeIfAbsent(CloneFinder.runHash(hashes, i, window), k -> new ArrayList<>())
                        .add(new int[] {b, i});
            }
            if (blocks.get(b).body() && hashes.length < window) {
                bodies.computeIfAbsent(CloneFinder.runHash(hashes, 0, hashes.length), k -> new ArrayList<>()).add(b);
            }
        }
        return new Workspace(units, blocks, seeds, bodies, Set.copyOf(common), window);
    }

    /** The methods and constructors of a class that have a body worth comparing, script bodies included. */
    static List<MethodNode> members(ClassNode classNode) {
        List<MethodNode> members = new ArrayList<>();
        for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
            if (!constructor.isSynthetic() && constructor.getLineNumber() > 0 && constructor.getCode() != null) {
                members.add(constructor);
            }
        }
        for (MethodNode method : classNode.getMethods()) {
            // A script's generated main is marked as script body too; only run holds the script's statements.
            boolean statements = method.isScriptBody() ? "run".equals(method.getName())
                    && method.getParameters().length == 0 : method.getLineNumber() > 0;
            if (!method.isSynthetic() && statements && method.getCode() != null) {
                members.add(method);
            }
        }
        return members;
    }

    /** The name a method is about: its camel case words without a leading verb, lower case and joined. */
    static String stem(String name) {
        List<String> kept = new ArrayList<>();
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|_+")) {
            if (!word.isEmpty()) {
                kept.add(word.toLowerCase(Locale.ROOT));
            }
        }
        if (kept.size() > 1 && VERBS.contains(kept.get(0))) {
            kept.remove(0);
        }
        return String.join("", kept);
    }

    /** Parameter types by simple name, generics left out, {@code def} for an untyped one. */
    static List<String> parameterTypes(Parameter[] parameters) {
        List<String> types = new ArrayList<>();
        for (Parameter parameter : parameters) {
            types.add(parameter.isDynamicTyped() ? "def" : simple(parameter.getOriginType()));
        }
        return types;
    }

    /** A type's simple name whether it was resolved or only parsed; arrays end in {@code []}. */
    static String simple(ClassNode type) {
        if (type == null || ClassHelper.isDynamicTyped(type)) {
            return "def";
        }
        if (type.isArray()) {
            return simple(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    /** {@code pkg.Outer.Inner}: the class's name with nesting written with dots. */
    static String className(ClassNode classNode) {
        return classNode.getName().replace('$', '.');
    }

    private static String simpleName(String symbol) {
        return symbol.substring(symbol.lastIndexOf('.') + 1);
    }

    /** True when a method overrides or implements one of a supertype, by annotation or by name and arity. */
    static boolean overrides(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            String name = annotation.getClassNode().getName();
            if ("Override".equals(name) || "java.lang.Override".equals(name)) {
                return true;
            }
        }
        ClassNode owner = method.getDeclaringClass();
        Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ClassNode> queue = new ArrayList<>();
        if (owner != null) {
            queue.add(owner);
        }
        while (!queue.isEmpty() && visited.size() < ReuseHelpers.MAX_SUPERTYPES) {
            ClassNode current = queue.remove(0);
            for (ClassNode type : ReuseHelpers.direct(current)) {
                if (!visited.add(type) || !type.isPrimaryClassNode() && !type.isResolved()) {
                    continue;
                }
                if (declares(type, method)) {
                    return true;
                }
                queue.add(type);
            }
        }
        return false;
    }

    private static boolean declares(ClassNode type, MethodNode method) {
        try {
            for (MethodNode other : type.getDeclaredMethods(method.getName())) {
                if (other.getParameters().length == method.getParameters().length) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return false; // a classpath type that cannot be read: no override known
        }
        return false;
    }

    private static int sum(int[] counts, int from, int length) {
        int total = 0;
        for (int i = from; i < from + length; i++) {
            total += counts[i];
        }
        return total;
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static double round(double score) {
        return Math.round(score * 100) / 100.0;
    }

    private static String trimDot(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        return packageName.endsWith(".") ? packageName.substring(0, packageName.length() - 1) : packageName;
    }

    /** Blocks, statements, declared methods and calls of the members a candidate is made of. */
    private static final class Collected {
        private final List<Block> blocks = new ArrayList<>();
        private final List<MethodNode> methods = new ArrayList<>();
        private final CallCollector calls;
        private final BlockCollector collector = new BlockCollector(blocks, 1, -1);
        private final int from;
        private final int to;
        private int statements;
        private int lines;

        Collected(int from, int to) {
            this.from = from;
            this.to = to;
            this.calls = new CallCollector(from, to);
        }

        /** Adds one member's body; {@code declared} when the member itself is part of the candidate. */
        void add(MethodNode method, boolean declared) {
            if (declared) {
                methods.add(method);
            }
            if (method.getCode() == null) {
                return; // abstract: a signature without statements
            }
            int memberLines = declared ? method.getLastLineNumber() - method.getLineNumber() + 1
                    : span(method.getCode());
            if (declared || from == 0) {
                lines = Math.max(lines, memberLines);
            }
            collector.collect(method.getCode(), Math.max(1, memberLines));
            method.getCode().visit(calls);
            statements += StatementCounter.count(method.getCode(), from, to);
        }

        List<CallKey> keys() {
            return calls.keys();
        }

        /** The lines from a body's first statement to its last. */
        private static int span(Statement code) {
            if (code instanceof BlockStatement block && !block.getStatements().isEmpty()) {
                Range first = AstRanges.rangeOf(block.getStatements().get(0));
                Range last = AstRanges.rangeOf(block.getStatements().get(block.getStatements().size() - 1));
                if (first != null && last != null) {
                    return last.end().line() - first.start().line() + 1;
                }
            }
            return 0;
        }
    }

    /** Every block's positioned statements with their loose hashes, split where a statement has no position. */
    private static final class BlockCollector extends CodeVisitorSupport {
        private final List<Block> out;
        private final int window;
        private final int unit;

        private Statement body;
        private int bodyLines;

        BlockCollector(List<Block> out, int window, int unit) {
            this.out = out;
            this.window = window;
            this.unit = unit;
        }

        /** Collects the blocks of a member's body, which spans {@code lines} lines with its declaration. */
        void collect(Statement code, int lines) {
            body = code;
            bodyLines = lines;
            code.visit(this);
        }

        @Override
        public void visitBlockStatement(BlockStatement block) {
            boolean whole = block == body;
            List<Statement> run = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();
            for (Statement statement : block.getStatements()) {
                Range range = AstRanges.rangeOf(statement);
                if (range == null) {
                    whole = false;
                    flush(run, ranges, false);
                    continue;
                }
                run.add(statement);
                ranges.add(range);
            }
            flush(run, ranges, whole);
            super.visitBlockStatement(block);
        }

        private void flush(List<Statement> run, List<Range> ranges, boolean whole) {
            if (run.size() >= window || whole && !run.isEmpty()) {
                long[] hashes = new long[run.size()];
                int[] counts = new int[run.size()];
                for (int i = 0; i < hashes.length; i++) {
                    hashes[i] = CloneFinder.looseHash(run.get(i));
                    counts[i] = StatementCounter.count(run.get(i), 0, Integer.MAX_VALUE);
                }
                out.add(new Block(unit, hashes, ranges.toArray(new Range[0]), counts, whole ? bodyLines : 0));
            }
            run.clear();
            ranges.clear();
        }
    }

    /** The methods a body calls, with the receiver's type when it is known, on lines {@code from} to {@code to}. */
    static final class CallCollector extends CodeVisitorSupport {
        private final Set<CallKey> keys = new LinkedHashSet<>();
        private final int from;
        private final int to;

        CallCollector(int from, int to) {
            this.from = from;
            this.to = to;
        }

        List<CallKey> keys() {
            return new ArrayList<>(keys);
        }

        private void add(Expression expression, String owner, String name) {
            int line = expression.getLineNumber();
            if (name != null && (from <= 0 || line >= from && line <= to)) {
                keys.add(new CallKey(owner, name));
            }
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            // A call on super is how an override joins its parent, not what the method does.
            if (!(call.getObjectExpression() instanceof VariableExpression receiver && receiver.isSuperExpression())) {
                add(call, call.isImplicitThis() ? null : owner(call.getObjectExpression()), call.getMethodAsString());
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            add(call, simple(call.getOwnerType()), call.getMethod());
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            add(call, null, "new " + simple(call.getType()));
            super.visitConstructorCallExpression(call);
        }

        /** The receiver's type: a class, a typed variable or parameter, or a capitalised name (a class, parsed). */
        static String owner(Expression receiver) {
            if (receiver instanceof ClassExpression type) {
                return simple(type.getType());
            }
            if (receiver instanceof VariableExpression variable && !variable.isThisExpression()
                    && !variable.isSuperExpression()) {
                Variable accessed = variable.getAccessedVariable();
                if (accessed != null && !(accessed instanceof DynamicVariable) && !accessed.isDynamicTyped()) {
                    return simple(accessed.getOriginType());
                }
                String name = variable.getName();
                if ((accessed == null || accessed instanceof DynamicVariable) && !name.isEmpty()
                        && Character.isUpperCase(name.charAt(0))) {
                    return name;
                }
            }
            return null;
        }
    }

    /** Counts statements, nested ones included and blocks left out, that lie wholly on lines {@code from} to {@code to}. */
    static final class StatementCounter extends CodeVisitorSupport {
        private final int from;
        private final int to;
        private int total;

        private StatementCounter(int from, int to) {
            this.from = from;
            this.to = to;
        }

        static int count(Statement statement, int from, int to) {
            StatementCounter counter = new StatementCounter(from, to);
            statement.visit(counter);
            return counter.total;
        }

        private void count(Statement statement) {
            if (statement.getLineNumber() > 0 && (from <= 0 || statement.getLineNumber() >= from
                    && statement.getLastLineNumber() <= to)) {
                total++;
            }
        }

        @Override
        public void visitExpressionStatement(ExpressionStatement statement) {
            count(statement);
            super.visitExpressionStatement(statement);
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            count(statement);
            super.visitReturnStatement(statement);
        }

        @Override
        public void visitIfElse(IfStatement statement) {
            count(statement);
            super.visitIfElse(statement);
        }

        @Override
        public void visitForLoop(ForStatement statement) {
            count(statement);
            super.visitForLoop(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement statement) {
            count(statement);
            super.visitWhileLoop(statement);
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement statement) {
            count(statement);
            super.visitDoWhileLoop(statement);
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement statement) {
            count(statement);
            super.visitTryCatchFinally(statement);
        }

        @Override
        public void visitSwitch(SwitchStatement statement) {
            count(statement);
            super.visitSwitch(statement);
        }

        @Override
        public void visitThrowStatement(ThrowStatement statement) {
            count(statement);
            super.visitThrowStatement(statement);
        }

        @Override
        public void visitAssertStatement(AssertStatement statement) {
            count(statement);
            super.visitAssertStatement(statement);
        }

        @Override
        public void visitBreakStatement(BreakStatement statement) {
            count(statement);
            super.visitBreakStatement(statement);
        }

        @Override
        public void visitContinueStatement(ContinueStatement statement) {
            count(statement);
            super.visitContinueStatement(statement);
        }

        @Override
        public void visitSynchronizedStatement(SynchronizedStatement statement) {
            count(statement);
            super.visitSynchronizedStatement(statement);
        }
    }
}
