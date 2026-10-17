plugins {
    java
    groovy
    jacoco
    alias(libs.plugins.shadow)
    alias(libs.plugins.cyclonedx)
}

description = "groovyscope engine: Groovy code intelligence served over LSP"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(libs.groovy)
    implementation(libs.lsp4j)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.spock.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<GroovyCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("**/engine-version.properties") {
        expand("version" to projectVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("groovy.grape.enable", "false")
    systemProperty("groovy.asttest.enable", "false")
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "org.groovyscope.engine.Main")
    }
}

tasks.shadowJar {
    archiveBaseName.set("groovyscope-engine")
    archiveClassifier.set("all")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes("Main-Class" to "org.groovyscope.engine.Main")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The CycloneDX plugin writes build/reports/bom.json and bom.xml with its defaults.
