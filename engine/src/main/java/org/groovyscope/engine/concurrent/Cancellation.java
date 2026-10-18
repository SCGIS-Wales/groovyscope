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
package org.groovyscope.engine.concurrent;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Cooperative cancellation for the thread serving one request. The protocol layer binds the request's cancel
 * flag with {@link #run}; long loops in the compiler, the type checker, the linter and the call graph call
 * {@link #checkpoint()} at their safe points and stop with a {@link CancellationException} once the flag is set.
 * Cancellation is only ever observed at a checkpoint: no thread is interrupted or stopped, so every structure
 * stays consistent. A thread with no binding (background work, tests) is never cancelled.
 */
public final class Cancellation {

    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    private Cancellation() {
    }

    /** Runs {@code work} with {@code cancelled} as this thread's cancel flag, restoring the previous one after. */
    public static <T> T run(BooleanSupplier cancelled, Supplier<T> work) {
        BooleanSupplier previous = CURRENT.get();
        CURRENT.set(cancelled);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** True when the request this thread serves has been cancelled. */
    public static boolean requested() {
        BooleanSupplier current = CURRENT.get();
        return current != null && current.getAsBoolean();
    }

    /** Stops the current request with a {@link CancellationException} when it has been cancelled. */
    public static void checkpoint() {
        if (requested()) {
            throw new CancellationException("request cancelled");
        }
    }

    /** This thread's cancel flag, to hand to another thread that works on its behalf; never null. */
    public static BooleanSupplier current() {
        BooleanSupplier current = CURRENT.get();
        return current == null ? () -> false : current;
    }
}
