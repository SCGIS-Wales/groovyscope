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
package org.groovyscope.engine.compile;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The class loader a compile's trees still need (classpath types are decompiled lazily), owned by the
 * {@link CompilationResult} and closed exactly once by whoever retires that result. Closing releases the jar
 * files the loader opened; the trees keep working for what was already decompiled.
 */
public final class LoaderHandle implements Closeable {

    private final Closeable loader;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LoaderHandle(Closeable loader) {
        this.loader = loader;
    }

    /** True once {@link #close()} has run. */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                loader.close();
            } catch (IOException e) {
                // nothing useful to do: the jars are released on GC at the latest
            }
        }
    }
}
