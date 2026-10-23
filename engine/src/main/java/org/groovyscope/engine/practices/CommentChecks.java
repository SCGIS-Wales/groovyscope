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
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.SourceComments.Comment;
import org.groovyscope.engine.practices.SourceComments.Kind;

/**
 * GS060 to GS065, comments that say why and nothing else: a comment that restates the statement below it, a long
 * comment block in a method, Javadoc on a private member or longer than a sentence or two, commented out code,
 * banners, empty comments and TODOs nobody owns, and methods with more comment than code. Comments come from
 * {@link SourceComments}; methods, closures and statements from the tree. Thresholds are {@code comments.*}.
 */
final class CommentChecks {

    /** Words a comment needs for nothing: articles, auxiliaries, pronouns and connectives. */
    static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "this", "that", "these", "those", "is", "are",
            "was", "were", "be", "been", "being", "to", "of", "in", "on", "at", "for", "by", "with", "from", "into",
            "and", "it", "its", "we", "our", "us", "you", "your", "i", "then", "so", "here", "now", "just", "also",
            "do", "does", "did", "has", "have", "had", "will", "would", "should", "can", "could", "may", "might",
            "must", "there", "which", "what", "as", "out", "up", "some", "any");

    /** The words a keyword, an operator or a small number says, as a comment that restates it would put them. */
    static final Map<String, List<String>> SAID = Map.ofEntries(
            Map.entry("if", List.of("if", "check", "whether", "when", "unless", "test")),
            Map.entry("else", List.of("else", "otherwise")),
            Map.entry("return", List.of("return", "give", "answer", "result")),
            Map.entry("new", List.of("new", "create", "make", "instantiate", "build")),
            Map.entry("throw", List.of("throw", "raise", "fail", "error")),
            Map.entry("for", List.of("loop", "iterate", "each", "every", "over", "through")),
            Map.entry("each", List.of("loop", "iterate", "each", "every", "over", "through")),
            Map.entry("while", List.of("loop", "while", "until", "repeat")),
            Map.entry("null", List.of("null", "nothing", "missing")),
            Map.entry("true", List.of("true", "yes")),
            Map.entry("false", List.of("false", "no")),
            Map.entry("in", List.of("contain", "in", "member")),
            Map.entry("==", List.of("equal", "same", "compare", "match")),
            Map.entry("!=", List.of("not", "equal", "differ", "different")),
            Map.entry("!", List.of("not")),
            Map.entry("++", List.of("increment", "increase", "add", "one", "bump")),
            Map.entry("--", List.of("decrement", "decrease", "subtract", "one")),
            Map.entry("+", List.of("add", "plus", "sum", "append", "concatenate", "increase", "total")),
            Map.entry("+=", List.of("add", "plus", "sum", "append", "increase", "total")),
            Map.entry("-", List.of("subtract", "minus", "decrease", "difference")),
            Map.entry("-=", List.of("subtract", "minus", "decrease")),
            Map.entry("*", List.of("multiply", "times", "product")),
            Map.entry("/", List.of("divide", "quotient")),
            Map.entry("=", List.of("set", "assign", "store", "save", "put", "keep", "initialise", "initialize")),
            Map.entry("<<", List.of("add", "append", "push")),
            Map.entry("?:", List.of("default", "fallback")),
            Map.entry("<", List.of("less", "smaller", "below", "compare")),
            Map.entry(">", List.of("greater", "more", "larger", "bigger", "above", "compare")),
            Map.entry("<=", List.of("less", "most", "compare")),
            Map.entry(">=", List.of("greater", "least", "compare")),
            Map.entry("&&", List.of("both")),
            Map.entry("||", List.of("either")),
            Map.entry("0", List.of("zero")),
            Map.entry("1", List.of("one")),
            Map.entry("2", List.of("two")));

    /** Tag description words that repeat a signature: articles, filler and generic nouns. */
    static final Set<String> GENERIC_TAG_WORDS = Set.of("a", "an", "the", "given", "specified", "provided",
            "passed", "input", "parameter", "param", "argument", "value", "object", "instance", "result", "results",
            "return", "returned", "returns", "this", "method", "call", "of", "to", "use", "used", "new");

    static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*|\\d+|\\+\\+|--|\\+=|-=|==|!=|<=|>=|<<|\\?:|&&"
            + "|\\|\\||[-+*/=<>!]");
    static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    static final Pattern PROSE_TOKEN = Pattern.compile("[A-Za-z][a-z']*|e\\.g|i\\.e");
    /** Words that give a reason or a caveat: a comment holding one says why, not what. */
    static final Pattern WHY = Pattern.compile("(?i)\\b(because|since|otherwise|so that|in order to|in case|cannot|"
            + "can't|can not|must|needs?|needed|ensures?|avoids?|prevents?|workaround|hack|note|nota bene|beware|"
            + "careful|might|may|could|would|for example|the case|false (negative|positive)s?|unless|assumes?|"
            + "assumption|why|reason|requires?|required)\\b|\\be\\.g\\.|\\bi\\.e\\.");
    /** An operator that prose does not use on its own (a dash and a colon do appear in sentences). */
    static final Pattern OPERATOR = Pattern.compile("[=!+*/%<>&|^~?]*=[=~]?|->|[{}]|&&|\\|\\||<<|>>|\\?:|<=>");
    /** A list item: a bullet or a number in front of the text. */
    static final Pattern BULLET = Pattern.compile("^([-*+]|\\d+[.)])\\s.*");
    static final Pattern TODO = Pattern.compile("\\b(TODO|FIXME)\\b");
    static final Pattern OWNED = Pattern.compile("^\\s*:?\\s*(\\(\\s*[^)\\s][^)]*\\)|@\\w+)"
            + "|\\b[A-Z][A-Z0-9]+-\\d+\\b|#\\d+\\b");
    static final Pattern TAG = Pattern.compile("^@(param|return|returns)\\b\\s*(.*)$");
    static final Pattern BANNER = Pattern.compile("(?!\\.+$)[-=*#_~+/\\\\<>|.:]{3,}");
    /** Comments that talk to a tool, or link or interpolate something: not restatements whatever their words. */
    static final Pattern SUPPRESSION = Pattern.compile("(?i)\\b(codenarc|nopmd|nosonar|noinspection|suppress\\w*|"
            + "lint)\\b|groovyscope:|https?://|\\$\\{");
    static final Set<String> ACCESSOR_PREFIXES = Set.of("get", "is", "has", "find", "compute", "calculate", "build",
            "create", "make", "read", "load");

    private final PracticeContext context;
    private final EngineConfig.Comments settings;
    private final SourceComments source;
    private final Layout layout;
    private final TextLines lines;
    /** Comment lines already reported as code or decoration, left out of the block count of GS061. */
    private final BitSet accounted = new BitSet();

    private CommentChecks(PracticeContext context, SourceComments source, Layout layout) {
        this.context = context;
        this.settings = context.config().practices().comments();
        this.source = source;
        this.layout = layout;
        this.lines = context.lines();
    }

    static void find(PracticeContext context) {
        if (context.module() == null) {
            return;
        }
        SourceComments source = SourceComments.of(context.lines().text());
        if (source.comments().isEmpty()) {
            return;
        }
        Layout layout = new Layout(context.module().getContext());
        for (ClassNode classNode : context.module().getClasses()) {
            if (!classNode.getName().contains("$_")) {
                layout.visitClassNode(classNode);
            }
        }
        CommentChecks checks = new CommentChecks(context, source, layout);
        checks.decoration();
        checks.commentedOutCode();
        checks.restating();
        checks.blocks();
        checks.javadoc();
        checks.density();
    }

    private void restating() {
        for (int[] run : runs(true)) {
            int below = run[1] + 1;
            Statement statement = layout.statements.get(below);
            if (statement == null || !layout.inBody(run[0]) || accounted.get(run[0], run[1] + 1).cardinality() > 0) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int at = run[0]; at <= run[1]; at++) {
                text.append(source.contentAt(at)).append(' ');
            }
            if (SUPPRESSION.matcher(text).find() || WHY.matcher(text).find() || listed(run[0], run[1])) {
                continue; // a reason, a caveat or a list item says more than the code
            }
            List<String> said = commentWords(text.toString());
            String code = context.source(statement);
            if (said.isEmpty() || code == null) {
                continue;
            }
            Set<String> covered = statementWords(header(statement, code));
            long hits = said.stream().filter(covered::contains).count();
            if (hits >= settings.restateShare() * said.size()) {
                int count = run[1] - run[0] + 1;
                context.report("GS060", span(run[0], run[1]), (count == 1 ? "the comment says" : "the " + count
                        + " comment lines say") + " what the next line already says ("
                        + Math.round(100.0 * hits / said.size()) + "% of its words are in the code); say why, or "
                        + "nothing", "Remove the comment", removeLines(run[0], run[1]));
            }
        }
    }

    /** Whether a run is part of a list: an item itself, or introduced by a line ending with a colon. */
    private boolean listed(int first, int last) {
        for (int at = first; at <= last; at++) {
            if (BULLET.matcher(source.contentAt(at)).matches()) {
                return true;
            }
        }
        return source.contentAt(last).endsWith(":");
    }

    /** The part of a statement a comment above it describes: the header of a compound statement, else all of it. */
    static String header(Statement statement, String code) {
        if (statement instanceof ExpressionStatement || statement instanceof ReturnStatement) {
            int brace = code.indexOf('{');
            return brace < 0 ? code : code.substring(0, brace);
        }
        int brace = code.indexOf('{');
        int newline = code.indexOf('\n');
        int cut = brace < 0 ? newline : newline < 0 ? brace : Math.min(brace, newline);
        return cut < 0 ? code : code.substring(0, cut);
    }

    /** A comment's content words, stop words left out, identifiers split, each reduced to its stem. */
    static List<String> commentWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            for (String word : NamingFinder.words(matcher.group())) {
                if (word.length() > 1 && !STOP_WORDS.contains(word)) {
                    words.add(stem(word));
                }
            }
        }
        return words;
    }

    /** The stems a statement's identifiers, keywords, operators and small numbers say. */
    static Set<String> statementWords(String code) {
        Set<String> words = new HashSet<>();
        Matcher matcher = TOKEN.matcher(code);
        while (matcher.find()) {
            String token = matcher.group();
            for (String said : SAID.getOrDefault(token, List.of())) {
                words.add(stem(said));
            }
            for (String word : NamingFinder.words(token)) {
                words.add(stem(word));
                for (String said : SAID.getOrDefault(word, List.of())) {
                    words.add(stem(said));
                }
            }
        }
        return words;
    }

    /** A crude English stem, the same on both sides: {@code counts}, {@code counted}, {@code counting} → count. */
    static String stem(String word) {
        String value = word.toLowerCase(Locale.ROOT);
        if (value.endsWith("ies") && value.length() > 4) {
            value = value.substring(0, value.length() - 3) + "y";
        } else if (value.matches(".*(ss|x|z|ch|sh)es")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("ing") && value.length() > 5) {
            value = value.substring(0, value.length() - 3);
        } else if (value.endsWith("ed") && value.length() > 4) {
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("s") && !value.endsWith("ss") && value.length() > 3) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("e") && value.length() > 3 ? value.substring(0, value.length() - 1) : value;
    }

    private void blocks() {
        for (int[] run : runs(false)) {
            if (!layout.inMethodBody(run[0])) {
                continue;
            }
            int prose = 0;
            for (int at = run[0]; at <= run[1]; at++) {
                prose += accounted.get(at) || source.contentAt(at).isEmpty() ? 0 : 1;
            }
            if (prose > settings.maxBlockLines()) {
                context.report("GS061", span(run[0], run[1]), prose + " comment lines in a row in a method body (at "
                        + "most " + settings.maxBlockLines() + "); a why in one line is wanted, or none", null,
                        List.of());
            }
        }
    }

    private void javadoc() {
        for (Comment comment : source.comments()) {
            if (comment.kind() != Kind.JAVADOC || !comment.ownLine()) {
                continue;
            }
            Member member = layout.memberAfter(comment.endLine(), lines);
            if (member == null) {
                continue;
            }
            Range first = span(comment.line(), comment.line());
            if (member.isPrivate()) {
                context.report("GS062", first, "Javadoc on private " + member.kind() + " " + member.name() + "; a "
                        + "private member is read with its code, so a one line comment saying why is enough, or "
                        + "none", null, List.of());
            }
            if (!(member.node() instanceof MethodNode method)) {
                continue;
            }
            int text = 0;
            for (int at = comment.line(); at <= comment.endLine(); at++) {
                String content = source.contentAt(at);
                text += content != null && !content.isEmpty() ? 1 : 0;
            }
            if (text > settings.maxJavadocLines()) {
                context.report("GS062", first, "Javadoc of " + text + " text lines on " + member.name() + " (at most "
                        + settings.maxJavadocLines() + "); one sentence on what it does and why is enough", null,
                        List.of());
            }
            repeatedTags(comment, method);
        }
    }

    private void repeatedTags(Comment comment, MethodNode method) {
        for (int at = comment.line(); at <= comment.endLine(); at++) {
            String content = source.contentAt(at);
            Matcher tag = content == null ? null : TAG.matcher(content);
            if (tag == null || !tag.matches()) {
                continue;
            }
            int last = at;
            StringBuilder description = new StringBuilder(tag.group(2));
            while (last + 1 <= comment.endLine() && continues(source.contentAt(last + 1), last + 1, comment)) {
                last++;
                description.append(' ').append(source.contentAt(last));
            }
            String name = "";
            Set<String> signature = new HashSet<>();
            if ("param".equals(tag.group(1))) {
                String[] parts = description.toString().strip().split("\\s+", 2);
                name = parts[0];
                description = new StringBuilder(parts.length > 1 ? parts[1] : "");
                if (!hasParameter(method, name)) {
                    continue;
                }
                signature.addAll(NamingFinder.words(name));
            } else {
                signature.addAll(nameWords(method.getName()));
                signature.addAll(returnedWords(method));
            }
            if (repeats(description.toString(), signature)) {
                String tagText = "@" + tag.group(1) + (name.isEmpty() ? "" : " " + name);
                boolean whole = at > comment.line() && last < comment.endLine();
                context.report("GS062", span(at, last), tagText + " repeats the signature; say what the signature "
                        + "cannot (a unit, a range, what null means), or drop the tag", whole ? "Remove the tag" : null,
                        whole ? removeLines(at, last) : List.of());
            }
        }
    }

    private boolean continues(String content, int at, Comment comment) {
        return content != null && !content.isEmpty() && !content.startsWith("@") && source.commentAt(at) == comment;
    }

    private static boolean hasParameter(MethodNode method, String name) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a tag's description adds nothing to the signature words: empty, generic, or the name again. */
    static boolean repeats(String description, Set<String> signature) {
        String plain = description.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1").replaceAll("<[^>]+>", " ");
        Matcher matcher = WORD.matcher(plain);
        while (matcher.find()) {
            for (String word : NamingFinder.words(matcher.group())) {
                if (!GENERIC_TAG_WORDS.contains(word) && !signature.contains(word)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> nameWords(String name) {
        List<String> words = new ArrayList<>(NamingFinder.words(name));
        if (words.size() > 1 && ACCESSOR_PREFIXES.contains(words.get(0))) {
            words.remove(0);
        }
        return words;
    }

    /** The words of what a one statement method returns, when it returns a variable or a property. */
    private static List<String> returnedWords(MethodNode method) {
        Statement code = method.getCode();
        if (code instanceof BlockStatement block && block.getStatements().size() == 1) {
            code = block.getStatements().get(0);
        }
        Expression returned = code instanceof ReturnStatement statement ? statement.getExpression()
                : code instanceof ExpressionStatement statement ? statement.getExpression() : null;
        if (returned instanceof VariableExpression variable) {
            return NamingFinder.words(variable.getName());
        }
        if (returned instanceof PropertyExpression property && property.getPropertyAsString() != null) {
            return NamingFinder.words(property.getPropertyAsString());
        }
        return List.of();
    }

    private void commentedOutCode() {
        for (int[] run : runs(false)) {
            int at = run[0];
            while (at <= run[1]) {
                if (!codeLike(source.contentAt(at))) {
                    at++;
                    continue;
                }
                int end = at;
                while (end + 1 <= run[1] && codeLike(source.contentAt(end + 1))
                        && sameBlock(source.commentAt(at), source.commentAt(end + 1))) {
                    end++;
                }
                if (!example(run[0], at)) {
                    reportCode(at, end);
                }
                at = end + 1;
            }
        }
    }

    /**
     * Whether the code-like lines starting at {@code at} illustrate the prose above them: the nearest prose line
     * before them, in the same run, ends with a colon or gives an example.
     */
    private boolean example(int first, int at) {
        for (int before = at - 1; before >= first; before--) {
            String content = source.contentAt(before);
            if (content.chars().anyMatch(Character::isLetter) && !codeLike(content)) {
                return content.endsWith(":") || content.matches("(?i).*\\b(e\\.g\\.|for example|example|for instance|"
                        + "such as)\\b.*");
            }
        }
        return false;
    }

    /** Whether two comment lines belong together: both {@code //} lines, or lines of one block comment. */
    private static boolean sameBlock(Comment one, Comment other) {
        return one == other || one.kind() == Kind.LINE && other.kind() == Kind.LINE;
    }

    private void reportCode(int first, int last) {
        Comment comment = source.commentAt(first);
        if (comment.kind() == Kind.JAVADOC) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int at = first; at <= last; at++) {
            text.append(source.contentAt(at)).append('\n');
        }
        String joined = text.toString().strip();
        if (!(joined.contains("(") || joined.contains("=") || joined.contains("{") || joined.endsWith(";")
                || joined.endsWith(")")) || !parses(joined)) {
            return;
        }
        accounted.set(first, last + 1);
        boolean whole = comment.kind() == Kind.LINE || wholeBlock(comment, first, last);
        int count = last - first + 1;
        context.report("GS063", span(first, last), (count == 1 ? "a line" : count + " lines") + " of commented out "
                + "code; delete it, version control keeps the history", whole ? "Remove the commented out code" : null,
                whole ? removeLines(comment.kind() == Kind.LINE ? first : comment.line(),
                        comment.kind() == Kind.LINE ? last : comment.endLine()) : List.of());
    }

    /** Whether lines {@code first} to {@code last} hold all the text of a block comment alone on its lines. */
    private boolean wholeBlock(Comment comment, int first, int last) {
        for (int at = comment.line(); at <= comment.endLine(); at++) {
            if (source.commentAt(at) != comment || (at < first || at > last) && !source.contentAt(at).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether a comment line reads like a line of code rather than a sentence. */
    static boolean codeLike(String content) {
        if (content == null || content.isEmpty() || TODO.matcher(content).find() || content.startsWith("@")
                || BULLET.matcher(content).matches()) {
            return false;
        }
        if (content.matches("[})\\]]+[;,)]*")) {
            return true;
        }
        boolean shaped = content.contains("(") || content.contains("=") || content.contains("{")
                || content.endsWith(";") || content.endsWith(")");
        if (!shaped) {
            return false;
        }
        String[] tokens = content.split("\\s+");
        int prose = 0;
        for (String token : tokens) {
            if (OPERATOR.matcher(token).matches()) {
                return true; // an operator standing alone is code, not a sentence
            }
            String word = token.replaceAll("^[(\"'\\[]+|[)\"'\\],.:;!?]+$", "");
            prose += PROSE_TOKEN.matcher(word).matches() ? 1 : 0;
        }
        return prose * 2 <= tokens.length;
    }

    /** Whether a text parses as Groovy statements, syntax only: nothing is resolved, transformed or run. */
    static boolean parses(String text) {
        SourceUnit unit = SourceUnit.create("Comment.groovy", text);
        try {
            unit.parse();
            unit.completePhase();
            unit.nextPhase();
            unit.convert();
            return !unit.getErrorCollector().hasErrors();
        } catch (RuntimeException | GroovyBugError e) {
            return false; // any failure to parse means the text is not code
        }
    }

    private void decoration() {
        for (int at = 0; at < source.lineCount(); at++) {
            Comment comment = source.commentAt(at);
            String content = source.contentAt(at);
            if (comment == null) {
                todo(at);
                continue;
            }
            boolean single = comment.line() == comment.endLine();
            boolean banner = BANNER.matcher(content).matches() && (comment.kind() == Kind.LINE || single
                    || at != comment.line() && at != comment.endLine());
            boolean empty = content.isEmpty() && (comment.kind() == Kind.LINE ? !proseAround(at) : single);
            if (banner || empty) {
                accounted.set(at);
                boolean removable = comment.kind() == Kind.LINE || single;
                context.report("GS064", span(at, at), (banner ? "a banner line" : "an empty comment") + "; it "
                        + "decorates and says nothing", removable ? "Remove the line" : null,
                        removable ? removeLines(at, at) : List.of());
            } else if (todo(at)) {
                accounted.set(at);
            }
        }
    }

    /** Reports an unowned TODO or FIXME on a line; true when it did. */
    private boolean todo(int at) {
        if (!settings.todoNeedsOwner()) {
            return false;
        }
        Comment comment = source.commentOn(at);
        if (comment == null) {
            return false;
        }
        String text = comment.kind() == Kind.LINE ? source.raw(comment) : lines.line(at);
        Matcher todo = TODO.matcher(text);
        if (!todo.find() || OWNED.matcher(text.substring(todo.end())).find()) {
            return false;
        }
        context.report("GS064", span(at, at), todo.group(1) + " without an owner or a ticket; write "
                + todo.group(1) + "(name) or " + todo.group(1) + " ABC-123, or do it now", null, List.of());
        return true;
    }

    /** Whether an empty line comment separates paragraphs of a comment (a non-empty comment line on each side). */
    private boolean proseAround(int at) {
        String before = source.contentAt(at - 1);
        String after = source.contentAt(at + 1);
        return before != null && !before.isEmpty() && after != null && !after.isEmpty();
    }

    private void density() {
        for (Span method : layout.methods) {
            int code = 0;
            int comments = 0;
            int run = 0;
            boolean why = false;
            for (int at = method.first(); at <= method.last() + 1; at++) {
                code += at <= method.last() && source.isCode(at) ? 1 : 0;
                if (at <= method.last() && source.isCommentOnly(at)) {
                    run++;
                    why |= WHY.matcher(source.contentAt(at)).find();
                } else {
                    comments += why ? 0 : run; // a reason is wanted: GS061 limits its length, not this count
                    run = 0;
                    why = false;
                }
            }
            double ratio = code == 0 ? 0 : (double) comments / code;
            if (code >= 5 && ratio > settings.maxDensity()) {
                MethodNode node = method.node();
                String name = node instanceof ConstructorNode ? node.getDeclaringClass().getNameWithoutPackage()
                        : node.getName();
                Range range = AstRanges.nameRange(node, AstRanges.rangeOf(node), lines);
                context.report("GS065", range != null ? range : span(method.first(), method.first()), comments
                        + " comment lines (reasons not counted) to " + code + " code lines in " + name
                        + String.format(Locale.ROOT, " (%.2f, at most %.2f); keep a why where the code cannot say it",
                                ratio, settings.maxDensity()), null, List.of());
            }
        }
    }

    /**
     * Maximal runs of consecutive comment lines, as {first, last}: own-line {@code //} comments only when
     * {@code lineCommentsOnly}, else comment lines of any kind.
     */
    private List<int[]> runs(boolean lineCommentsOnly) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int at = 0; at <= source.lineCount(); at++) {
            Comment comment = source.commentAt(at);
            boolean member = comment != null && (!lineCommentsOnly || comment.kind() == Kind.LINE);
            if (member && start < 0) {
                start = at;
            } else if (!member && start >= 0) {
                runs.add(new int[] {start, at - 1});
                start = -1;
            }
        }
        return runs;
    }

    /** The range of whole lines {@code first} to {@code last}, from the first non-blank character. */
    private Range span(int first, int last) {
        String text = lines.line(first);
        int indent = text.length() - text.stripLeading().length();
        return new Range(new Position(first, indent), new Position(last, lines.line(last).length()));
    }

    /** An edit deleting lines {@code first} to {@code last} with their line breaks. */
    private List<TextEdit> removeLines(int first, int last) {
        Position end = last + 1 < lines.lineCount() ? new Position(last + 1, 0)
                : new Position(last, lines.line(last).length());
        return List.of(new TextEdit(new Range(new Position(first, 0), end), ""));
    }

    /** A method or constructor with a body: its lines, zero based. */
    record Span(MethodNode node, int first, int last, int bodyFirst, int bodyLast) {
    }

    /** A declaration a Javadoc may document. */
    record Member(AnnotatedNode node, String kind, String name, boolean isPrivate, int line) {
    }

    /** What the tree says about where comments sit: methods, closures, members and the statement on each line. */
    static final class Layout extends ClassCodeVisitorSupport {

        final List<Span> methods = new ArrayList<>();
        final List<int[]> closures = new ArrayList<>();
        final List<Member> members = new ArrayList<>();
        final TreeMap<Integer, Statement> statements = new TreeMap<>();
        private final SourceUnit unit;

        Layout(SourceUnit unit) {
            this.unit = unit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return unit;
        }

        void visitClassNode(ClassNode classNode) {
            if (classNode.getLineNumber() > 0 && !classNode.isScript()) {
                members.add(new Member(classNode, "class", classNode.getNameWithoutPackage(),
                        java.lang.reflect.Modifier.isPrivate(classNode.getModifiers()), classNode.getLineNumber() - 1));
            }
            visitClass(classNode);
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            if (node.getLineNumber() > 0 && !node.isSynthetic() && !node.isScriptBody() && !generated(node)) {
                members.add(new Member(node, isConstructor ? "constructor" : "method", isConstructor
                        ? node.getDeclaringClass().getNameWithoutPackage() : node.getName(), node.isPrivate(),
                        node.getLineNumber() - 1));
                Statement code = node.getCode();
                if (code != null && code.getLineNumber() > 0) {
                    methods.add(new Span(node, node.getLineNumber() - 1, node.getLastLineNumber() - 1,
                            code.getLineNumber() - 1, code.getLastLineNumber() - 1));
                }
            }
            super.visitConstructorOrMethod(node, isConstructor);
        }

        /** Whether a transformation wrote the method ({@code @Generated}): it has no comments of its own. */
        private static boolean generated(MethodNode node) {
            return node.getAnnotations().stream()
                    .anyMatch(annotation -> "Generated".equals(annotation.getClassNode().getNameWithoutPackage()));
        }

        @Override
        public void visitField(FieldNode node) {
            if (node.getLineNumber() > 0 && !node.isSynthetic() && node.getOwner().getProperty(node.getName()) == null) {
                members.add(new Member(node, "field", node.getName(), node.isPrivate(), node.getLineNumber() - 1));
            }
            super.visitField(node);
        }

        @Override
        public void visitProperty(PropertyNode node) {
            if (node.getLineNumber() > 0) {
                members.add(new Member(node, "property", node.getName(), false, node.getLineNumber() - 1));
            }
            super.visitProperty(node);
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            if (expression.getLineNumber() > 0) {
                closures.add(new int[] {expression.getLineNumber() - 1, expression.getLastLineNumber() - 1});
            }
            super.visitClosureExpression(expression);
        }

        @Override
        protected void visitStatement(Statement statement) {
            if (statement.getLineNumber() > 0 && !(statement instanceof BlockStatement)) {
                statements.putIfAbsent(statement.getLineNumber() - 1, statement);
            }
            super.visitStatement(statement);
        }

        /** Whether a line lies inside the braces of a method or a closure. */
        boolean inBody(int line) {
            for (int[] closure : closures) {
                if (closure[0] < line && line < closure[1]) {
                    return true;
                }
            }
            return inMethodBody(line);
        }

        /** Whether a line lies inside the braces of a method or constructor. */
        boolean inMethodBody(int line) {
            for (Span method : methods) {
                if (method.bodyFirst() < line && line < method.bodyLast()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The member a Javadoc ending on {@code line} documents: the first one declared below it with only blank
         * and annotation lines between.
         */
        Member memberAfter(int line, TextLines lines) {
            Member found = null;
            for (Member member : members) {
                if (member.line() > line && (found == null || member.line() < found.line())) {
                    found = member;
                }
            }
            if (found == null) {
                return null;
            }
            for (int at = line + 1; at < found.line(); at++) {
                String text = lines.line(at).strip();
                if (!text.isEmpty() && !text.startsWith("@")) {
                    return null;
                }
            }
            return found;
        }
    }
}
