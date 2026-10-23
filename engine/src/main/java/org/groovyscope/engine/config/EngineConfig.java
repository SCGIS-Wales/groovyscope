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
package org.groovyscope.engine.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The slice of {@code .groovyscope.yaml} the engine needs. The Python front end validates the full document
 * against its schema and hands the effective configuration over as JSON (the {@code GROOVYSCOPE_CONFIG_JSON}
 * environment variable or {@code --config <file>}); the defaults here mirror {@code defaults.yaml}.
 *
 * @param sourceRoots directories, relative to the workspace root, that hold Groovy sources
 * @param classpath jars or directories added to the analysis classpath (never inferred)
 * @param exclude glob patterns of paths to ignore
 * @param trust whether AST transformations found on the workspace classpath may run
 * @param lint CodeNarc settings
 * @param typecheck Groovy 6 type-checker settings
 * @param format formatter settings
 * @param javaSources whether {@code .java} files under the source roots join the compile, so Groovy code that
 *     uses Java classes of the same project resolves (they are never linted, formatted or reported on)
 * @param groovy the Groovy version the code targets and the versions it must stay compatible with
 * @param practices groovyscope's own practice diagnostics (GS codes, docs/practices.md)
 * @param profiles runtime profiles (docs/profiles.md), in the order they are tried
 * @param maxFileBytes source files larger than this are skipped ({@code workspace.maxFileBytes}; 0 means no limit)
 * @param configHash SHA-256 (hex) of the raw configuration JSON handed over in the environment, or empty; lets the
 *     front end detect a daemon started with an older configuration
 * @param daemon the request executors and workspace eviction of the daemon ({@code daemon.*})
 * @param budget how much of a workspace the compile takes on ({@code workspace.maxFiles}, {@code workspace.maxTotalBytes})
 */
