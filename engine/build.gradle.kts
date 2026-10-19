import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Properties

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
    implementation(libs.elk.layered)
    implementation(libs.guava)
    runtimeOnly(libs.xbase.lib)
    implementation(libs.codenarc) {
        // CodeNarc ships against Groovy 5; the engine runs one Groovy, the one it analyses with.
        exclude(group = "org.apache.groovy")
    }
    runtimeOnly("org.apache.groovy:groovy-xml:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-json:${libs.versions.groovy.get()}")
    runtimeOnly("org.apache.groovy:groovy-groovydoc:${libs.versions.groovy.get()}")
    // Groovy modules workspace code commonly uses without declaring them; visible through
    // WorkspaceParentClassLoader so their types resolve and the Groovy 6 checkers can see them. The engine itself
    // needs none of them (its tests pass without templates and ant): a script using SimpleTemplateEngine or
    // AntBuilder would otherwise report unresolved classes when no classpath is configured.
    for (module in listOf("groovy-sql", "groovy-datetime", "groovy-nio", "groovy-dateutil", "groovy-test", "groovy-contracts",
            "groovy-templates", "groovy-ant")) {
        runtimeOnly("org.apache.groovy:$module:${libs.versions.groovy.get()}")
    }

    testImplementation(libs.spock.core)
    testRuntimeOnly(libs.junit.platform.launcher)
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
    toolVersion = libs.versions.jacoco.get()
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
    // Shadow 9 hands duplicate files to its transformers only when duplicates are included: without this the
    // service files and the Groovy extension module descriptors of every module but one were dropped, and with them
    // the nio, datetime, dateutil, sql and macro extension methods the type checker (and FormatStringChecker) need.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Shadow's own Groovy extension merge stamps the build time into the descriptor; this one writes no date, so two
    // builds of one commit give byte-identical JARs.
    transform(ReproducibleGroovyExtensionModules())
    // Every bundled Apache NOTICE is kept (Apache-2.0 section 4(d)), merged into one; the inputs are fixed, so the
    // merged file is the same in every build.
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer::class.java) {
        projectName.set("groovyscope")
        organizationName.set("SCGIS-Wales and contributors")
        organizationURL.set("https://github.com/SCGIS-Wales/groovyscope")
        inceptionYear.set("2026")
    }
    from(layout.projectDirectory.file("../THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    filesNotMatching(listOf("META-INF/services/**", "META-INF/groovy/**", "META-INF/NOTICE*")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    manifest {
        attributes("Main-Class" to "org.groovyscope.engine.Main", "CodeNarc-Version" to codenarcVersion)
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The CycloneDX plugin (3.x) writes build/reports/cyclonedx/bom.json and bom.xml with its defaults.

/**
 * Merges every module's Groovy extension descriptor (extension and static extension classes, in the order the jars
 * are read) into one descriptor, written without the date `Properties.store` would add.
 */
class ReproducibleGroovyExtensionModules : com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer {
    private val extensionClasses = linkedSetOf<String>()
    private val staticExtensionClasses = linkedSetOf<String>()

    override fun canTransformResource(element: FileTreeElement): Boolean =
        element.path == DESCRIPTOR || element.path == LEGACY_DESCRIPTOR

    override fun transform(context: com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext) {
        val properties = Properties()
        context.inputStream.use { properties.load(it) }
        extensionClasses += names(properties.getProperty("extensionClasses"))
        staticExtensionClasses += names(properties.getProperty("staticExtensionClasses"))
    }

    override fun hasTransformedResource(): Boolean = extensionClasses.isNotEmpty() || staticExtensionClasses.isNotEmpty()

    override fun modifyOutputStream(os: org.apache.tools.zip.ZipOutputStream, preserveFileTimestamps: Boolean) {
        val entry = org.apache.tools.zip.ZipEntry(DESCRIPTOR)
        entry.time = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).timeInMillis
        os.putNextEntry(entry)
        val text = buildString {
            append("moduleName=MergedByShadowJar\n")
            append("moduleVersion=1.0.0\n")
            append("extensionClasses=").append(extensionClasses.joinToString(",")).append('\n')
            append("staticExtensionClasses=").append(staticExtensionClasses.joinToString(",")).append('\n')
        }
        os.write(text.toByteArray(Charsets.ISO_8859_1))
        os.closeEntry()
    }

    private fun names(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DESCRIPTOR = "META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule"
        const val LEGACY_DESCRIPTOR = "META-INF/services/org.codehaus.groovy.runtime.ExtensionModule"
    }
}
