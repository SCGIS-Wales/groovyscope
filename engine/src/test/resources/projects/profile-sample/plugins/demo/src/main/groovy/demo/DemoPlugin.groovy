package demo

import com.example.sdk.PluginBase
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom

class DemoPlugin extends PluginBase {
    private static final HttpClient CLIENT = HttpClient.newHttpClient()
    private Map params = [:]
    private Map outputs = [:]

    void preProcessing() {
        String topic = getResourceParameter('TOPIC_NAME', String.class)
        String retention = getResourceParameter('RETENTION_DAYS', String.class) ?: '7'
        Boolean enabled = getResourceParameter('ENABLED', String.class)
        String missing = getResourceParameter('NOT_DECLARED', String.class)
        Object raw = params['TOPIC_NAME']
        Set<String> values = Set.of('a', 'b')
        Object parsed = new JsonSlurper().parseText('{}')
        String clientSecret = new SecureRandom().nextInt().toString()
        println "secret is ${clientSecret}"
        outputs.put('secret', clientSecret)
        CLIENT.send(null, HttpResponse.BodyHandlers.ofString())
        val count = 1
    }

    String httpWithRetry(HttpRequest request) {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    String advance(String state) {
        String next = switch (state) {
            case 'NONE' -> 'ACTIVE'
            default -> state
        };
        return next
    }

    void seam() {
    }

    static class Helper {
    }
}
