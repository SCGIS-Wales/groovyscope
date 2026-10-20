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

/**
 * The rounds of a compile that drops broken files and tries again: at most {@code maxRetries} retries after the
 * first round, and no new round once the {@link RetryBudget} measured on the first round is spent.
 */
public final class RetryRounds {

    private final int maxRetries;
    private final long started = System.nanoTime();
    private long deadline = Long.MAX_VALUE;

    public RetryRounds(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /** Whether round {@code round} (0 is the first) may run at all. */
    public boolean allows(int round) {
        return round <= maxRetries;
    }

    /** Whether round {@code round} is the last one allowed. */
    public boolean isLast(int round) {
        return round == maxRetries;
    }

    /** Whether the retry budget is spent (never before the first round has finished). */
    public boolean overBudget() {
        return System.nanoTime() > deadline;
    }

    /** Records that round {@code round} finished; the first one sets the budget. */
    public void finished(int round) {
        if (round == 0) {
            deadline = started + RetryBudget.of(System.nanoTime() - started);
        }
    }
}
