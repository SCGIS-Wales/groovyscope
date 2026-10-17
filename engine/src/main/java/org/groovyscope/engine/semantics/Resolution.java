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
package org.groovyscope.engine.semantics;

import java.util.List;
import org.codehaus.groovy.ast.ASTNode;
import org.groovyscope.engine.model.Confidence;

/**
 * What a node refers to: one or more candidate declarations and how sure the engine is. The first
 * candidate is the best guess; the rest only matter when the confidence is {@code DYNAMIC_UNKNOWN}.
 */
public record Resolution(List<ASTNode> candidates, Confidence confidence) {

    public Resolution {
        candidates = List.copyOf(candidates);
    }

    public static Resolution of(ASTNode declaration, Confidence confidence) {
        return new Resolution(List.of(declaration), confidence);
    }

    public ASTNode primary() {
        return candidates.get(0);
    }

    public boolean includes(ASTNode declaration) {
        for (ASTNode candidate : candidates) {
            if (candidate == declaration) {
                return true;
            }
        }
        return false;
    }
}
