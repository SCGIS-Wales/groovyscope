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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.TypeNames;
import org.groovyscope.engine.workspace.TestSources;

/**
 * Naming checks, GS020 to GS027: whether names say what the things they name are and do. Each check reports only
 * from facts in the tree (a declared type, an assignment to a field, a leading word), never from guesses about
 * intent; the agent's semantic judgement builds on these with {@code check_naming}. Loop and lambda variables,
 * catch parameters, test methods and the project's own vocabulary ({@code naming.allowedAbbreviations},
 * {@code naming.vocabulary}) are left alone.
 */
public final class NamingFinder {

    static final Set<String> BOOLEAN_PREFIXES = Set.copyOf(List.of("is", "has", "can", "should", "was", "were", "will", "needs",
            "allows", "supports", "contains", "exists", "enabled", "disabled", "matches", "equals", "accepts",
            "requires", "includes", "uses", "ignores", "skips", "are", "does", "did", "must", "may"));
    /** Last words that make a verb-first class name a noun phrase: a RemoveAllOnSelfRule is a rule. */
    static final Set<String> ROLE_NOUNS = Set.copyOf(List.of("rule", "visitor", "handler", "listener", "checker",
            "validator", "command", "action", "task", "job", "step", "filter", "strategy", "policy", "event",
            "exception", "error", "result", "request", "response", "spec", "test", "tests", "it", "builder",
            "factory", "provider", "service", "controller", "resolver", "processor", "converter", "mapper",
            "reader", "writer", "parser", "formatter", "plugin", "extension", "adapter", "decorator",
            "interceptor", "callback", "hook", "operation", "operator", "function", "predicate", "matcher",
            "collector", "generator", "loader", "runner", "executor", "scheduler", "worker", "agent", "client",
            "server", "module", "component", "bean", "dto", "entity", "model", "view", "page", "screen",
            "dialog", "form", "report", "message", "query"));