public record EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
        Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices,
        List<org.groovyscope.engine.profiles.Profile> profiles, long maxFileBytes, Diagnostics diagnostics,
        String configHash, Daemon daemon, Budget budget) {

    /** Default of {@code workspace.maxFileBytes}. */
    public static final long DEFAULT_MAX_FILE_BYTES = 2_000_000L;

    /** File extensions treated as Groovy sources. */
    public static final Set<String> EXTENSIONS = Set.of("groovy", "gvy", "gy", "gsh", "gradle");
    /** Extension-less file names treated as Groovy sources. */
    public static final Set<String> FILE_NAMES = Set.of("Jenkinsfile");
    /** Extension of Java sources compiled alongside Groovy in joint projects. */
    public static final String JAVA_EXTENSION = "java";

    /**
     * How diagnostics reach an LSP client ({@code diagnostics.*}).
     *
     * @param push which documents get pushed diagnostics after an edit: {@link #PUSH_OPEN} (the documents the
     *     connection has open, the default: an agent or editor works on a few files and the pull request serves
     *     the rest), {@link #PUSH_WORKSPACE} (every compiled document, the pre-0.13 behaviour) or
     *     {@link #PUSH_NONE} (nothing is pushed; clients pull)
     * @param debounceMillis the quiet window after the last edit before a push, in milliseconds
     */
    /**
     * How the daemon serves requests: {@code workerThreads} heavy requests at a time (0 means the processor
     * count, at least 2) with at most {@code requestQueue} waiting before new ones are refused as busy; cheap
     * requests (hover, definition, outline, status) have their own small pool. A workspace nobody has used for
     * {@code workspaceIdleMinutes} is released from memory.
     */
    /**
     * @param diskCheckMillis how long a check of the workspace on disk (did a file change outside the editor?) is
     *                        trusted before the next request walks the tree again; 0 checks on every request
     */
    public record Daemon(int workerThreads, int requestQueue, int workspaceIdleMinutes, int heapPressurePercent,
            int diskCheckMillis) {

        public static final Daemon DEFAULTS = new Daemon(0, 64, 30, 85, 0);

        public Daemon {
            workerThreads = Math.max(0, workerThreads);
            requestQueue = Math.max(1, requestQueue);
            workspaceIdleMinutes = Math.max(1, workspaceIdleMinutes);
            heapPressurePercent = Math.max(1, Math.min(99, heapPressurePercent));
            diskCheckMillis = Math.max(0, diskCheckMillis);
        }

        /** The lanes and eviction settings with the default pressure threshold and disk check. */
        public Daemon(int workerThreads, int requestQueue, int workspaceIdleMinutes) {
            this(workerThreads, requestQueue, workspaceIdleMinutes, 85, 0);
        }

        /** The lanes, eviction and pressure settings with a disk check on every request. */
        public Daemon(int workerThreads, int requestQueue, int workspaceIdleMinutes, int heapPressurePercent) {
            this(workerThreads, requestQueue, workspaceIdleMinutes, heapPressurePercent, 0);
        }

        /** The heavy pool size this setting means on this machine. */
        public int effectiveWorkerThreads() {
            return workerThreads > 0 ? workerThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * How much of a workspace the compile takes on before it stops reading: at most {@code maxFiles} files and
     * {@code maxTotalBytes} of source (0 means no limit). Past the budget the remaining files are left out and
     * reported once ({@code GS101}) rather than loaded until the heap runs out.
     */
    public record Budget(int maxFiles, long maxTotalBytes) {

        public static final Budget DEFAULTS = new Budget(20_000, 256_000_000L);

        public Budget {
            maxFiles = Math.max(0, maxFiles);
            maxTotalBytes = Math.max(0, maxTotalBytes);
        }
    }

    public record Diagnostics(String push, int debounceMillis) {
        public static final String PUSH_OPEN = "open";
        public static final String PUSH_WORKSPACE = "workspace";
        public static final String PUSH_NONE = "none";
        public static final Set<String> PUSH_MODES = Set.of(PUSH_OPEN, PUSH_WORKSPACE, PUSH_NONE);
        public static final int DEFAULT_DEBOUNCE_MILLIS = 300;
        public static final Diagnostics DEFAULTS = new Diagnostics(PUSH_OPEN, DEFAULT_DEBOUNCE_MILLIS);

        public Diagnostics {
            push = push == null || !PUSH_MODES.contains(push.toLowerCase(java.util.Locale.ROOT))
                    ? PUSH_OPEN : push.toLowerCase(java.util.Locale.ROOT);
            debounceMillis = Math.max(0, debounceMillis);
        }
    }

    /**
     * CodeNarc settings.
     *
     * @param ruleset path of the default profile, or {@code null} for the bundled defensive profile
     * @param jenkinsRuleset path of the Jenkins profile, or {@code null} for the bundled one
     * @param gradleRuleset path of the profile for Gradle build scripts, or {@code null} for the bundled one
     * @param failOnPriority {@code check} fails on violations with this priority or more severe (1 is worst)
     * @param publish whether lint findings are pushed as LSP diagnostics
     * @param rules per-rule overrides on top of the loaded rule sets ({@code lint.rules}): rule name to a priority
     *     (1 to 3, which also switches on a rule the profile leaves out), {@link #RULE_OFF} to remove the rule, or
     *     {@link #RULE_ON} to switch on a left-out rule at CodeNarc's own priority
     * @param publishMaxPriority pushed diagnostics include CodeNarc findings only with this priority or more severe
     *     ({@code lint.publishMaxPriority}, 1 to 3); {@code check} and review requests are never filtered
     * @param licenceHeader a regular expression every file linted with the default profile must contain (CodeNarc's
     *     RequiredRegex, reported as {@code LicenceHeader}); {@code null} requires nothing
     */
    public record Lint(String ruleset, String jenkinsRuleset, String gradleRuleset, int failOnPriority,
            boolean publish, Map<String, Integer> rules, int publishMaxPriority, String licenceHeader) {
        /** The value of a {@link #rules} entry that removes the rule. */
        public static final int RULE_OFF = 0;
        /** The value of a {@link #rules} entry that switches on a rule the profile leaves out. */
        public static final int RULE_ON = -1;
        /** Default of {@code lint.publishMaxPriority}. */
        public static final int DEFAULT_PUBLISH_MAX_PRIORITY = 2;
        public static final Lint DEFAULTS = new Lint(null, null, 2, true);

        public Lint {
            rules = Map.copyOf(rules == null ? Map.of() : rules);
            publishMaxPriority = Math.max(1, Math.min(3, publishMaxPriority));
            licenceHeader = licenceHeader == null || licenceHeader.isBlank() ? null : licenceHeader;
        }

        /** The original four settings: the bundled Gradle profile, no rule overrides, the default publish threshold. */
        public Lint(String ruleset, String jenkinsRuleset, int failOnPriority, boolean publish) {
            this(ruleset, jenkinsRuleset, null, failOnPriority, publish, Map.of(), DEFAULT_PUBLISH_MAX_PRIORITY, null);
        }

        /**
         * Parses {@code lint.rules}: each value is {@code "on"}, {@code "off"} or {@code {"priority": 1|2|3}}.
         * Entries of any other shape are ignored with a warning.
         */
        static Map<String, Integer> parseRules(JsonElement element) {
            Map<String, Integer> rules = new java.util.TreeMap<>();
            if (element == null || !element.isJsonObject()) {
                return rules;
            }
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && "off".equalsIgnoreCase(value.getAsString())) {
                    rules.put(entry.getKey(), RULE_OFF);
                    continue;
                }
                if (value.isJsonPrimitive() && "on".equalsIgnoreCase(value.getAsString())) {
                    rules.put(entry.getKey(), RULE_ON);
                    continue;
                }
                JsonElement priority = value.isJsonObject() ? value.getAsJsonObject().get("priority") : null;
                if (priority != null && priority.isJsonPrimitive() && priority.getAsJsonPrimitive().isNumber()
                        && priority.getAsInt() >= 1 && priority.getAsInt() <= 3) {
                    rules.put(entry.getKey(), priority.getAsInt());
                } else {
                    org.groovyscope.engine.log.EngineLog.warn("lint.rules.%s: expected \"on\", \"off\" or {\"priority\": 1|2|3}; ignored",
                            entry.getKey());
                }
            }
            return rules;
        }
    }

    /**
     * Groovy 6 type-checker settings, applied to classes that already carry {@code @CompileStatic} or
     * {@code @TypeChecked}.
     *
     * @param nullChecker {@code off}, {@code lenient} or {@code strict}
     * @param sqlInjection whether to add the SQL injection checker
     * @param purity whether to add the purity and modifies checkers
     * @param configScript the project's compiler configuration script ({@code groovyc --configscript}), relative to
     *     the workspace root; applied when it exists and the workspace is trusted, since it is code
     */
    public record TypeCheck(String nullChecker, boolean sqlInjection, boolean purity, boolean regex,
            boolean formatString, boolean combiner, String configScript) {
        /** Where a project keeps its compiler configuration script by convention. */
        public static final String DEFAULT_CONFIG_SCRIPT = "config/groovyc/compile-static.groovy";
        public static final TypeCheck DEFAULTS = new TypeCheck("strict", true, false, true, true, true);

        public TypeCheck {
            configScript = configScript == null || configScript.isBlank() ? DEFAULT_CONFIG_SCRIPT : configScript;
            if (!java.util.Set.of("off", "lenient", "strict").contains(nullChecker)) {
                throw new IllegalArgumentException("typecheck.nullChecker must be off, lenient or strict, not '"
                        + nullChecker + "'");
            }
        }

        /** The original three settings; the regex, format-string and combiner checkers stay on. */
        public TypeCheck(String nullChecker, boolean sqlInjection, boolean purity) {
            this(nullChecker, sqlInjection, purity, true, true, true);
        }

        /** The checker settings with the conventional configuration script. */
        public TypeCheck(String nullChecker, boolean sqlInjection, boolean purity, boolean regex,
                boolean formatString, boolean combiner) {
            this(nullChecker, sqlInjection, purity, regex, formatString, combiner, DEFAULT_CONFIG_SCRIPT);
        }
    }

    /**
     * The Groovy language level.
     *
     * @param version the version the code is compiled with (the engine itself runs Groovy 6)
     * @param compat older versions the code must still compile on
     */
    public record Groovy(String version, List<String> compat) {
        public static final Groovy DEFAULTS = new Groovy("6.0", List.of("5.1"));

        public Groovy {
            version = version == null || version.isBlank() ? "6.0" : version;
            compat = List.copyOf(compat == null ? List.of() : compat);
        }

        /** True when the target is Groovy 6 or later, so the Groovy 6 type checkers apply. */
        public boolean hasGroovy6Checkers() {
            return major(version) >= 6;
        }

        /** True when any supported version (target or compat) is older than 6, so Groovy 6-only syntax is flagged. */
        public boolean mustCompileOnGroovy5() {
            if (major(version) < 6) {
                return true;
            }
            for (String older : compat) {
                if (major(older) < 6) {
                    return true;
                }
            }
            return false;
        }

        static int major(String version) {
            String head = version.strip().split("\\.", 2)[0];
            try {
                return Integer.parseInt(head);
            } catch (NumberFormatException e) {
                return 6;
            }
        }
    }

    /**
     * groovyscope's practice diagnostics.
     *
     * @param enabled whether the GS diagnostics run at all
     * @param severities per-code overrides: {@code error}, {@code warning}, {@code info} or {@code off}
     * @param secretsAllow regular expressions ({@code secrets.allow}); a literal whose value or name matches one is
     *     never reported as a hardcoded secret (GS019)
     * @param comments the comment checks' thresholds ({@code comments.*}, GS060 to GS065)
     * @param tests the test bloat checks' settings ({@code tests.*}, GS044 to GS048)
     */
    public record Practices(boolean enabled, Map<String, String> severities, Naming naming,
            List<String> secretsAllow, Comments comments, Tests tests) {
        public static final Practices DEFAULTS = new Practices(true, Map.of(), Naming.DEFAULTS);

        public Practices {
            severities = Map.copyOf(severities == null ? Map.of() : severities);
            naming = naming == null ? Naming.DEFAULTS : naming;
            secretsAllow = List.copyOf(secretsAllow == null ? List.of() : secretsAllow);
            comments = comments == null ? Comments.DEFAULTS : comments;
            tests = tests == null ? Tests.DEFAULTS : tests;
        }

        /** Practices with the default test bloat settings. */
        public Practices(boolean enabled, Map<String, String> severities, Naming naming, List<String> secretsAllow,
                Comments comments) {
            this(enabled, severities, naming, secretsAllow, comments, Tests.DEFAULTS);
        }

        /** Practices with the default comment thresholds. */
        public Practices(boolean enabled, Map<String, String> severities, Naming naming, List<String> secretsAllow) {
            this(enabled, severities, naming, secretsAllow, Comments.DEFAULTS);
        }

        /** Practices with the default naming settings. */
        public Practices(boolean enabled, Map<String, String> severities) {
            this(enabled, severities, Naming.DEFAULTS);
        }

        /** Practices with no secrets allow list. */
        public Practices(boolean enabled, Map<String, String> severities, Naming naming) {
            this(enabled, severities, naming, List.of());
        }

        /** The configured severity for a code, or {@code null} to keep the catalogue's default. */
        public String severityOf(String code) {
            return severities.get(code);
        }
    }

    /**
     * Naming checks (GS020 to GS027).
     *
     * @param enabled whether the naming checks run at all
     * @param allowedAbbreviations words the team reads fluently, never reported by GS024
     * @param vocabulary the verb to use for a family of synonyms ({@code fetch: get}), reported by GS027
     */
    public record Naming(boolean enabled, List<String> allowedAbbreviations, Map<String, String> vocabulary) {
        public static final Naming DEFAULTS = new Naming(true, List.of(), Map.of());

        public Naming {
            allowedAbbreviations = List.copyOf(allowedAbbreviations == null ? List.of() : allowedAbbreviations);
            vocabulary = Map.copyOf(vocabulary == null ? Map.of() : vocabulary);
        }
    }

    /**
     * Comment checks (GS060 to GS065) and the edit gate's comment policy; the defaults mirror {@code defaults.yaml}.
     *
     * @param restateShare the share of a comment's words that the statement below it must cover for the comment to
     *     restate the code (GS060)
     * @param maxBlockLines consecutive comment lines allowed in a method body (GS061)
     * @param maxJavadocLines Javadoc text lines allowed on a method, delimiter lines not counted (GS062)
     * @param todoNeedsOwner whether a TODO or FIXME needs an owner or a ticket (GS064)
     * @param maxDensity comment lines over code lines allowed in a method of five code lines or more (GS065)
     * @param maxAddedShare the share of a write's added lines that may be comments (the edit gate)
     * @param exemptPublicJavadoc whether the edit gate leaves one Javadoc line per public member out of that share
     */
    public record Comments(double restateShare, int maxBlockLines, int maxJavadocLines, boolean todoNeedsOwner,
            double maxDensity, double maxAddedShare, boolean exemptPublicJavadoc) {
        public static final Comments DEFAULTS = new Comments(0.7, 2, 5, true, 0.25, 0.2, true);
    }

    /**
     * Test bloat checks (GS044 to GS048), on test sources only; the defaults mirror {@code defaults.yaml}.
     *
     * @param maxTestToCodeRatio test file lines allowed per line of the class under test's file (GS046)
     * @param maxMethodsPerSubject test methods allowed in one test class (GS046)
     * @param namePattern a regular expression every test method name matches (GS048)
     * @param vagueNames words that say no behaviour on their own ({@code test}, {@code works}); a name made only of
     *     them, numbers and the names of what it calls says nothing (GS048)
     */
    public record Tests(double maxTestToCodeRatio, int maxMethodsPerSubject, String namePattern,
            List<String> vagueNames) {
        /** A sentence of two words or more with a space (Spock), or an identifier of two words or more. */
        public static final String DEFAULT_NAME_PATTERN = "^(?:.*\\S\\s+\\S.*|_*[a-z][a-z0-9]*"
                + "(?:_*[A-Z][a-z0-9]*|_+[a-z][a-z0-9]*){1,}_*)$";
        public static final List<String> DEFAULT_VAGUE_NAMES = List.of("test", "tests", "testing", "should", "it",
                "works", "work", "working", "method", "check", "checks", "verify", "verifies", "case", "scenario",
                "simple", "basic", "happy", "path", "example", "sample", "ok", "correct", "correctly", "properly",
                "fine", "success", "successful", "foo", "bar", "baz", "a", "an", "the", "of", "for", "and", "to",
                "with", "is", "does");
        public static final Tests DEFAULTS = new Tests(3.0, 40, DEFAULT_NAME_PATTERN, DEFAULT_VAGUE_NAMES);

        public Tests {
            namePattern = namePattern == null || namePattern.isBlank() ? DEFAULT_NAME_PATTERN : namePattern;
            vagueNames = List.copyOf(vagueNames == null ? DEFAULT_VAGUE_NAMES : vagueNames);
        }
    }

    /**
     * Formatter settings.
     *
     * @param engine {@code basic} (bundled whitespace and CodeNarc-driven fixes) or {@code greclipse} (not bundled)
     * @param indentSize spaces per indentation level
     * @param maxBlankLines consecutive blank lines allowed
     * @param removeSemicolons remove unnecessary trailing semicolons
     */
    public record Format(String engine, int indentSize, int maxBlankLines, boolean removeSemicolons) {
        public static final Format DEFAULTS = new Format("basic", 4, 1, true);
    }

    public static final EngineConfig DEFAULTS = new EngineConfig(
            List.of("src/main/groovy", "src/test/groovy", "src/main/java", "src/test/java", "vars"),
            List.of(),
            List.of("**/build/**", "**/.gradle/**", "**/target/**", "**/node_modules/**"),
            false,
            Lint.DEFAULTS,
            TypeCheck.DEFAULTS,
            Format.DEFAULTS,
            true,
            Groovy.DEFAULTS,
            Practices.DEFAULTS,
            List.of(),
            DEFAULT_MAX_FILE_BYTES,
            Diagnostics.DEFAULTS,
            "",
            Daemon.DEFAULTS,
            Budget.DEFAULTS);

    public EngineConfig {
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
        exclude = List.copyOf(exclude);
        lint = lint == null ? Lint.DEFAULTS : lint;
        typecheck = typecheck == null ? TypeCheck.DEFAULTS : typecheck;
        format = format == null ? Format.DEFAULTS : format;
        groovy = groovy == null ? Groovy.DEFAULTS : groovy;
        practices = practices == null ? Practices.DEFAULTS : practices;
        profiles = List.copyOf(profiles == null ? List.of() : profiles);
        maxFileBytes = Math.max(0, maxFileBytes);
        configHash = configHash == null ? "" : configHash;
        daemon = daemon == null ? Daemon.DEFAULTS : daemon;
        budget = budget == null ? Budget.DEFAULTS : budget;
    }

    /** This configuration with another Groovy target (used for files a runtime profile governs). */
    public EngineConfig withGroovy(Groovy target) {
        return toBuilder().groovy(target).build();
    }

    /** This configuration with other daemon settings. */
    public EngineConfig withDaemon(Daemon replacement) {
        return toBuilder().daemon(replacement).build();
    }

    /** This configuration with the hash of the raw JSON it was read from. */
    public EngineConfig withConfigHash(String hash) {
        return toBuilder().configHash(hash).build();
    }

    /** This configuration with another file size limit (0 means no limit). */
    public EngineConfig withMaxFileBytes(long limit) {
        return toBuilder().maxFileBytes(limit).build();
    }

    /** A builder starting from {@link #DEFAULTS}. */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /** A builder starting from this configuration. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** A configuration set field by field; every field not set keeps the value of the one it started from. */
    public static final class Builder {
        private List<String> sourceRoots;
        private List<String> classpath;
        private List<String> exclude;
        private boolean trust;
        private Lint lint;
        private TypeCheck typecheck;
        private Format format;
        private boolean javaSources;
        private Groovy groovy;
        private Practices practices;
        private List<org.groovyscope.engine.profiles.Profile> profiles;
        private long maxFileBytes;
        private Diagnostics diagnostics;
        private String configHash;
        private Daemon daemon;
        private Budget budget;

        private Builder(EngineConfig from) {
            sourceRoots = from.sourceRoots;
            classpath = from.classpath;
            exclude = from.exclude;
            trust = from.trust;
            lint = from.lint;
            typecheck = from.typecheck;
            format = from.format;
            javaSources = from.javaSources;
            groovy = from.groovy;
            practices = from.practices;
            profiles = from.profiles;
            maxFileBytes = from.maxFileBytes;
            diagnostics = from.diagnostics;
            configHash = from.configHash;
            daemon = from.daemon;
            budget = from.budget;
        }

        public Builder sourceRoots(List<String> value) {
            sourceRoots = value;
            return this;
        }

        public Builder classpath(List<String> value) {
            classpath = value;
            return this;
        }

        public Builder exclude(List<String> value) {
            exclude = value;
            return this;
        }

        public Builder trust(boolean value) {
            trust = value;
            return this;
        }

        public Builder lint(Lint value) {
            lint = value;
            return this;
        }

        public Builder typecheck(TypeCheck value) {
            typecheck = value;
            return this;
        }

        public Builder format(Format value) {
            format = value;
            return this;
        }

        public Builder javaSources(boolean value) {
            javaSources = value;
            return this;
        }

        public Builder groovy(Groovy value) {
            groovy = value;
            return this;
        }

        public Builder practices(Practices value) {
            practices = value;
            return this;
        }

        public Builder profiles(List<org.groovyscope.engine.profiles.Profile> value) {
            profiles = value;
            return this;
        }

        public Builder maxFileBytes(long value) {
            maxFileBytes = value;
            return this;
        }

        public Builder diagnostics(Diagnostics value) {
            diagnostics = value;
            return this;
        }

        public Builder configHash(String value) {
            configHash = value;
            return this;
        }

        public Builder daemon(Daemon value) {
            daemon = value;
            return this;
        }

        public Builder budget(Budget value) {
            budget = value;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy,
                    practices, profiles, maxFileBytes, diagnostics, configHash, daemon, budget);
        }
    }

    /** SHA-256 (hex) of a raw configuration document, or empty when there is none. */
    public static String hashOf(String json) {
        if (json == null) {
            return "";
        }
        return org.groovyscope.engine.util.Hashes.sha256Hex(json);
    }


    /**
     * Parses the JSON form of the effective configuration. Unknown keys are ignored; missing keys keep
     * their defaults. A blank document yields the defaults.
     */
    public static EngineConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULTS;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("invalid engine configuration JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("engine configuration must be a JSON object");
        }
        JsonObject document = root.getAsJsonObject();
        JsonObject workspace = objectOrEmpty(document.get("workspace"));
        JsonObject lint = objectOrEmpty(document.get("lint"));
        JsonObject typecheck = objectOrEmpty(document.get("typecheck"));
        JsonObject format = objectOrEmpty(document.get("format"));
        JsonObject groovy = objectOrEmpty(document.get("groovy"));
        JsonObject practices = objectOrEmpty(document.get("practices"));
        JsonObject naming = objectOrEmpty(document.get("naming"));
        JsonObject secrets = objectOrEmpty(document.get("secrets"));
        JsonObject comments = objectOrEmpty(document.get("comments"));
        JsonObject tests = objectOrEmpty(document.get("tests"));
        JsonObject diagnostics = objectOrEmpty(document.get("diagnostics"));
        JsonObject daemon = objectOrEmpty(document.get("daemon"));
        return new EngineConfig(
                strings(workspace.get("sourceRoots"), DEFAULTS.sourceRoots()),
                strings(workspace.get("classpath"), DEFAULTS.classpath()),
                strings(workspace.get("exclude"), DEFAULTS.exclude()),
                bool(workspace.get("trust"), DEFAULTS.trust()),
                new Lint(
                        string(lint.get("ruleset"), null),
                        string(lint.get("jenkinsRuleset"), null),
                        string(lint.get("gradleRuleset"), null),
                        integer(lint.get("failOnPriority"), Lint.DEFAULTS.failOnPriority()),
                        bool(lint.get("publish"), Lint.DEFAULTS.publish()),
                        Lint.parseRules(lint.get("rules")),
                        integer(lint.get("publishMaxPriority"), Lint.DEFAULT_PUBLISH_MAX_PRIORITY),
                        string(lint.get("licenceHeader"), null)),
                new TypeCheck(
                        string(typecheck.get("nullChecker"), TypeCheck.DEFAULTS.nullChecker()),
                        bool(typecheck.get("sqlInjection"), TypeCheck.DEFAULTS.sqlInjection()),
                        bool(typecheck.get("purity"), TypeCheck.DEFAULTS.purity()),
                        bool(typecheck.get("regex"), TypeCheck.DEFAULTS.regex()),
                        bool(typecheck.get("formatString"), TypeCheck.DEFAULTS.formatString()),
                        bool(typecheck.get("combiner"), TypeCheck.DEFAULTS.combiner()),
                        string(typecheck.get("configScript"), TypeCheck.DEFAULT_CONFIG_SCRIPT)),
                new Format(
                        string(format.get("engine"), Format.DEFAULTS.engine()),
                        integer(format.get("indentSize"), Format.DEFAULTS.indentSize()),
                        integer(format.get("maxBlankLines"), Format.DEFAULTS.maxBlankLines()),
                        bool(format.get("removeSemicolons"), Format.DEFAULTS.removeSemicolons())),
                bool(workspace.get("javaSources"), DEFAULTS.javaSources()),
                new Groovy(string(groovy.get("version"), Groovy.DEFAULTS.version()),
                        strings(groovy.get("compat"), Groovy.DEFAULTS.compat())),
                new Practices(bool(practices.get("enabled"), Practices.DEFAULTS.enabled()),
                        stringMap(practices.get("severity")),
                        new Naming(bool(naming.get("enabled"), Naming.DEFAULTS.enabled()),
                                strings(naming.get("allowedAbbreviations"), List.of()),
                                stringMap(naming.get("vocabulary"))),
                        strings(secrets.get("allow"), List.of()),
                        new Comments(number(comments.get("restateShare"), Comments.DEFAULTS.restateShare()),
                                integer(comments.get("maxBlockLines"), Comments.DEFAULTS.maxBlockLines()),
                                integer(comments.get("maxJavadocLines"), Comments.DEFAULTS.maxJavadocLines()),
                                bool(comments.get("todoNeedsOwner"), Comments.DEFAULTS.todoNeedsOwner()),
                                number(comments.get("maxDensity"), Comments.DEFAULTS.maxDensity()),
                                number(comments.get("maxAddedShare"), Comments.DEFAULTS.maxAddedShare()),
                                bool(comments.get("exemptPublicJavadoc"), Comments.DEFAULTS.exemptPublicJavadoc())),
                        new Tests(number(tests.get("maxTestToCodeRatio"), Tests.DEFAULTS.maxTestToCodeRatio()),
                                integer(tests.get("maxMethodsPerSubject"), Tests.DEFAULTS.maxMethodsPerSubject()),
                                string(tests.get("namePattern"), Tests.DEFAULT_NAME_PATTERN),
                                strings(tests.get("vagueNames"), Tests.DEFAULT_VAGUE_NAMES))),
                profiles(document.get("profiles")),
                longValue(workspace.get("maxFileBytes"), DEFAULTS.maxFileBytes()),
                new Diagnostics(string(diagnostics.get("push"), Diagnostics.DEFAULTS.push()),
                        integer(diagnostics.get("debounceMillis"), Diagnostics.DEFAULTS.debounceMillis())),
                "",
                new Daemon(integer(daemon.get("workerThreads"), Daemon.DEFAULTS.workerThreads()),
                        integer(daemon.get("requestQueue"), Daemon.DEFAULTS.requestQueue()),
                        integer(daemon.get("workspaceIdleMinutes"), Daemon.DEFAULTS.workspaceIdleMinutes()),
                        integer(daemon.get("heapPressurePercent"), Daemon.DEFAULTS.heapPressurePercent()),
                        integer(daemon.get("diskCheckMillis"), Daemon.DEFAULTS.diskCheckMillis())),
                new Budget(integer(workspace.get("maxFiles"), Budget.DEFAULTS.maxFiles()),
                        longValue(workspace.get("maxTotalBytes"), Budget.DEFAULTS.maxTotalBytes())));
    }

    private static long longValue(JsonElement element, long fallback) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsLong();
        }
        return fallback;
    }

    private static List<org.groovyscope.engine.profiles.Profile> profiles(JsonElement element) {
        List<org.groovyscope.engine.profiles.Profile> profiles = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    profiles.add(org.groovyscope.engine.profiles.Profile.fromJson(item.getAsJsonObject()));
                }
            }
        }
        return profiles;
    }

    private static Map<String, String> stringMap(JsonElement element) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    values.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return values;
    }

    /** True when the file name denotes a Java source ({@code Foo.java}). */
    public static boolean isJavaFileName(String fileName) {
        return fileName.endsWith("." + JAVA_EXTENSION) && fileName.length() > JAVA_EXTENSION.length() + 1;
    }

    /** True when the file name denotes a Groovy source. */
    public static boolean isGroovyFileName(String fileName) {
        if (FILE_NAMES.contains(fileName)) {
            return true;
        }
        for (String name : FILE_NAMES) {
            if (fileName.startsWith(name + ".")) {
                return true;
            }
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(fileName.substring(dot + 1));
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static List<String> strings(JsonElement element, List<String> fallback) {
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                values.add(item.getAsString());
            }
        }
        return values;
    }

    private static String string(JsonElement element, String fallback) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int integer(JsonElement element, int fallback) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        return fallback;
    }

    private static double number(JsonElement element, double fallback) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        return fallback;
    }

    private static boolean bool(JsonElement element, boolean fallback) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }
}
