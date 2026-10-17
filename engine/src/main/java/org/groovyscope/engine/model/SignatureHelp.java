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

/** Candidate signatures for the call under the cursor. */
public record SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {

    /** One candidate: its label and the labels of its parameters, in order. */
    public record Signature(String label, List<String> parameters, String documentation) {
    }
}
