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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/**
 * Navigation across artefacts for governed code: from the parameter name in an accessor call to its descriptor
 * entry and infrastructure variable, every reader of a parameter, and a hover summarising its declaration.
 */
public final class ProfileNavigation {

    /** One accessor call that reads a parameter. */
    public record Read(String parameter, Location location) {
    }

    private ProfileNavigation() {
    }

    /** Every accessor read in a module. */
    public static List<Read> reads(Profile profile, String uri, ModuleNode module) {
        List<Read> reads = new ArrayList<>();
        if (profile.descriptor() == null || module == null) {
            return reads;
        }
        for (ClassNode classNode : module.getClasses()) {
            new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return module.getContext();
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    for (Profile.Accessor accessor : profile.descriptor().accessors()) {
                        if (accessor.method().equals(call.getMethodAsString())
                                && call.getArguments() instanceof TupleExpression args
                                && accessor.nameArgument() < args.getExpressions().size()) {
                            Expression name = args.getExpression(accessor.nameArgument());
                            Range range = AstRanges.rangeOf(name);
                            if (name instanceof ConstantExpression constant && constant.getValue() instanceof String text
                                    && range != null) {
                                reads.add(new Read(text, new Location(uri, range)));
                            }
                        }
                    }
                    super.visitMethodCallExpression(call);
                }
            }.visitClass(classNode);
        }
        return reads;
    }

    /** The parameter whose name is under the position, if the position is on an accessor's name argument. */
    public static Optional<String> parameterAt(Profile profile, String uri, ModuleNode module, Position position) {
        for (Read read : reads(profile, uri, module)) {
            if (read.location().range().contains(position)) {
                return Optional.of(read.parameter());
            }
        }
        return Optional.empty();
    }

    /** Where a parameter is declared: the descriptor entry and the matching infrastructure variables. */
    public static List<Location> declarations(Profile profile, Path root, Path file, String parameter) {
        List<Location> locations = new ArrayList<>();
        Descriptors.forFile(profile.descriptor(), root, file).ifPresent(document -> {
            Descriptors.Parameter declared = document.parameters().get(parameter);
            if (declared != null) {
                locations.add(declared.location());
            }
        });
        for (Descriptors.Variable variable : Descriptors.variables(profile.infrastructure(), root)) {
            if (Descriptors.sameName(variable.name(), parameter)) {
                locations.add(variable.location());
            }
        }
        return locations;
    }

    /** Hover text for a parameter: type, default, description, declaration file and number of readers. */
    public static Optional<String> hover(Profile profile, Path root, Path file, String parameter, int readers) {
        return Descriptors.forFile(profile.descriptor(), root, file).map(document -> {
            Descriptors.Parameter declared = document.parameters().get(parameter);
            if (declared == null) {
                return "**" + parameter + "** is not declared in " + document.file().getFileName();
            }
            StringBuilder text = new StringBuilder("**").append(parameter).append("** `").append(declared.type()).append('`');
            if (declared.defaultValue() != null && !declared.defaultValue().isJsonNull()) {
                text.append(", default `").append(declared.defaultValue()).append('`');
            }
            if (!declared.description().isBlank()) {
                text.append(" — ").append(declared.description());
            }
            text.append("\n\nDeclared in ").append(root.relativize(document.file())).append("; read in ")
                    .append(readers).append(readers == 1 ? " place" : " places").append('.');
            return text.toString();
        });
    }

    /** All reads of a parameter across the given modules. */
    public static List<Location> readers(Profile profile, Map<String, ModuleNode> governed, String parameter) {
        List<Location> locations = new ArrayList<>();
        for (Map.Entry<String, ModuleNode> entry : governed.entrySet()) {
            for (Read read : reads(profile, entry.getKey(), entry.getValue())) {
                if (read.parameter().equals(parameter)) {
                    locations.add(read.location());
                }
            }
        }
        return locations;
    }
}
