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

import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

class DiagnosticsPublisherSpec extends Specification {

    def 'only documents whose diagnostics changed are published again'() {
        given:
        def services = Fixtures.services('practices-sample')
        def client = new RecordingLanguageClient()
        def publisher = new DiagnosticsPublisher({ services }, { client }, { services.workspace().sources().keySet() as Set })
        String clean = Fixtures.uri('practices-sample', 'src/main/groovy/sample/Clean.groovy')

        when: 'the first publish sends every document'
        publisher.publishNow()
        int first = client.received.size()

        and: 'nothing changed'
        publisher.publishNow()

        then:
        first == services.workspace().sources().size()
        client.received.size() == first

        when: 'one document changes'
        services.workspace().open(clean, 'package sample\n\nclass Clean {\n    int half(int a, int b) { int r = a / b; return r }\n}\n')
        services.markDirty()
        publisher.publishNow()

        then: 'only that document is sent'
        client.received.size() == first + 1
        (client.received.last() as PublishDiagnosticsParams).uri == clean
        (client.received.last() as PublishDiagnosticsParams).diagnostics.collect { it.code.getLeft() }.contains('GS001')

        cleanup:
        services.workspace().close(clean)
        services.markDirty()
        publisher.shutdown()
    }

    def 'the open scope publishes the open documents only and clears a document once it is closed'() {
        given:
        def services = Fixtures.services('practices-sample')
        def client = new RecordingLanguageClient()
        Set<String> open = [] as Set
        def publisher = new DiagnosticsPublisher({ services }, { client }, { open })
        String clean = Fixtures.uri('practices-sample', 'src/main/groovy/sample/Clean.groovy')

        when: 'nothing is open'
        publisher.publishNow()

        then: 'nothing is pushed (the default scope is open)'
        services.workspace().config().diagnostics().push() == 'open'
        client.received.empty

        when: 'one document is open'
        open << clean
        publisher.publishNow()

        then: 'only that document is pushed'
        client.received*.uri == [clean]

        when: 'it is closed again'
        open.clear()
        publisher.publishNow()

        then: 'its diagnostics are cleared, nothing else is sent'
        client.received.size() == 2
        (client.received.last() as PublishDiagnosticsParams).uri == clean
        (client.received.last() as PublishDiagnosticsParams).diagnostics.empty

        cleanup:
        publisher.shutdown()
    }

    def 'the none scope never pushes and the workspace scope pushes every document'() {
        given:
        def base = Fixtures.services('practices-sample')
        def client = new RecordingLanguageClient()
        def none = org.groovyscope.engine.config.EngineConfig.fromJson('{"diagnostics": {"push": "none"}}')
        def all = org.groovyscope.engine.config.EngineConfig.fromJson('{"diagnostics": {"push": "workspace", "debounceMillis": 7}}')
        def services = new org.groovyscope.engine.services.LanguageServices(
                new org.groovyscope.engine.workspace.WorkspaceModel(base.workspace().root(), none),
                new org.groovyscope.engine.compile.GroovyCompiler())
        def publisher = new DiagnosticsPublisher({ services }, { client }, { [] as Set })

        when:
        publisher.publishNow()

        then:
        client.received.empty

        when:
        services.reconfigure(all)
        publisher.publishNow()

        then:
        client.received.size() == services.workspace().sources().size()
        services.workspace().config().diagnostics().debounceMillis() == 7

        cleanup:
        publisher.shutdown()
    }

    def 'shutdown during a publish never interrupts the writing thread and stops before the next notification'() {
        given:
        def services = Fixtures.services('practices-sample')
        def entered = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        def interrupted = new java.util.concurrent.atomic.AtomicBoolean(false)
        def sent = new java.util.concurrent.atomic.AtomicInteger()
        def client = new RecordingLanguageClient() {
            @Override
            void publishDiagnostics(PublishDiagnosticsParams params) {
                if (sent.incrementAndGet() == 1) {
                    entered.countDown()
                    try {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        interrupted.set(Thread.currentThread().isInterrupted())
                    } catch (InterruptedException e) {
                        interrupted.set(true)
                    }
                }
            }
        }
        def publisher = new DiagnosticsPublisher({ services }, { client }, { services.workspace().sources().keySet() as Set })

        when: 'a publish is writing its first notification when shutdown arrives'
        publisher.schedule(0)
        assert entered.await(60, java.util.concurrent.TimeUnit.SECONDS)
        publisher.shutdown()
        release.countDown()
        Thread.sleep(300)

        then:
        !interrupted.get()
        sent.get() == 1
        publisher.isShutdown()

        when: 'more work is scheduled after shutdown'
        publisher.schedule(0)
        Thread.sleep(100)

        then:
        sent.get() == 1
    }
}
