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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.NotificationEmitter;

/**
 * Watches the tenured heap pool and declares memory pressure once the memory still in use after a collection
 * passes a share of the maximum. Under pressure the process sheds what it can rebuild (derived caches) and
 * refuses the requests that would walk the whole workspace, while cheap requests keep working. The JVM itself
 * is never asked to collect and {@code OutOfMemoryError} is never caught: with {@code -XX:+ExitOnOutOfMemoryError}
 * the process exits and the front end handles the rest. Pressure clears when a later collection leaves the pool
 * under the threshold again.
 */
public final class MemoryGuard {

    private static final long MB = 1024 * 1024;
    private static final AtomicReference<MemoryGuard> INSTALLED = new AtomicReference<>();

    private final MemoryPoolMXBean pool;
    private final long thresholdBytes;
    private final int percent;
    private final Runnable onPressure;
    private volatile boolean pressure;

    private MemoryGuard(MemoryPoolMXBean pool, int percent, Runnable onPressure) {
        this.pool = pool;
        this.percent = percent;
        this.onPressure = onPressure;
        long max = pool.getUsage().getMax();
        this.thresholdBytes = max > 0 ? max / 100 * percent : Long.MAX_VALUE;
    }

    /**
     * Installs the process-wide guard at {@code percent} of the tenured pool, once; a later call returns the
     * installed one. {@code onPressure} runs when pressure is first declared (typically: drop derived caches).
     * Returns empty when the JVM exposes no suitable pool (the guard then never reports pressure).
     */
    public static Optional<MemoryGuard> install(int percent, Runnable onPressure) {
        MemoryGuard existing = INSTALLED.get();
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<MemoryPoolMXBean> tenured = tenuredPool();
        if (tenured.isEmpty()) {
            return Optional.empty();
        }
        MemoryGuard guard = new MemoryGuard(tenured.get(), Math.max(1, Math.min(99, percent)), onPressure);
        if (!INSTALLED.compareAndSet(null, guard)) {
            return Optional.of(INSTALLED.get());
        }
        guard.arm();
        return Optional.of(guard);
    }

    /** The installed guard, if any. */
    public static Optional<MemoryGuard> installed() {
        return Optional.ofNullable(INSTALLED.get());
    }

    /** True when the installed guard reports pressure; false with no guard. */
    public static boolean pressure() {
        MemoryGuard guard = INSTALLED.get();
        return guard != null && guard.underPressure();
    }

    /** Refuses a whole-workspace step (a compile, a type check) while the installed guard reports pressure. */
    public static void requireHeadroom() {
        MemoryGuard guard = INSTALLED.get();
        if (guard != null && guard.underPressure()) {
            throw new MemoryPressureException(guard.advice());
        }
    }

    static Optional<MemoryPoolMXBean> tenuredPool() {
        MemoryPoolMXBean best = null;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) {
                continue;
            }
            long max = pool.getUsage().getMax();
            if (max > 0 && (best == null || max > best.getUsage().getMax())) {
                best = pool;
            }
        }
        return Optional.ofNullable(best);
    }

    private void arm() {
        if (thresholdBytes != Long.MAX_VALUE) {
            pool.setCollectionUsageThreshold(thresholdBytes);
        }
        if (ManagementFactory.getMemoryMXBean() instanceof NotificationEmitter emitter) {
            emitter.addNotificationListener((notification, handback) -> {
                if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType())) {
                    trip();
                }
            }, null, null);
        }
    }

    private void trip() {
        if (!pressure) {
            pressure = true;
            org.groovyscope.engine.log.EngineLog.warn("%s", advice());
            try {
                onPressure.run();
            } catch (RuntimeException e) {
                org.groovyscope.engine.log.EngineLog.warn("%s", "shedding caches failed: " + e);
            }
        }
    }

    /** Re-reads the pool: declares pressure the notification may have missed, clears it once a collection freed memory. */
    public void refresh() {
        MemoryUsage usage = pool.getCollectionUsage();
        if (usage == null) {
            return;
        }
        if (usage.getUsed() >= thresholdBytes) {
            trip();
        } else if (pressure) {
            pressure = false;
            org.groovyscope.engine.log.EngineLog.info("heap pressure cleared: %d MB of %d MB in use after the last collection",
                    usage.getUsed() / MB, pool.getUsage().getMax() / MB);
        }
    }

    public boolean underPressure() {
        return pressure;
    }

    public int percent() {
        return percent;
    }

    /** What a refused request is told. */
    public String advice() {
        MemoryUsage usage = pool.getCollectionUsage();
        long used = usage == null ? 0 : usage.getUsed() / MB;
        return "groovyscope engine is low on heap: " + used + " MB of " + pool.getUsage().getMax() / MB
                + " MB still in use after the last collection (over " + percent + "%); whole-workspace requests are "
                + "refused until memory frees; raise daemon.heap in .groovyscope.yaml, then `groovyscope daemon restart`";
    }

    /** For specs: forget the installed guard. */
    public static void reset() {
        INSTALLED.set(null);
    }

    /** For specs: make {@code guard} the installed one. */
    public static void installForTest(MemoryGuard guard) {
        INSTALLED.set(guard);
    }

    /** For specs: a guard on the given pool with a threshold already crossed or not. */
    public static MemoryGuard of(MemoryPoolMXBean pool, int percent, Runnable onPressure) {
        return new MemoryGuard(pool, percent, onPressure);
    }

    /** For specs: declare pressure as the JVM's notification would. */
    public void tripForTest() {
        trip();
    }

    /** True when the JVM was started with {@code -XX:+ExitOnOutOfMemoryError}, so an out-of-memory error ends it. */
    public static boolean exitsOnOutOfMemory() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("-XX:+ExitOnOutOfMemoryError");
    }
}
