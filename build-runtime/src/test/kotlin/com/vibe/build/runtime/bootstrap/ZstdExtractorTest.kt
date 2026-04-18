package com.vibe.build.runtime.bootstrap

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ZstdExtractorTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun makeTarZst(entries: List<TarEntry>): ByteArray {
        val raw = ByteArrayOutputStream()
        ZstdOutputStream(raw).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (entry in entries) {
                    val tarEntry = TarArchiveEntry(entry.name)
                    tarEntry.size = entry.bytes.size.toLong()
                    tarEntry.mode = entry.mode
                    tar.putArchiveEntry(tarEntry)
                    tar.write(entry.bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return raw.toByteArray()
    }

    private data class TarEntry(val name: String, val bytes: ByteArray, val mode: Int = 0b110_100_100)

    @Test
    fun `extract writes files and creates parent directories`() {
        val bytes = makeTarZst(
            listOf(
                TarEntry("jdk/bin/java", "binary content".toByteArray(), mode = 0b111_101_101),
                TarEntry("jdk/README", "hello\n".toByteArray()),
            ),
        )
        val src = File(temp.root, "in.tar.zst").also { it.writeBytes(bytes) }
        val dst = File(temp.root, "out").also { it.mkdirs() }

        ZstdExtractor().extract(src, dst)

        assertTrue(File(dst, "jdk/bin/java").isFile)
        assertTrue(File(dst, "jdk/README").isFile)
        assertEquals("hello\n", File(dst, "jdk/README").readText())
    }

    @Test
    fun `extract preserves executable bit on owner`() {
        val bytes = makeTarZst(
            listOf(TarEntry("bin/sh", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101)),
        )
        val src = File(temp.root, "in.tar.zst").also { it.writeBytes(bytes) }
        val dst = File(temp.root, "out").also { it.mkdirs() }

        ZstdExtractor().extract(src, dst)

        val extracted = File(dst, "bin/sh")
        assertTrue(extracted.canExecute())
    }

    @Test
    fun `extract rejects tar path traversal attempt`() {
        val bytes = makeTarZst(
            listOf(TarEntry("../../etc/evil", "gotcha".toByteArray())),
        )
        val src = File(temp.root, "in.tar.zst").also { it.writeBytes(bytes) }
        val dst = File(temp.root, "out").also { it.mkdirs() }

        assertThrows(ExtractionFailedException::class.java) {
            ZstdExtractor().extract(src, dst)
        }
    }

    @Test
    fun `extract with corrupt zstd throws ExtractionFailedException`() {
        val src = File(temp.root, "corrupt.tar.zst").also {
            it.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
        val dst = File(temp.root, "out").also { it.mkdirs() }

        assertThrows(ExtractionFailedException::class.java) {
            ZstdExtractor().extract(src, dst)
        }
    }
}
