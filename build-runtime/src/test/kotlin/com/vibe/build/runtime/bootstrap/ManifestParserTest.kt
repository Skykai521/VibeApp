package com.vibe.build.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestParserTest {

    private val parser = ManifestParser()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/manifest/$name")) {
            "fixture not found: /manifest/$name"
        }.readBytes()

    @Test
    fun `parse valid manifest returns expected components`() {
        val manifest = parser.parse(fixture("valid.json"))

        assertEquals(1, manifest.schemaVersion)
        assertEquals("v2.0.0", manifest.manifestVersion)
        assertEquals(2, manifest.components.size)

        val jdk = manifest.components.first { it.id == "jdk-17.0.13" }
        assertEquals(3, jdk.artifacts.size)
        assertNotNull(jdk.artifacts["arm64-v8a"])
        assertEquals(83000000L, jdk.artifacts.getValue("arm64-v8a").sizeBytes)

        val gradle = manifest.components.first { it.id == "gradle-9.3.1" }
        assertEquals(1, gradle.artifacts.size)
        assertNotNull(gradle.artifacts["common"])
    }

    @Test
    fun `parse malformed JSON throws ManifestException`() {
        val junk = "{ not valid json ".toByteArray()
        assertThrows(ManifestException::class.java) {
            parser.parse(junk)
        }
    }

    @Test
    fun `parse schemaVersion not 1 throws ManifestException`() {
        val malformed = """{"schemaVersion":2,"manifestVersion":"x","components":[]}""".toByteArray()
        assertThrows(ManifestException::class.java) {
            parser.parse(malformed)
        }
    }

    @Test
    fun `parse missing required field throws ManifestException`() {
        val noVersion = """{"schemaVersion":1,"components":[]}""".toByteArray()
        assertThrows(ManifestException::class.java) {
            parser.parse(noVersion)
        }
    }

    @Test
    fun `findArtifact returns correct arch for component`() {
        val manifest = parser.parse(fixture("valid.json"))
        val artifact = manifest.findArtifact(
            componentId = "jdk-17.0.13",
            abi = Abi.ARM64,
        )
        assertNotNull(artifact)
        assertEquals("jdk-17.0.13-arm64-v8a.tar.zst", artifact!!.fileName)
    }

    @Test
    fun `findArtifact returns common artifact for gradle component`() {
        val manifest = parser.parse(fixture("valid.json"))
        val artifact = manifest.findArtifact(
            componentId = "gradle-9.3.1",
            abi = Abi.ARM64,   // gradle has only "common", should still return it
        )
        assertNotNull(artifact)
        assertEquals("gradle-9.3.1-noarch.tar.zst", artifact!!.fileName)
    }

    @Test
    fun `findArtifact returns null for missing component`() {
        val manifest = parser.parse(fixture("valid.json"))
        val artifact = manifest.findArtifact("nonexistent", Abi.ARM64)
        assertEquals(null, artifact)
    }
}
