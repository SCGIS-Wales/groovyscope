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

import java.util.concurrent.TimeUnit;

/**
 * How long a compile may keep retrying after its first round: a few times the first round, never less than a
 * floor, so a broken base class costs a bounded number of whole-workspace rounds on any size of workspace.
 */
public final class RetryBudget {

    /** Retries may take this many times the first round. */
    public static final long FACTOR = 3;
    /** The floor, so tiny workspaces still get their retries. */
    public static final long MINIMUM_NANOS = TimeUnit.SECONDS.toNanos(5);

    private RetryBudget() {
    }

    /** The budget, in nanoseconds, after a first round that took {@code firstRoundNanos}. */
    public static long of(long firstRoundNanos) {
        return Math.max(MINIMUM_NANOS, FACTOR * Math.max(0, firstRoundNanos));
    }
}
