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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The practice catalogue ({@code config/practices/practices.json}, bundled as a resource): for every GS code,
 * Groovy 6 checker and the CodeNarc rules worth explaining, what it means, why it matters and how to fix it,
 * with a link into {@code docs/practices.md}.
 */
public final class PracticeCatalog {

    static final String RESOURCE = "org/groovyscope/engine/practices/practices.json";

    /** One catalogue entry; {@code safety} says whether its automatic fix keeps behaviour (see {@link #safety}). */
    public record Entry(String code, String kind, String group, String title, String why, String fix, String severity,
            String section, String safety) {
    }

    /** The automatic fix keeps what the code does: the bulk fix applies it. */
    public static final String PRESERVING = "preserving";
    /** The automatic fix changes what the code does: applied only when its code is asked for by name. */
    public static final String CHANGING = "changing";
    /**
     * Fixable CodeNarc rules without a catalogue entry whose fixes only touch layout or redundant syntax. Any other
     * fixable code without an entry counts as changing.
     */
    static final java.util.Set<String> PRESERVING_WITHOUT_ENTRY = java.util.Set.of("UnnecessarySemicolon",
            "TrailingWhitespace", "ConsecutiveBlankLines", "UnnecessaryDefInMethodDeclaration",
            "UnnecessaryDefInFieldDeclaration");

    private static final PracticeCatalog BUNDLED = load();

    private final String docs;
    private final Map<String, Entry> entries;

    private PracticeCatalog(String docs, Map<String, Entry> entries) {
        this.docs = docs;
        this.entries = Map.copyOf(entries);
    }

    /** The catalogue shipped with the engine. */
    public static PracticeCatalog bundled() {
        return BUNDLED;
    }

    public Optional<Entry> find(String code) {
        return Optional.ofNullable(code == null ? null : entries.get(code));
    }

    /** Whether {@code code}'s automatic fix keeps behaviour ({@link #PRESERVING}) or not ({@link #CHANGING}). */
    public String safety(String code) {
        Entry entry = code == null ? null : entries.get(code);
        if (entry != null && PRESERVING.equals(entry.safety())) {
            return PRESERVING;
        }
        if (entry == null && PRESERVING_WITHOUT_ENTRY.contains(code)) {
            return PRESERVING;
        }
        return CHANGING;
    }

    /** A link to the documentation section for a code, or {@code null} when the catalogue has no entry. */
    public String link(String code) {
        return find(code).map(entry -> docs + "#" + entry.section()).orElse(null);
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    static PracticeCatalog load() {
        try (InputStream in = PracticeCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                JsonObject e = element.getAsJsonObject();
                Entry entry = new Entry(text(e, "code"), text(e, "kind"), text(e, "group"), text(e, "title"),
                        text(e, "why"), text(e, "fix"), text(e, "severity"), text(e, "section"), text(e, "safety"));
                entries.put(entry.code(), entry);
            }
            return new PracticeCatalog(text(root, "docs"), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
