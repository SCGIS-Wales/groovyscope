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
        def publisher = new DiagnosticsPublisher({ services }, { client })
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
}
