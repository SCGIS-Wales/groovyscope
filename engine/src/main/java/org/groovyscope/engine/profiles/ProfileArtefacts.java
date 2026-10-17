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
package org.groovyscope.engine.profiles;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.practices.PracticeFinder;

/**
 * Findings on the files around governed code rather than in it: descriptor values the runtime rejects (RP004),
 * and infrastructure variables for descriptor-supplied parameters that carry a default (RP015) or their own
 * validation block (RP014), both of which duplicate what the descriptor already decides.
 */
public final class ProfileArtefacts {

    private ProfileArtefacts() {
    }

    /** Findings per file URI for every descriptor and infrastructure file of the profile under the root. */
    public static Map<String, List<Diagnostic>> find(Profile profile, Path root, EngineConfig config) {
        Map<String, List<Diagnostic>> out = new TreeMap<>();
        if (profile.descriptor() == null) {
            return out;
        }
        List<Descriptors.Document> documents = Descriptors.all(profile.descriptor(), root, config.exclude());
        for (Descriptors.Document document : documents) {
            for (Profile.ForbiddenValue forbidden : profile.descriptor().forbiddenValues()) {
                JsonElement value = Descriptors.at(document.json(), forbidden.path());
                if (value != null && value.isJsonPrimitive() && forbidden.values().contains(value.getAsString())) {
                    add(out, "RP004", Descriptors.locate(document.file(), forbidden.path()), config,
                            forbidden.path() + " = " + value.getAsString() + " is not accepted by " + profile.name()
                                    + (forbidden.message().isBlank() ? "" : ": " + forbidden.message()));
                }
            }
        }
        java.util.Set<String> supplied = new java.util.HashSet<>();
        for (Descriptors.Document document : documents) {
            supplied.addAll(document.parameters().keySet());
        }
        for (Descriptors.Variable variable : Descriptors.variables(profile.infrastructure(), root, config.exclude())) {
            boolean fromDescriptor = supplied.stream().anyMatch(name -> Descriptors.sameName(variable.name(), name));
            if (!fromDescriptor) {
                continue;
            }
            if (profile.infrastructure().noDefaults() && variable.defaultLine() != null) {
                add(out, "RP015", variable.defaultLine(), config, "variable " + variable.name()
                        + " is supplied from the descriptor; give it a type and description, not a default");
            }
            if (profile.infrastructure().noValidation() && variable.validationLine() != null) {
                add(out, "RP014", variable.validationLine(), config, "variable " + variable.name()
                        + " is validated where the descriptor parameter is declared; a second validation block drifts");
            }
        }
        return out;
    }

    private static void add(Map<String, List<Diagnostic>> out, String code, Location location, EngineConfig config,
            String message) {
        Severity severity = PracticeFinder.severity(code, config);
        if (severity != null) {
            out.computeIfAbsent(location.uri(), k -> new ArrayList<>())
                    .add(new Diagnostic(location.uri(), location.range(), severity, ProfileFinder.SOURCE, code, message));
        }
    }
}