    static final Set<String> VERBS = Set.copyOf(List.of("get", "set", "is", "has", "can", "should", "add", "remove", "delete",
            "create", "build", "make", "find", "search", "lookup", "load", "save", "store", "read", "write", "parse",
            "format", "render", "validate", "verify", "check", "resolve", "compute", "calculate", "count", "sum",
            "apply", "run", "execute", "start", "stop", "open", "close", "init", "initialise", "initialize", "reset",
            "clear", "update", "refresh", "sync", "fetch", "retrieve", "send", "receive", "post", "put", "patch",
            "upload", "download", "import", "export", "convert", "transform", "map", "filter", "sort", "merge",
            "split", "join", "compare", "match", "handle", "process", "register", "unregister", "subscribe",
            "publish", "notify", "emit", "fire", "trigger", "schedule", "cancel", "retry", "wait", "await", "sleep",
            "lock", "unlock", "acquire", "release", "grant", "revoke", "enable", "disable", "toggle", "select",
            "choose", "pick", "collect", "gather", "accept", "reject", "approve", "deny", "allow", "require",
            "ensure", "assert", "expect", "report", "log", "print", "show", "hide", "display", "draw", "paint",
            "describe", "explain", "summarise", "summarize", "list", "visit", "walk", "scan", "inspect", "analyse",
            "analyze", "evaluate", "estimate", "measure", "test", "setup", "teardown", "configure", "prepare",
            "provision", "deploy", "install", "uninstall", "migrate", "rotate", "renew", "issue", "sign", "encrypt",
            "decrypt", "hash", "encode", "decode", "serialise", "serialize", "deserialise", "deserialize", "wrap",
            "unwrap", "copy", "clone", "move", "rename", "replace", "insert", "append", "prepend", "push", "pop",
            "peek", "poll", "offer", "take", "give", "provide", "supply", "consume", "produce", "generate", "derive",
            "extract", "inject", "bind", "unbind", "attach", "detach", "connect", "disconnect", "mount", "unmount",
            "to", "as", "from", "with", "on", "before", "after", "do", "call", "invoke", "try", "use", "visit",
            "compile", "link", "resolve", "capture", "record", "track", "mark", "flag", "tag", "label", "name",
            "determine", "decide", "detect", "discover", "locate", "normalise", "normalize", "sanitise", "sanitize",
            "escape", "unescape", "trim", "strip", "pad", "fill", "drain", "flush", "commit", "rollback", "begin",
            "end", "finish", "complete", "fail", "succeed", "throw", "raise", "catch", "recover", "restore", "backup",
            "archive", "purge", "evict", "expire", "touch", "ping", "poll", "watch", "observe", "listen", "stamp",
            "assign", "unassign", "allocate", "free", "reserve", "book", "plan", "estimate", "forecast", "predict",
            "train", "learn", "score", "rank", "rate", "weigh", "balance", "adjust", "tune", "optimise", "optimize",
            "warm", "cool", "heat", "spin", "turn", "flip", "rotate", "shift", "scale", "resize", "crop", "zoom",
            "main", "equals", "hashCode", "toString", "compareTo", "iterator", "spliterator", "stream", "size",
            "length", "isEmpty", "clone", "finalize", "close", "flush", "write", "read", "accept", "apply", "test",
            "compare", "run", "call", "get", "update", "put", "remove", "contains", "collect", "reduce", "each",
            "any", "every", "sum", "max", "min", "first", "last", "head", "tail", "init", "next", "previous",
            "cleanup", "setupSpec", "cleanupSpec", "propertyMissing", "methodMissing", "invokeMethod", "getProperty",
            "setProperty"));
    static final Set<String> GENERIC_NAMES = Set.copyOf(List.of("data", "info", "tmp", "temp", "obj", "object",
            "stuff", "thing", "things", "foo", "bar", "baz", "retval", "misc", "dummy", "placeholder", "whatever",
            "something", "anything", "everything"));
    static final Set<String> CLASS_SUFFIXES = Set.copyOf(List.of("Manager", "Helper", "Util", "Utils", "Misc",
            "Stuff", "Thing"));
    static final Set<String> ABBREVIATIONS = Set.copyOf(List.of("cfg", "mgr", "svc", "ctx", "btn", "usr", "pwd", "pw", "num",
            "cnt", "idx", "tmp", "tmpl", "msg", "err", "ex", "exc", "req", "res", "resp", "rsp", "conn", "cfgs",
            "env", "evt", "fn", "func", "impl", "init", "len", "lst", "mtd", "nbr", "obj", "opt", "opts", "pkg",
            "ptr", "ref", "src", "dst", "dest", "str", "val", "var", "vals", "vars", "buf", "buff", "chk", "clr",
            "cmd", "cmp", "col", "cols", "ctl", "ctrl", "cur", "curr", "db", "def", "del", "dir", "dirs", "doc",
            "docs", "elem", "elt", "fld", "frm", "grp", "hdr", "img", "ins", "inst", "lbl", "lib", "lnk", "loc",
            "max", "min", "mod", "nm", "no", "nr", "obs", "org", "orig", "pos", "prev", "prod", "prop", "props",
            "qry", "rec", "rect", "reg", "rem", "rpt", "sec", "seq", "sess", "sig", "spec", "sql", "stmt", "sub",
            "sys", "tbl", "tkn", "tok", "txt", "typ", "usr", "ver", "win", "wnd", "yr"));
    static final Set<String> ALLOWED_ABBREVIATIONS = Set.copyOf(List.of("id", "ids", "uri", "url", "urls", "uuid", "json", "xml",
            "yaml", "html", "http", "https", "sql", "jdbc", "jvm", "jdk", "api", "apis", "ui", "io", "os", "cpu",
            "ram", "dns", "ip", "tcp", "udp", "ssl", "tls", "jwt", "oauth", "saml", "cors", "csv", "pdf", "png",
            "svg", "md5", "sha", "sha256", "utf8", "ascii", "regex", "max", "min", "sum", "avg", "db", "env",
            "args", "argv", "spec", "specs", "log", "ok", "iso", "utc", "gmt", "ttl", "cli", "mcp", "lsp", "pr",
            "mr", "ci", "cd", "sdk", "ast", "dto", "dsl", "pom", "gradle", "git", "ssh", "sftp", "ftp", "smtp",
            "imap", "rest", "soap", "grpc", "aws", "gcp", "s3", "ec2", "iam", "kms", "vpc", "eks", "ecs", "ecr",
            "rds", "sqs", "sns", "ses", "lambda", "crud", "acl", "rbac", "ldap", "ad", "sso", "mfa", "otp", "pkce",
            "oidc", "idp", "sp", "cn", "dn", "ou", "sam", "upn", "guid", "ref", "refs", "src", "dst"));
    static final Pattern TYPE_PREFIX = Pattern.compile("^(?:str|int|bool|b|s|i|n|f|m_|arr|lst|obj|o|p|ptr)[A-Z_].*");
    static final Pattern SINGLE_LETTER = Pattern.compile("^[a-zA-Z]$");
    static final Set<String> LOOP_LETTERS = Set.copyOf(List.of("i", "j", "k", "n", "x", "y", "z", "e", "t", "it", "k", "v"));
    static final Set<String> COLLECTION_TYPES = Set.copyOf(List.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Collection", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.TreeSet", "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.SortedMap", "java.util.NavigableMap", "java.util.Queue", "java.util.Deque"));
    static final Set<String> MAP_TYPES = Set.copyOf(List.of("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap", "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentHashMap"));
    static final Set<String> NUMBER_TYPES = Set.copyOf(List.of("int", "long", "short", "byte", "double", "float",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger", "java.lang.Number"));
    static final Set<String> COUNT_WORDS = Set.copyOf(List.of("count", "size", "length", "total", "number", "index", "amount",
            "sum", "max", "min", "limit", "offset", "page", "retries", "attempts", "seconds", "millis", "minutes",
            "hours", "days", "width", "height", "depth", "age", "score", "rank", "level", "percent"));
    static final Set<String> DATE_WORDS = Set.copyOf(List.of("date", "time", "timestamp", "instant", "expiry", "deadline",
            "createdAt", "updatedAt", "expiresAt", "startedAt", "finishedAt"));
    /** Pairs of synonyms a code base should pick one of. */
    static final List<List<String>> SYNONYMS = List.of(
            List.of("get", "fetch", "retrieve", "load", "obtain", "acquire"),
            List.of("remove", "delete", "destroy", "erase", "drop"),
            List.of("create", "make", "build", "construct", "new"),
            List.of("find", "search", "lookup", "locate", "query"),
            List.of("update", "modify", "change", "edit", "alter"),
            List.of("check", "validate", "verify", "ensure", "assert"),
            List.of("start", "begin", "launch", "init", "initialise", "initialize"),
            List.of("stop", "end", "finish", "terminate", "halt", "shutdown"),
            List.of("send", "post", "dispatch", "emit", "publish", "transmit"),
            List.of("show", "display", "render", "print", "present"));

    private NamingFinder() {
    }

    /** Naming findings for one Groovy document. */
    public static List<PracticeFinder.Practice> find(String uri, ModuleNode module, EngineConfig config) {
        List<PracticeFinder.Practice> out = new ArrayList<>();
        if (module == null || uri.endsWith(".java") || !config.practices().naming().enabled()) {
            return out;
        }
        Visitor visitor = new Visitor(uri, config, out);
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() > 0 && !classNode.getName().contains("$_")) {
                visitor.visitClassNode(classNode);
            }
        }
        return out;
    }

    /** Words of an identifier, lower-cased: {@code resolveApiAssignments} gives resolve, api, assignments. */
    static List<String> words(String identifier) {
        List<String> words = new ArrayList<>();
        if (identifier == null || identifier.isEmpty()) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (c == '_' || c == '$') {
                if (current.length() > 0) {
                    words.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                continue;
            }
            boolean boundary = Character.isUpperCase(c) && current.length() > 0
                    && (!Character.isUpperCase(current.charAt(current.length() - 1))
                            || (i + 1 < identifier.length() && Character.isLowerCase(identifier.charAt(i + 1))));
            if (boundary) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            current.append(c);
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    static boolean isBoolean(ClassNode type) {
        return type != null && (ClassHelper.boolean_TYPE.equals(type) || ClassHelper.Boolean_TYPE.equals(type));
    }

    static boolean looksBoolean(String name) {
        List<String> parts = words(name);
        return !parts.isEmpty() && BOOLEAN_PREFIXES.contains(parts.get(0)) && parts.size() > 1;
    }

    /** The visitor over one document's classes. */
    private static final class Visitor extends ClassCodeVisitorSupport {
        private final String uri;
        private final EngineConfig config;
        private final List<PracticeFinder.Practice> out;
        private final Set<String> allowedAbbreviations;
        private final Map<String, String> vocabulary;
        private ClassNode currentClass;
        private boolean testClass;

        Visitor(String uri, EngineConfig config, List<PracticeFinder.Practice> out) {
            this.uri = uri;
            this.config = config;
            this.out = out;
            this.allowedAbbreviations = new TreeSet<>(ALLOWED_ABBREVIATIONS);
            for (String word : config.practices().naming().allowedAbbreviations()) {
                allowedAbbreviations.add(word.toLowerCase(Locale.ROOT));
            }
            this.vocabulary = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : config.practices().naming().vocabulary().entrySet()) {
                vocabulary.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().toLowerCase(Locale.ROOT));
            }
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        void visitClassNode(ClassNode classNode) {
            currentClass = classNode;
            String simple = classNode.getNameWithoutPackage();
            int dollar = simple.lastIndexOf('$');
            String name = dollar >= 0 ? simple.substring(dollar + 1) : simple;
            testClass = TestSources.isTest(uri, name) || uri.contains("/src/test/");
            if (!classNode.isScript()) {
                checkClassName(classNode, name);
            }
            for (FieldNode field : classNode.getFields()) {
                if (!field.isSynthetic() && classNode.getProperty(field.getName()) == null) {
                    checkMember(field, field.getName(), field.getType(), field.isDynamicTyped(), "field");
                }
            }
            for (PropertyNode property : classNode.getProperties()) {
                checkMember(property, property.getName(), property.getType(), property.isDynamicTyped(), "property");
            }
            Map<String, List<String>> verbsUsed = new LinkedHashMap<>();
            for (MethodNode method : classNode.getMethods()) {
                if (method.isSynthetic() || method.getLineNumber() < 1 || method.isScriptBody()) {
                    continue;
                }
                checkMethod(method, verbsUsed);
                if (method.getCode() != null) {
                    method.getCode().visit(new Locals(method));
                }
            }
            checkVocabulary(classNode, verbsUsed);
        }

        private void checkClassName(ClassNode classNode, String name) {
            List<String> parts = words(name);
            if (parts.isEmpty()) {
                return;
            }
            String first = parts.get(0);
            if (parts.size() == 1 && GENERIC_NAMES.contains(first)) {
                report("GS023", classNode, "class name '" + name + "' says nothing about what it is");
            }
            for (String suffix : CLASS_SUFFIXES) {
                if (name.endsWith(suffix) && name.length() > suffix.length() && !testClass) {
                    report("GS023", classNode, "class name '" + name + "' ends in '" + suffix
                            + "', which hides what the class is responsible for");
                    break;
                }
            }
            String last = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
            if (VERBS.contains(first) && parts.size() > 1 && !first.equals("test") && !classNode.isInterface()
                    && !ROLE_NOUNS.contains(last) && !first.equals("to") && !first.equals("list")
                    && !first.equals("map") && !first.equals("set") && !first.equals("log")) {
                report("GS022", classNode, "class name '" + name + "' starts with the verb '" + first
                        + "'; classes are named for what they are, methods for what they do");
            }
            checkAbbreviations(classNode, name, "class");
        }

        private void checkMember(ASTNode node, String name, ClassNode type, boolean dynamic, String what) {
            checkGeneric(node, name, what);
            checkAbbreviations(node, name, what);
            checkTypePrefix(node, name, what);
            if (!dynamic) {
                checkBooleanName(node, name, type, what);
                checkContradiction(node, name, type, what);
            }
        }

        private void checkMethod(MethodNode method, Map<String, List<String>> verbsUsed) {
            String name = method.getName();
            if (name.startsWith("<") || name.contains("$")) {
                return;
            }
            boolean publicApi = Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers());
            List<String> parts = words(name);
            String first = parts.isEmpty() ? "" : parts.get(0);
            if (!testClass && !name.contains(" ")) {
                checkGeneric(method, name, "method");
                checkAbbreviations(method, name, "method");
                if (!method.isDynamicReturnType()) {
                    checkBooleanName(method, name, method.getReturnType(), "method");
                    checkContradiction(method, name, method.getReturnType(), "method");
                }
                boolean effectful = ClassHelper.VOID_TYPE.equals(method.getReturnType()) || writesField(method);
                if (publicApi && effectful && !parts.isEmpty() && !VERBS.contains(first)
                        && !BOOLEAN_PREFIXES.contains(first) && !isAccessorLike(method) && parts.size() <= 4
                        && !currentClass.isInterface()) {
                    report("GS022", method, "method name '" + name + "' does not start with a verb, yet calling it "
                            + "changes something; say what it does");
                }
                if (first.equals("get") && parts.size() > 1 && writesField(method)) {
                    report("GS021", method, "'" + name + "' reads like a getter but assigns a field; name it for the "
                            + "change it makes, or make it side-effect free");
                }
            }
            if (!testClass && publicApi && !parts.isEmpty() && !name.contains(" ")) {
                verbsUsed.computeIfAbsent(first, k -> new ArrayList<>()).add(name);
            }
            for (Parameter parameter : method.getParameters()) {
                checkGeneric(parameter, parameter.getName(), "parameter");
                checkAbbreviations(parameter, parameter.getName(), "parameter");
                checkTypePrefix(parameter, parameter.getName(), "parameter");
                if (!parameter.isDynamicTyped()) {
                    checkContradiction(parameter, parameter.getName(), parameter.getType(), "parameter");
                }
            }
        }

        private boolean isAccessorLike(MethodNode method) {
            String name = method.getName();
            return currentClass.getProperty(name) != null || currentClass.getDeclaredField(name) != null
                    || method.getParameters().length == 0 && (currentClass.getProperty(name) != null);
        }

        private void checkBooleanName(ASTNode node, String name, ClassNode type, String what) {
            if (!isBoolean(type)) {
                return;
            }
            List<String> parts = words(name);
            if (parts.isEmpty() || BOOLEAN_PREFIXES.contains(parts.get(0)) || name.endsWith("ed")
                    || name.endsWith("able") || name.endsWith("ible") || parts.get(0).equals("ok")) {
                return;
            }
            report("GS020", node, what + " '" + name + "' is boolean but its name does not read as a question; "
                    + "prefer is" + capitalise(name) + ", has..., can... or should...");
        }

        private void checkContradiction(ASTNode node, String name, ClassNode type, String what) {
            if (type == null) {
                return;
            }
            List<String> parts = words(name);
            if (parts.isEmpty()) {
                return;
            }
            String last = parts.get(parts.size() - 1);
            String typeName = type.getName();
            boolean collection = COLLECTION_TYPES.contains(typeName) || type.isArray();
            boolean map = MAP_TYPES.contains(typeName);
            if ((last.equals("list") || last.equals("array") || last.equals("set")) && map) {
                report("GS026", node, what + " '" + name + "' is a " + TypeNames.simple(type) + " but is named as a "
                        + last);
            } else if (last.equals("map") && collection && !map) {
                report("GS026", node, what + " '" + name + "' is a " + TypeNames.simple(type) + " but is named as a map");
            } else if (looksBoolean(name) && !isBoolean(type) && !what.equals("method")) {
                report("GS026", node, what + " '" + name + "' reads as a yes/no question but is a "
                        + TypeNames.simple(type));
            } else if (looksBoolean(name) && !isBoolean(type) && what.equals("method")
                    && !ClassHelper.VOID_TYPE.equals(type) && !ClassHelper.OBJECT_TYPE.equals(type)
                    && !collection && !map) {
                report("GS026", node, what + " '" + name + "' reads as a yes/no question but returns a "
                        + TypeNames.simple(type));
            } else if (COUNT_WORDS.contains(last) && ClassHelper.STRING_TYPE.equals(type)) {
                report("GS026", node, what + " '" + name + "' is named as a number but is a String");
            } else if (DATE_WORDS.contains(last) && ClassHelper.STRING_TYPE.equals(type) && !what.equals("parameter")) {
                // Information, not a warning: timestamps are often carried as RFC 3339 text on purpose (stored and
                // published verbatim, parsed where compared), so this is a naming question, not a defect.
                reportAtMost(Diagnostic.Severity.INFORMATION, "GS026", node, what + " '" + name
                        + "' is named as a point in time but is a String; if it is timestamp text on purpose, a name "
                        + "such as " + name + "Text says so, otherwise parse it once at the boundary");
            } else if (last.endsWith("s") && !last.endsWith("ss") && !last.equals("status") && !last.equals("class")
                    && !last.equals("address") && !last.equals("alias") && !last.equals("bytes")
                    && (ClassHelper.STRING_TYPE.equals(type) || ClassHelper.boolean_TYPE.equals(type)
                            || ClassHelper.Boolean_TYPE.equals(type))
                    && !COUNT_WORDS.contains(last) && !last.equals("millis") && !last.equals("seconds")
                    && !last.equals("minutes") && !last.equals("hours") && !last.equals("days")
                    && !last.equals("retries") && !last.equals("attempts") && !last.equals("credentials")
                    && !last.equals("contents") && !last.equals("details") && !last.equals("settings")
                    && !last.equals("series") && !last.equals("news") && !last.equals("progress")) {
                report("GS026", node, what + " '" + name + "' is plural but is a single " + TypeNames.simple(type));
            }
        }

        private void checkGeneric(ASTNode node, String name, String what) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (GENERIC_NAMES.contains(lower) && !what.equals("parameter")) {
                report("GS023", node, what + " '" + name + "' says nothing about what it holds or does");
            } else if (GENERIC_NAMES.contains(lower) && what.equals("parameter") && (lower.equals("data")
                    || lower.equals("obj") || lower.equals("tmp") || lower.equals("stuff") || lower.equals("thing"))) {
                report("GS023", node, what + " '" + name + "' says nothing about what it holds");
            }
        }

        private void checkAbbreviations(ASTNode node, String name, String what) {
            for (String word : words(name)) {
                if (ABBREVIATIONS.contains(word) && !allowedAbbreviations.contains(word)) {
                    report("GS024", node, what + " '" + name + "' abbreviates '" + word + "'; spell it out, or list "
                            + "it under naming.allowedAbbreviations when the team reads it fluently");
                    return;
                }
            }
        }

        private void checkTypePrefix(ASTNode node, String name, String what) {
            if (TYPE_PREFIX.matcher(name).matches() && !allowedAbbreviations.contains(words(name).get(0))) {
                report("GS025", node, what + " '" + name + "' encodes its type in a prefix; the declared type "
                        + "already says it");
            }
        }

        private void checkVocabulary(ClassNode classNode, Map<String, List<String>> verbsUsed) {
            for (List<String> family : SYNONYMS) {
                List<String> present = new ArrayList<>();
                for (String verb : family) {
                    if (verbsUsed.containsKey(verb)) {
                        present.add(verb);
                    }
                }
                if (present.size() > 1) {
                    String preferred = null;
                    for (String verb : present) {
                        if (vocabulary.containsKey(verb)) {
                            preferred = vocabulary.get(verb);
                        }
                    }
                    String examples = verbsUsed.get(present.get(0)).get(0) + " and " + verbsUsed.get(present.get(1)).get(0);
                    report("GS027", classNode, "methods use " + String.join(", ", present) + " for one idea ("
                            + examples + "); pick one" + (preferred != null ? ", the vocabulary says '" + preferred + "'"
                            : "") + " so callers can guess names");
                }
            }
            for (Map.Entry<String, List<String>> entry : verbsUsed.entrySet()) {
                String replacement = vocabulary.get(entry.getKey());
                if (replacement != null && !replacement.equals(entry.getKey())) {
                    report("GS027", classNode, "'" + entry.getValue().get(0) + "' uses '" + entry.getKey()
                            + "' where the project vocabulary says '" + replacement + "'");
                }
            }
        }

        private boolean writesField(MethodNode method) {
            boolean[] writes = {false};
            if (method.getCode() == null) {
                return false;
            }
            method.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitBinaryExpression(BinaryExpression expression) {
                    if (Types.isAssignment(expression.getOperation().getType())) {
                        Expression left = expression.getLeftExpression();
                        if (left instanceof VariableExpression variable) {
                            Variable accessed = variable.getAccessedVariable();
                            if (accessed instanceof FieldNode || accessed instanceof PropertyNode) {
                                writes[0] = true;
                            }
                        } else if (left instanceof PropertyExpression property
                                && property.getObjectExpression() instanceof VariableExpression target
                                && target.isThisExpression()) {
                            writes[0] = true;
                        }
                    }
                    super.visitBinaryExpression(expression);
                }

                @Override
                public void visitClosureExpression(ClosureExpression expression) {
                    // assignments inside closures run later or elsewhere; not the getter's own effect
                }
            });
            return writes[0];
        }

        void report(String code, ASTNode node, String message) {
            reportAtMost(null, code, node, message);
        }

        /** {@link #report} with the configured severity lowered to at most {@code ceiling} (null: no ceiling). */
        void reportAtMost(Diagnostic.Severity ceiling, String code, ASTNode node, String message) {
            Diagnostic.Severity configured = PracticeFinder.severity(code, config);
            Diagnostic.Severity severity = configured == null || ceiling == null
                    || configured.ordinal() >= ceiling.ordinal() ? configured : ceiling;
            Range range = AstRanges.rangeOf(node);
            if (severity == null || range == null) {
                return;
            }
            String title = PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::title).orElse(code);
            out.add(new PracticeFinder.Practice(new Diagnostic(uri, range, severity, PracticeFinder.SOURCE, code,
                    title + ": " + message), null, List.<TextEdit>of()));
        }

        static String capitalise(String name) {
            return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        /** Local variables of one method: single letters outside loops and closures, generic names, prefixes. */
        private final class Locals extends CodeVisitorSupport {
            private final MethodNode method;
            private int loops;
            private int closures;

            Locals(MethodNode method) {
                this.method = method;
            }

            @Override
            public void visitForLoop(ForStatement statement) {
                loops++;
                super.visitForLoop(statement);
                loops--;
            }

            @Override
            public void visitClosureExpression(ClosureExpression expression) {
                closures++;
                super.visitClosureExpression(expression);
                closures--;
            }

            @Override
            public void visitCatchStatement(CatchStatement statement) {
                statement.getCode().visit(this);
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression expression) {
                if (!expression.isMultipleAssignmentDeclaration() && !testClass) {
                    VariableExpression variable = expression.getVariableExpression();
                    String name = variable.getName();
                    if (SINGLE_LETTER.matcher(name).matches() && loops == 0 && closures == 0
                            && !LOOP_LETTERS.contains(name)) {
                        report("GS024", expression, "local '" + name + "' in " + method.getName()
                                + " is a single letter outside a loop; say what it holds");
                    } else if (!SINGLE_LETTER.matcher(name).matches()) {
                        checkGeneric(expression, name, "local");
                        checkAbbreviations(expression, name, "local");
                        checkTypePrefix(expression, name, "local");
                        if (!variable.isDynamicTyped()) {
                            checkContradiction(expression, name, variable.getType(), "local");
                        }
                    }
                }
                super.visitDeclarationExpression(expression);
            }
        }
    }

    /** Facts an agent needs to judge a name semantically, for one declaration. */
    public static Map<String, Object> declarationFacts(CodeFacts.MethodFacts method) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", method.name());
        facts.put("words", words(method.name()));
        facts.put("returns", method.returns());
        facts.put("writes", method.writes());
        facts.put("externals", method.externals());
        facts.put("callerCount", method.callerCount());
        return facts;
    }
}
