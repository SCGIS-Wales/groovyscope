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

import org.groovyscope.engine.compile.CompilationResult;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;

/** One consistent view of the workspace: the compile, its index and the resolver over it. */
public record Snapshot(CompilationResult result, AstIndex index, Resolver resolver, Symbols symbols, long version) {

    public static Snapshot empty() {
        AstIndex index = AstIndex.empty();
        return new Snapshot(CompilationResult.empty(), index, new Resolver(index), new Symbols(index), 0);
    }
}
