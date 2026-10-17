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
 * A declaration in the workspace.
 *
 * @param name simple name
 * @param kind what it is
 * @param container enclosing class or package, or {@code null}
 * @param location full extent of the declaration
 * @param selectionRange the name token, to place a cursor on
 * @param detail a one-line signature
 * @param children nested declarations (members of a class)
 */
public record Symbol(String name, SymbolKind kind, String container, Location location, Range selectionRange,
        String detail, List<Symbol> children) {

    public Symbol {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
