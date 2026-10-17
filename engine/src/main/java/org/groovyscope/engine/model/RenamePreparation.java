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

/** The token a rename would change, or a refusal. */
public record RenamePreparation(Range range, String placeholder, String refusalReason) {

    public static RenamePreparation refused(String reason) {
        return new RenamePreparation(null, null, reason);
    }

    public boolean isRefused() {
        return refusalReason != null;
    }
}
