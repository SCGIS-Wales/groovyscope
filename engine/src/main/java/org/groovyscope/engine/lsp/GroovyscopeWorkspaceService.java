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
package org.groovyscope.engine.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.services.LanguageServices;

/** Workspace-level requests: symbol search and change notifications that invalidate the snapshot. */
final class GroovyscopeWorkspaceService implements WorkspaceService {

    private final Supplier<LanguageServices> services;
    private final DiagnosticsPublisher diagnostics;

    GroovyscopeWorkspaceService(Supplier<LanguageServices> services, DiagnosticsPublisher diagnostics) {
        this.services = services;
        this.diagnostics = diagnostics;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // Configuration arrives through the Python front end (GROOVYSCOPE_CONFIG_JSON); nothing to do here yet.
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        LanguageServices current = services.get();
        if (current != null) {
            current.markDirty();
            diagnostics.schedule();
        }
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = services.get();
            List<WorkspaceSymbol> symbols = new ArrayList<>();
            if (current != null) {
                for (Symbol symbol : current.workspaceSymbols(params.getQuery())) {
                    if (symbol.location() != null) {
                        symbols.add(Converters.workspaceSymbol(symbol));
                    }
                }
            }
            return Either.forRight(symbols);
        });
    }
}
