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
package org.groovyscope.engine.lsp

import groovy.transform.CompileStatic
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

/** Test double that records everything the server sends to the client. */
@CompileStatic
class RecordingLanguageClient implements LanguageClient {

    final List<Object> received = new CopyOnWriteArrayList<>()

    @Override
    void telemetryEvent(Object object) {
        received << object
    }

    @Override
    void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        received << diagnostics
    }

    @Override
    void showMessage(MessageParams messageParams) {
        received << messageParams
    }

    @Override
    CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        received << requestParams
        return CompletableFuture.completedFuture(null)
    }

    @Override
    void logMessage(MessageParams message) {
        received << message
    }
}
