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
package org.groovyscope.engine.model;

import java.util.List;

/**
 * What would be affected by changing a declaration.
 *
 * @param symbol the declaration under consideration
 * @param change the kind of change
 * @param directCallers call sites that target the declaration with static or inferred confidence
 * @param transitiveCallers callers of the direct callers, and so on, up to the analyzer's depth; those matched by
 *        name only carry {@code DYNAMIC_UNKNOWN} and are not followed further
 * @param overrides methods that override, or are overridden by, the declaration
 * @param tests call sites that live in test sources; those matched by name only ({@code DYNAMIC_UNKNOWN}) are
 *        possible tests
 * @param dynamicUnknowns call sites matched by name only; they must be reviewed by hand
 * @param riskScore 0 to 100
 * @param riskLevel {@code low}, {@code medium} or {@code high}
 * @param suggestedChecks what to run or review before and after the change
 */
public record ImpactReport(Symbol symbol, Change change, List<CallSite> directCallers,
        List<CallSite> transitiveCallers, List<Symbol> overrides, List<CallSite> tests,
        List<CallSite> dynamicUnknowns, int riskScore, String riskLevel, List<String> suggestedChecks) {

    /** The kinds of change the analyzer understands. */
    public enum Change {
        SIGNATURE,
        RENAME,
        DELETE,
        BEHAVIOUR;

        public static Change parse(String value) {
            if (value == null || value.isBlank()) {
                return SIGNATURE;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown change kind '" + value
                        + "'; use signature, rename, delete or behaviour", e);
            }
        }
    }

    public ImpactReport {
        directCallers = List.copyOf(directCallers);
        transitiveCallers = List.copyOf(transitiveCallers);
        overrides = List.copyOf(overrides);
        tests = List.copyOf(tests);
        dynamicUnknowns = List.copyOf(dynamicUnknowns);
        suggestedChecks = List.copyOf(suggestedChecks);
    }
}
