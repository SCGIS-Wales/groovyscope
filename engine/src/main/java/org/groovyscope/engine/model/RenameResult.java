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
import java.util.Map;

/**
 * Either a set of edits per document, or a refusal with the reason. The engine refuses whenever a
 * reference could only be matched by name, rather than guess.
 */
public record RenameResult(Map<String, List<TextEdit>> edits, String refusalReason) {

    public static RenameResult refused(String reason) {
        return new RenameResult(Map.of(), reason);
    }

    public static RenameResult of(Map<String, List<TextEdit>> edits) {
        return new RenameResult(Map.copyOf(edits), null);
    }

    public boolean isRefused() {
        return refusalReason != null;
    }
}
