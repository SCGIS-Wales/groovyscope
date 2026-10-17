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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.groovyscope.engine.log.EngineLog;

/**
 * Logs JSON-RPC traffic at debug level: each request's method and how long the answer took, notifications by
 * method, and every error response at warning level. Parameters and results are not logged (they hold source
 * text); the method, id and timing are enough to see what an agent or editor asked and what was slow or failed.
 */
final class MessageLogging {

    private final Map<String, long[]> started = new ConcurrentHashMap<>();
    private final Map<String, String> methods = new ConcurrentHashMap<>();

    /** A wrapper for LSP4J's launcher that logs and then passes every message on unchanged. */
    static Function<MessageConsumer, MessageConsumer> wrapper() {
        MessageLogging state = new MessageLogging();
        return next -> message -> {
            state.observe(message);
            next.consume(message);
        };
    }

    void observe(Message message) {
        if (message instanceof RequestMessage request) {
            String id = String.valueOf(request.getId());
            methods.put(id, request.getMethod());
            started.put(id, new long[] {System.nanoTime()});
            EngineLog.debug("<- request %s %s", id, request.getMethod());
        } else if (message instanceof ResponseMessage response) {
            String id = String.valueOf(response.getId());
            long[] start = started.remove(id);
            String method = methods.remove(id);
            long millis = start == null ? -1 : (System.nanoTime() - start[0]) / 1_000_000;
            if (response.getError() != null) {
                EngineLog.warn("-> error %s %s after %d ms: %s", id, method, millis, response.getError().getMessage());
            } else {
                EngineLog.debug("-> answer %s %s in %d ms", id, method, millis);
            }
        } else if (message instanceof NotificationMessage notification
                && EngineLog.enabled(EngineLog.Level.DEBUG)) {
            EngineLog.debug("<> notification %s", notification.getMethod());
        }
    }
}
