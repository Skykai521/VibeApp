package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapStateStoreTest {

    @Test
    fun `default state is NotInstalled`() = runTest {
        val store = InMemoryBootstrapStateStore()
        assertEquals(BootstrapState.NotInstalled, store.state.first())
    }

    @Test
    fun `update then read returns the same state`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val target = BootstrapState.Downloading(componentId = "jdk", bytesRead = 100, totalBytes = 1000)
        store.update(target)
        assertEquals(target, store.state.first())
    }

    @Test
    fun `state sealed hierarchy roundtrips through JSON`() {
        val cases = listOf(
            BootstrapState.NotInstalled,
            BootstrapState.Downloading("jdk", 100, 1000),
            BootstrapState.Verifying("jdk"),
            BootstrapState.Unpacking("jdk"),
            BootstrapState.Installing("jdk"),
            BootstrapState.Ready("v2.0.0"),
            BootstrapState.Failed("net: timeout"),
            BootstrapState.Corrupted("java -version returned nonzero"),
        )
        for (state in cases) {
            val json = BootstrapStateJson.encode(state)
            val decoded = BootstrapStateJson.decode(json)
            assertEquals(state, decoded)
        }
    }

    @Test
    fun `corrupt JSON decodes to NotInstalled`() {
        val decoded = BootstrapStateJson.decode("not actually json")
        assertEquals(BootstrapState.NotInstalled, decoded)
    }

    @Test
    fun `empty or blank JSON decodes to NotInstalled`() {
        assertEquals(BootstrapState.NotInstalled, BootstrapStateJson.decode(""))
        assertEquals(BootstrapState.NotInstalled, BootstrapStateJson.decode("   "))
    }

    @Test
    fun `downloading state percent calculation`() {
        val s = BootstrapState.Downloading("jdk", 250, 1000)
        assertTrue(s.percent in 24..26)  // loose to avoid rounding-pedantry
    }
}
