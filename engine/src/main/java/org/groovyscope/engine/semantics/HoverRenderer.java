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

import groovy.lang.groovydoc.Groovydoc;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.model.Confidence;

/** Markdown for hovers: a Groovy signature block, the declaring class, groovydoc, and the confidence. */
public final class HoverRenderer {

    private HoverRenderer() {
    }

    public static String render(ASTNode declaration, Resolution resolution, ClassNode inferredVariableType) {
        StringBuilder markdown = new StringBuilder();
        String signature = Symbols.detailOf(declaration);
        if (declaration instanceof VariableExpression variable && inferredVariableType != null) {
            signature = TypeNames.simple(inferredVariableType) + " " + variable.getName() + "  // declared with def";
        }
        markdown.append("```groovy\n").append(signature == null ? declaration.getText() : signature).append("\n```\n");
        String container = Symbols.containerOf(declaration);
        if (container != null && !container.isEmpty()) {
            markdown.append("\n*in* `").append(container).append("`\n");
        }
        if (declaration instanceof AnnotatedNode annotated) {
            Groovydoc doc = annotated.getGroovydoc();
            if (doc != null && doc.isPresent()) {
                markdown.append('\n').append(cleanGroovydoc(doc.getContent())).append('\n');
            }
        }
        if (resolution != null && resolution.confidence() != Confidence.STATIC) {
            markdown.append("\n*resolution:* ").append(resolution.confidence().name().toLowerCase());
            if (resolution.candidates().size() > 1) {
                markdown.append(" (").append(resolution.candidates().size()).append(" candidates)");
            }
            markdown.append('\n');
        }
        return markdown.toString();
    }

    static String cleanGroovydoc(String raw) {
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("/**")) {
                trimmed = trimmed.substring(3);
            }
            if (trimmed.endsWith("*/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2);
            }
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
            }
            trimmed = trimmed.strip();
            if (!trimmed.isEmpty()) {
                out.append(trimmed).append('\n');
            }
        }
        return out.toString().strip();
    }
}
