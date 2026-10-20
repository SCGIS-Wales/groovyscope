import com.cloudbees.groovy.cps.NonCPS

def call(Map config) {
    withCredentials([usernamePassword(credentialsId: 'deploy', usernameVariable: 'DEPLOY_USER',
            passwordVariable: 'DEPLOY_PASSWORD')]) {
        sh "curl -u ${DEPLOY_USER}:${DEPLOY_PASSWORD} https://example.com/upload"
        sh 'curl -u "$DEPLOY_USER:$DEPLOY_PASSWORD" https://example.com/upload'
    }
    withCredentials([string(credentialsId: 'token', variable: 'API_TOKEN')]) {
        sh(script: "echo token ${env.API_TOKEN}", returnStdout: true)
    }
    sh "echo releasing ${config.version}"
    bat "echo ${env.SIGNING_SECRET}"
    powershell "Write-Output ${env.BUILD_NUMBER}"
}

def tagOf(String text) {
    def matcher = text =~ /v(\d+)/
    sh 'git fetch --tags'
    return matcher[0][1]
}

def tagBefore(String text) {
    def matcher = text =~ /v(\d+)/
    String tag = matcher[0][1]
    matcher = null
    sh 'git fetch --tags'
    return tag
}

def announce(List<String> names) {
    Iterator<String> remaining = names.iterator()
    while (remaining.hasNext()) {
        echo remaining.next()
    }
}

@NonCPS
def parseVersions(String text) {
    echo 'parsing'
    return text.readLines().collect { it.trim() }
}

@NonCPS
def sortedNames(List<String> names) {
    return names.sort(false)
}
