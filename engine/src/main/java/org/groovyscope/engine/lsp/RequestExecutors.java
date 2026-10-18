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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.concurrent.Cancellation;

/**
 * Two bounded lanes for the requests of every connection in the process, instead of the common pool: a
 * {@code heavy} pool for work that compiles, lints or type-checks, and a small {@code light} pool for hover,
 * definition, outline and status, so one long type check never starves navigation. Each lane has a bounded
 * queue; a request that finds the queue full is answered at once with a {@code RequestFailed} error naming
 * the load, which is back pressure the client can act on instead of a timeout. Every request runs with its
 * {@link CancelChecker} bound as the thread's {@link Cancellation} flag, so {@code $/cancelRequest} reaches
 * the loops in the engine.
 */
public final class RequestExecutors {

    /** The light lane: enough for an editor's hover and outline traffic while the heavy lane is busy. */
    static final int LIGHT_THREADS = 2;
    static final int LIGHT_QUEUE = 256;

    private final ThreadPoolExecutor heavy;
    private final ThreadPoolExecutor light;

    public RequestExecutors(int heavyThreads, int heavyQueue) {
        this.heavy = pool("groovyscope-heavy", Math.max(1, heavyThreads), Math.max(1, heavyQueue));
        this.light = pool("groovyscope-light", LIGHT_THREADS, LIGHT_QUEUE);
    }

    /** Executors sized from {@code daemon.workerThreads} and {@code daemon.requestQueue}. */
    public static RequestExecutors forConfig(EngineConfig config) {
        EngineConfig.Daemon daemon = config.daemon();
        return new RequestExecutors(daemon.effectiveWorkerThreads(), daemon.requestQueue());
    }

    private static ThreadPoolExecutor pool(String name, int threads, int queue) {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), runnable -> {
                    Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Runs a cheap request (navigation, outline, status) on the light lane. */
    public <T> CompletableFuture<T> light(Function<CancelChecker, T> work) {
        return submit(light, "light", work);
    }

    /** Runs a request that may compile, lint or type-check on the heavy lane. */
    public <T> CompletableFuture<T> heavy(Function<CancelChecker, T> work) {
        return submit(heavy, "heavy", work);
    }

    private static <T> CompletableFuture<T> submit(ThreadPoolExecutor executor, String lane,
            Function<CancelChecker, T> work) {
        CompletableFuture<T> inner = CompletableFutures.computeAsync(executor,
                cancel -> Cancellation.run(cancel::isCanceled, () -> work.apply(cancel)));
        // The pool's rejection lands on the future (LSP4J hands the work to the executor through a stage), so
        // it is translated here; the returned future forwards a cancel to the inner one, which is what the
        // request's CancelChecker watches.
        CompletableFuture<T> outer = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                inner.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        inner.whenComplete((value, error) -> {
            if (error == null) {
                outer.complete(value);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            outer.completeExceptionally(cause instanceof RejectedExecutionException ? busy(executor, lane) : cause);
        });
        return outer;
    }

    static ResponseErrorException busy(ThreadPoolExecutor executor, String lane) {
        String message = "groovyscope engine is busy: " + executor.getActiveCount() + " " + lane
                + " request(s) running and " + executor.getQueue().size() + " waiting; retry in a moment";
        return new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed, message, null));
    }

    /** Requests running or waiting on the heavy lane, for {@code groovyscope/status}. */
    public int heavyLoad() {
        return heavy.getActiveCount() + heavy.getQueue().size();
    }

    public int heavyThreads() {
        return heavy.getMaximumPoolSize();
    }

    public void shutdown() {
        heavy.shutdownNow();
        light.shutdownNow();
    }
}
