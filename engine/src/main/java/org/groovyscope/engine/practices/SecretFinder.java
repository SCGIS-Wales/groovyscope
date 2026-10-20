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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * GS019, hardcoded secrets. A string literal with the shape of a credential (an AWS access key ID, a GitHub token, a
 * JSON Web Token, a private key) is an error wherever it is. A literal given to a name that says secret (password,
 * token, apiKey and the like) is only presumed one, so it is information, and test sources are left alone. Values
 * and names matching {@code secrets.allow} are never reported. The secret itself is never repeated in a message.
 */
final class SecretFinder {

    /** Credential shapes, each with the name a finding uses. */
    static final List<Shape> SHAPES = List.of(
            new Shape("an AWS access key ID", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Shape("a GitHub token", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})")),
            new Shape("a JSON Web Token",
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
            new Shape("a private key",
                    Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY")));
    /** Words in a name that mark its value as a secret (compared without case, underscores or hyphens). */
    static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "passphrase", "secret", "token", "apikey",
            "accesskey", "privatekey", "credential", "credentials");
    /** Literals too short to be a credential (an empty default, a single letter). */
    static final int MIN_LENGTH = 4;

    record Shape(String name, Pattern pattern) {
    }

    private SecretFinder() {
    }

    static void find(PracticeContext context) {
        List<Pattern> allow = new ArrayList<>();
        for (String expression : context.config().practices().secretsAllow()) {
            try {
                allow.add(Pattern.compile(expression));
            } catch (PatternSyntaxException e) {
                org.groovyscope.engine.log.EngineLog.warn("secrets.allow: '%s' is not a regular expression; ignored",
                        expression);
            }
        }
        new Visitor(context, allow).visitModule();
    }

    /** Whether a name says its value is a secret. */
    static boolean secretName(String name) {
        if (name == null) {
            return false;
        }
        String flat = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (String word : SECRET_WORDS) {
            if (flat.contains(word) && !flat.endsWith("hint") && !flat.endsWith("name") && !flat.endsWith("field")
                    && !flat.endsWith("url") && !flat.endsWith("path") && !flat.endsWith("file")) {
                return true;
            }
        }
        return false;
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final PracticeContext context;
        private final List<Pattern> allow;
        private final Set<ASTNode> done = Collections.newSetFromMap(new IdentityHashMap<>());

        Visitor(PracticeContext context, List<Pattern> allow) {
            this.context = context;
            this.allow = allow;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return context.module().getContext();
        }

        void visitModule() {
            for (ClassNode classNode : context.module().getClasses()) {
                visitClass(classNode);
            }
        }

        private boolean allowed(String text) {
            return text != null && allow.stream().anyMatch(pattern -> pattern.matcher(text).find());
        }

        @Override
        public void visitConstantExpression(ConstantExpression constant) {
            if (constant.getValue() instanceof String text && !done.contains(constant) && !allowed(text)) {
                for (Shape shape : SHAPES) {
                    if (shape.pattern().matcher(text).find()) {
                        done.add(constant);
                        context.reportAtLeast(Severity.ERROR, "GS019", constant, "this literal has the shape of "
                                + shape.name() + "; read it from the environment or a secret store, and revoke it");
                        break;
                    }
                }
            }
            super.visitConstantExpression(constant);
        }

        /** A literal given to a secret-sounding name: presumed a secret, so information, and never in tests. */
        private void named(String name, Expression value) {
            if (context.test() || !(value instanceof ConstantExpression constant)
                    || !(constant.getValue() instanceof String text) || text.strip().length() < MIN_LENGTH
                    || !secretName(name) || allowed(name) || allowed(text)) {
                return;
            }
            visitConstantExpression(constant); // a credential shape is the stronger finding
            if (done.add(constant)) {
                context.reportAtMost(Severity.INFORMATION, "GS019", constant, "'" + name + "' is given a literal; "
                        + "if it is a real secret, read it from the environment or a secret store");
            }
        }

        @Override
        public void visitField(FieldNode node) {
            named(node.getName(), node.getInitialExpression());
            super.visitField(node);
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression declaration) {
            if (!declaration.isMultipleAssignmentDeclaration()) {
                named(declaration.getVariableExpression().getName(), declaration.getRightExpression());
            }
            super.visitDeclarationExpression(declaration);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            if (expression.getOperation().getType() == Types.ASSIGN) {
                Expression target = expression.getLeftExpression();
                String name = target instanceof VariableExpression variable ? variable.getName()
                        : target instanceof PropertyExpression property ? property.getPropertyAsString() : null;
                named(name, expression.getRightExpression());
            }
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression entry) {
            if (entry.getKeyExpression() instanceof ConstantExpression key && key.getValue() instanceof String name) {
                named(name, entry.getValueExpression());
            }
            super.visitMapEntryExpression(entry);
        }
    }
}
