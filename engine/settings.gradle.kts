import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

rootProject.name = "groovyscope-engine"

// ---------------------------------------------------------------------------------------------------------
// CodeNarc is pinned in gradle/libs.versions.toml, so two builds of one commit embed the same release;
// Dependabot proposes newer ones. The lookup of the latest stable release on Maven Central is opt in: the
// weekly `codenarc canary` workflow builds and tests with it, so a new release is tried before the bump PR.
// The version used is expanded into the engine (engine-version.properties, the JAR manifest) so
// `groovyscope version`, `doctor` and `status` report it.
//
//   -PcodenarcResolve=true                                        use the latest stable release (the canary)
//   -PcodenarcVersion=x.y.z (or GROOVYSCOPE_CODENARC_VERSION)     use that version for this build
//   -PcodenarcMetadataUrl=https://mirror/.../maven-metadata.xml   read the metadata from a mirror
//
// A ValueSource is re-evaluated on every build, so a cached configuration is invalidated when a new release
// appears (the configuration cache is on in gradle.properties).
// ---------------------------------------------------------------------------------------------------------

abstract class LatestStableCodenarc : ValueSource<String, LatestStableCodenarc.Parameters> {

    interface Parameters : ValueSourceParameters {
        val floor: Property<String>
        val pin: Property<String>
        val resolve: Property<Boolean>
        val metadataUrl: Property<String>
    }

    override fun obtain(): String {
        val floor = parameters.floor.get()
        val pin = parameters.pin.orNull?.trim().orEmpty()
        if (pin.isNotEmpty()) {
            return "$pin\tpinned by codenarcVersion"
        }
        if (!parameters.resolve.get()) {
            return "$floor\tpinned in libs.versions.toml"
        }
        val url = parameters.metadataUrl.get()
        return try {
            val versions = stableVersions(fetch(url))
            val latest = versions.maxWithOrNull(::compareVersions)
            when {
                latest == null -> "$floor\tfloor from libs.versions.toml (no stable release listed at $url)"
                compareVersions(latest, floor) < 0 ->
                    "$floor\tfloor from libs.versions.toml (latest stable at $url is $latest)"
                else -> "$latest\tlatest stable release at $url (floor $floor)"
            }
        } catch (e: Exception) {
            "$floor\tfloor from libs.versions.toml ($url unreachable: ${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun fetch(url: String): String {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        return response.body()
    }

    companion object {
        /** A stable release is plain numeric: 4.0.0 or 4.1; `-groovy-4.0`, `-rc1` and `-SNAPSHOT` are not. */
        private val STABLE = Regex("""\d+(\.\d+){1,2}""")
        private val VERSION_TAG = Regex("""<version>\s*([^<\s]+)\s*</version>""")

        fun stableVersions(metadataXml: String): List<String> =
            VERSION_TAG.findAll(metadataXml).map { it.groupValues[1] }.filter { STABLE.matches(it) }.toList()

        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toInt() }
            val right = b.split('.').map { it.toInt() }
            for (i in 0 until maxOf(left.size, right.size)) {
                val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}

val catalogue = providers.fileContents(layout.settingsDirectory.file("gradle/libs.versions.toml")).asText
val codenarcFloor = catalogue.map { text ->
    Regex("""(?m)^codenarc\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        ?: error("gradle/libs.versions.toml has no codenarc version (the floor)")
}
val resolveWanted = providers.gradleProperty("codenarcResolve").map { it == "true" }.orElse(false)
val offline = gradle.startParameter.isOffline

val codenarcResolution = providers.of(LatestStableCodenarc::class) {
    parameters.floor.set(codenarcFloor)
    parameters.pin.set(
        providers.gradleProperty("codenarcVersion").orElse(providers.environmentVariable("GROOVYSCOPE_CODENARC_VERSION"))
    )
    parameters.resolve.set(resolveWanted.map { it && !offline })
    parameters.metadataUrl.set(
        providers.gradleProperty("codenarcMetadataUrl")
            .orElse("https://repo1.maven.org/maven2/org/codenarc/CodeNarc/maven-metadata.xml")
    )
}.get()
val (codenarcVersion, codenarcReason) = codenarcResolution.split('\t', limit = 2)
logger.lifecycle("CodeNarc $codenarcVersion: $codenarcReason")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            version("codenarc", codenarcVersion)
        }
    }
}
