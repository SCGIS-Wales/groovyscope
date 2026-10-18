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
    implementation(libs.groovy.typecheckers)
    implementation(libs.lsp4j)
    implementation(libs.sqlite.jdbc)
    implementation(libs.codenarc) {
        // CodeNarc ships against Groovy 5; the engine runs one Groovy, the one it analyses with.
        exclude(group = "org.apache.groovy")
    }
    runtimeOnly("org.apache.groovy:groovy-xml:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-json:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-templates:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-groovydoc:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-ant:${libs.versions.groovy.get()}")
    // Groovy modules workspace code commonly uses without declaring them; visible through
    // WorkspaceParentClassLoader so their types resolve and the Groovy 6 checkers can see them.
    for (module in listOf("groovy-sql", "groovy-datetime", "groovy-nio", "groovy-dateutil", "groovy-test", "groovy-contracts")) {
        runtimeOnly("org.apache.groovy:$module:${libs.versions.groovy.get()}")
    }

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

// The CodeNarc version comes from settings.gradle.kts (latest stable release on Maven Central, or the floor).
val codenarcVersion = libs.versions.codenarc.get()

tasks.processResources {
    // Locals only: a task action must not capture the script object (configuration cache).
    val projectVersion = project.version.toString()
    val embeddedCodenarc = codenarcVersion
    inputs.property("version", projectVersion)
    inputs.property("codenarc", embeddedCodenarc)
    filesMatching("**/engine-version.properties") {
        expand("version" to projectVersion, "codenarc" to embeddedCodenarc)
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
        attributes("Main-Class" to "org.groovyscope.engine.Main", "CodeNarc-Version" to codenarcVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("groovyscope-engine")
    archiveClassifier.set("all")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes("Main-Class" to "org.groovyscope.engine.Main", "CodeNarc-Version" to codenarcVersion)
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The CycloneDX plugin (3.x) writes build/reports/cyclonedx/bom.json and bom.xml with its defaults.
