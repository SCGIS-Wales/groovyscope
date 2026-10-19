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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.concurrent.Cancellation
import spock.lang.Specification
import spock.lang.Timeout

/** Two bounded lanes: a full heavy lane answers busy at once while the light lane keeps answering. */
@Timeout(60)
class RequestExecutorsSpec extends Specification {

    def 'a full heavy lane refuses with RequestFailed while light requests still run'() {
        given: 'one heavy thread and a queue of one'
        def executors = new RequestExecutors(1, 1)
        def hold = new CountDownLatch(1)
        def running = new CountDownLatch(1)

        when: 'one request occupies the thread, one waits in the queue, a third finds the queue full'
        def first = executors.heavy { cancel -> running.countDown(); hold.await(30, TimeUnit.SECONDS); 'first' }
        running.await(30, TimeUnit.SECONDS)
        def second = executors.heavy { cancel -> 'second' }
        def third = executors.heavy { cancel -> 'third' }
        def light = executors.light { cancel -> 'light' }

        then: 'the third is refused at once and names the load; the light lane is unaffected'
        light.get(30, TimeUnit.SECONDS) == 'light'
        third.isCompletedExceptionally()
        def failure = thrownBy { third.get() }
        failure instanceof ExecutionException
        failure.cause instanceof ResponseErrorException
        failure.cause.responseError.code == ResponseErrorCode.RequestFailed.value
        failure.cause.responseError.message.contains('busy')
        executors.heavyLoad() == 2

        when:
        hold.countDown()

        then:
        first.get(30, TimeUnit.SECONDS) == 'first'
        second.get(30, TimeUnit.SECONDS) == 'second'

        cleanup:
        executors?.shutdown()
    }

    def "a request's cancel flag is bound as the thread's cancellation"() {
        given:
        def executors = new RequestExecutors(1, 4)
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)

        when: 'the request is cancelled while it waits at a checkpoint'
        def future = executors.heavy { cancel ->
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            Cancellation.checkpoint()
            'done'
        }
        entered.await(30, TimeUnit.SECONDS)
        future.cancel(true)
        release.countDown()

        then: 'the checkpoint saw the cancel (the future itself is cancelled by LSP4J)'
        future.isCancelled()
        !Cancellation.requested()

        cleanup:
        executors?.shutdown()
    }

    def 'the defaults follow the machine and the configuration'() {
        expect:
        new org.groovyscope.engine.config.EngineConfig.Daemon(0, 64, 30).effectiveWorkerThreads() >= 2
        new org.groovyscope.engine.config.EngineConfig.Daemon(3, 0, 0).requestQueue() == 1
        new org.groovyscope.engine.config.EngineConfig.Daemon(3, 0, 0).workspaceIdleMinutes() == 1
        org.groovyscope.engine.config.EngineConfig.fromJson('{"daemon":{"workerThreads":2,"requestQueue":8}}').daemon().workerThreads() == 2
        org.groovyscope.engine.config.EngineConfig.fromJson('{"daemon":{"heapPressurePercent":70}}').daemon().heapPressurePercent() == 70
        org.groovyscope.engine.config.EngineConfig.fromJson('{}').daemon() == org.groovyscope.engine.config.EngineConfig.Daemon.DEFAULTS
    }

    private static Throwable thrownBy(Closure<?> action) {
        try {
            action.call()
            return null
        } catch (Throwable t) {
            return t
        }
    }

    def 'closing a connection cancels the requests it started, and only those (E-15)'() {
        given:
        def shared = new RequestExecutors(2, 4)
        def closing = shared.forConnection()
        def other = shared.forConnection()
        def running = new CountDownLatch(1)
        def stopped = new CountDownLatch(1)

        when: 'a long check runs for one connection; the other connection has a request too'
        def check = closing.heavy { cancel ->
            running.countDown()
            try {
                while (true) {
                    Cancellation.checkpoint()
                    Thread.onSpinWait()
                }
            } finally {
                stopped.countDown()
            }
        }
        running.await(30, TimeUnit.SECONDS)
        def hold = new CountDownLatch(1)
        def kept = other.heavy { cancel -> hold.await(30, TimeUnit.SECONDS); 'kept' }
        closing.cancelAll()

        then: 'the closed connection\'s work stops; the other one is untouched'
        stopped.await(30, TimeUnit.SECONDS)
        check.isCancelled()
        !kept.isDone()

        when:
        hold.countDown()

        then:
        kept.get(30, TimeUnit.SECONDS) == 'kept'
        closing.inFlight() == 0

        cleanup:
        shared.shutdown()
    }

    def 'a request still queued at shutdown is answered daemon is exiting, not dropped'() {
        given:
        def executors = new RequestExecutors(1, 4)
        def view = executors.forConnection()
        def running = new CountDownLatch(1)
        view.heavy { cancel -> running.countDown(); new CountDownLatch(1).await(30, TimeUnit.SECONDS) }
        running.await(30, TimeUnit.SECONDS)
        def queued = view.heavy { cancel -> 'never' }

        when:
        executors.shutdown()
        def failure = thrownBy { queued.get(30, TimeUnit.SECONDS) }

        then:
        failure.cause instanceof ResponseErrorException
        failure.cause.responseError.code == RequestExecutors.DAEMON_EXITING
    }

    def 'lanes that have shut down answer daemon is exiting, which a client takes as reconnect (E-14)'() {
        given:
        def executors = new RequestExecutors(1, 1)
        executors.shutdown()

        when:
        def refused = executors.light { cancel -> 'never' }
        def failure = thrownBy { refused.get(30, TimeUnit.SECONDS) }

        then:
        failure.cause instanceof ResponseErrorException
        failure.cause.responseError.code == RequestExecutors.DAEMON_EXITING
        failure.cause.responseError.message.startsWith('daemon is exiting')
    }
}
