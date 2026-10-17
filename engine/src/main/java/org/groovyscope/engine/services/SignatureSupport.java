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
package org.groovyscope.engine.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.SignatureHelp;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.TypeNames;

/** Signature help for the innermost call whose argument list contains the cursor. */
final class SignatureSupport {

    private SignatureSupport() {
    }

    static Optional<SignatureHelp> signatureHelp(Snapshot snapshot, String uri, Position position) {
        AstIndex index = snapshot.index();
        TextLines lines = index.lines(uri);
        NodeEntry call = null;
        NodeEntry name = null;
        for (NodeEntry entry : index.entries(uri)) {
            if (!entry.range().contains(position)) {
                if (entry.range().start().isAfter(position)) {
                    break;
                }
                continue;
            }
            ASTNode node = entry.node();
            boolean isCall = node instanceof MethodCallExpression || node instanceof StaticMethodCallExpression
                    || node instanceof ConstructorCallExpression;
            if (isCall && (call == null || entry.range().extent() <= call.range().extent())) {
                call = entry;
            }
        }
        if (call == null) {
            return Optional.empty();
        }
        for (NodeEntry entry : index.entries(uri)) {
            if (entry.parent() == call.node() && entry.role() == NodeEntry.Role.METHOD_NAME) {
                name = entry;
            }
        }
        NodeEntry resolvable = name != null ? name : call;
        Optional<Resolution> resolution = snapshot.resolver().resolve(resolvable);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        List<SignatureHelp.Signature> signatures = new ArrayList<>();
        for (ASTNode candidate : resolution.get().candidates()) {
            if (candidate instanceof MethodNode method) {
                List<String> parameters = new ArrayList<>();
                for (Parameter parameter : method.getParameters()) {
                    parameters.add(TypeNames.parameter(parameter));
                }
                signatures.add(new SignatureHelp.Signature(TypeNames.signature(method), parameters, null));
            }
        }
        if (signatures.isEmpty()) {
            return Optional.empty();
        }
        // Scan from the end of the method name, or from the start of a constructor call ("new Foo(").
        Position scanFrom = name != null ? name.range().end() : call.range().start();
        int active = activeParameter(lines, scanFrom, position);
        return Optional.of(new SignatureHelp(signatures, 0, active));
    }

    /** Counts top-level commas between the first {@code (} after the name and the cursor. */
    static int activeParameter(TextLines lines, Position afterName, Position cursor) {
        int start = lines.offsetOf(afterName);
        int end = lines.offsetOf(cursor);
        String text = lines.text();
        int depth = 0;
        int commas = 0;
        boolean opened = false;
        char quote = 0;
        for (int i = start; i < end && i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                if (opened) {
                    depth++;
                } else {
                    opened = true;
                }
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && opened && depth == 0) {
                commas++;
            }
        }
        return commas;
    }
}
