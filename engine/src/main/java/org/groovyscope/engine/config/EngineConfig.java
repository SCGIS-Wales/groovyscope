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
     */
    public record Practices(boolean enabled, Map<String, String> severities, Naming naming,
            List<String> secretsAllow) {
        public static final Practices DEFAULTS = new Practices(true, Map.of(), Naming.DEFAULTS);

        public Practices {
            severities = Map.copyOf(severities == null ? Map.of() : severities);
            naming = naming == null ? Naming.DEFAULTS : naming;
            secretsAllow = List.copyOf(secretsAllow == null ? List.of() : secretsAllow);
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
            "");

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

    /** Every setting except the workspace budget, which keeps its defaults. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices,
            List<org.groovyscope.engine.profiles.Profile> profiles, long maxFileBytes, Diagnostics diagnostics,
            String configHash, Daemon daemon) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy, practices, profiles,
                maxFileBytes, diagnostics, configHash, daemon, Budget.DEFAULTS);
    }

    /** Every setting except the daemon's, which keep their defaults. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices,
            List<org.groovyscope.engine.profiles.Profile> profiles, long maxFileBytes, Diagnostics diagnostics,
            String configHash) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy, practices, profiles,
                maxFileBytes, diagnostics, configHash, Daemon.DEFAULTS);
    }

    /** Every setting, with the default file size limit and no configuration hash. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices,
            List<org.groovyscope.engine.profiles.Profile> profiles) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy, practices, profiles,
                DEFAULT_MAX_FILE_BYTES, "");
    }

    /** Every setting except the diagnostics delivery, which keeps its defaults. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices,
            List<org.groovyscope.engine.profiles.Profile> profiles, long maxFileBytes, String configHash) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy, practices, profiles,
                maxFileBytes, Diagnostics.DEFAULTS, configHash);
    }

    /** This configuration with another Groovy target (used for files a runtime profile governs). */
    public EngineConfig withGroovy(Groovy target) {
        return new EngineConfig(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, target,
                practices, profiles, maxFileBytes, diagnostics, configHash, daemon, budget);
    }

    /** This configuration with other daemon settings. */
    public EngineConfig withDaemon(Daemon replacement) {
        return new EngineConfig(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy,
                practices, profiles, maxFileBytes, diagnostics, configHash, replacement, budget);
    }

    /** This configuration with the hash of the raw JSON it was read from. */
    public EngineConfig withConfigHash(String hash) {
        return new EngineConfig(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy,
                practices, profiles, maxFileBytes, diagnostics, hash, daemon, budget);
    }

    /** This configuration with another file size limit (0 means no limit). */
    public EngineConfig withMaxFileBytes(long limit) {
        return new EngineConfig(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy,
                practices, profiles, limit, diagnostics, configHash, daemon, budget);
    }

    /** SHA-256 (hex) of a raw configuration document, or empty when there is none. */
    public static String hashOf(String json) {
        if (json == null) {
            return "";
        }
        return org.groovyscope.engine.util.Hashes.sha256Hex(json);
    }

    /** All settings except runtime profiles. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources, Groovy groovy, Practices practices) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, groovy, practices, List.of());
    }

    /** Everything except the Groovy target and practice settings, which keep their defaults. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format, boolean javaSources) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, javaSources, Groovy.DEFAULTS,
                Practices.DEFAULTS);
    }

    /** Convenience for callers that only care about workspace settings. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust) {
        this(sourceRoots, classpath, exclude, trust, Lint.DEFAULTS, TypeCheck.DEFAULTS, Format.DEFAULTS, true,
                Groovy.DEFAULTS, Practices.DEFAULTS);
    }

    /** All settings except {@code javaSources}, which stays on. */
    public EngineConfig(List<String> sourceRoots, List<String> classpath, List<String> exclude, boolean trust,
            Lint lint, TypeCheck typecheck, Format format) {
        this(sourceRoots, classpath, exclude, trust, lint, typecheck, format, true, Groovy.DEFAULTS,
                Practices.DEFAULTS);
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
                        strings(secrets.get("allow"), List.of())),
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

    private static boolean bool(JsonElement element, boolean fallback) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : fallback;
    }
}
