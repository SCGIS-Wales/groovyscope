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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Answers "what does this code mean and how do I fix it" for any diagnostic code: the practice catalogue first,
 * then CodeNarc's own rule descriptions for the rules the catalogue does not cover.
 */
public final class Explanations {

    static final String CODENARC_RULES = "https://codenarc.org/codenarc-rule-index.html";
    private static final String CODENARC_MESSAGES = "codenarc-base-messages";

    private Explanations() {
    }

    /** The explanation as a JSON-ready map, or empty when nothing is known about the code. */
    public static Optional<Map<String, Object>> explain(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        PracticeCatalog catalog = PracticeCatalog.bundled();
        Optional<PracticeCatalog.Entry> entry = catalog.find(code);
        if (entry.isPresent()) {
            PracticeCatalog.Entry found = entry.get();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", found.code());
            map.put("kind", found.kind());
            map.put("group", found.group());
            map.put("title", found.title());
            map.put("why", found.why());
            map.put("fix", found.fix());
            map.put("severity", found.severity());
            map.put("link", catalog.link(code));
            return Optional.of(map);
        }
        return codeNarcDescription(code).map(description -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("kind", "codenarc");
            map.put("title", code);
            map.put("why", description);
            map.put("fix", "See the rule's documentation; groovyscope offers a quick fix where one is safe.");
            map.put("link", CODENARC_RULES);
            return map;
        });
    }

    /** CodeNarc's description of a rule, from the messages bundled with CodeNarc. */
    static Optional<String> codeNarcDescription(String rule) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(CODENARC_MESSAGES);
            String key = rule + ".description";
            return bundle.containsKey(key) ? Optional.of(bundle.getString(key)) : Optional.empty();
        } catch (MissingResourceException e) {
            return Optional.empty();
        }
    }

    /** A documentation link for a diagnostic, or {@code null}. */
    public static String link(String code, String source) {
        String link = PracticeCatalog.bundled().link(code);
        if (link != null) {
            return link;
        }
        return "codenarc".equals(source) && codeNarcDescription(code).isPresent() ? CODENARC_RULES : null;
    }

    /** One Markdown paragraph for a hover: the title, why it matters and the fix. */
    public static Optional<String> hoverNote(String code) {
        return explain(code).map(map -> "**" + code + "** " + map.get("title") + ": " + map.get("why")
                + "\n\n_Fix:_ " + map.get("fix"));
    }
}
