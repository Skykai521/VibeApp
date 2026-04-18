package com.vibe.build.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of :gradle-host's IpcProtocolTest. Asserts the same wire
 * bytes — if these diverge, the IPC breaks.
 */
class IpcProtocolTest {

    @Test
    fun `roundtrip Ping request`() {
        val request: HostRequest = HostRequest.Ping(requestId = "r1")
        val line = IpcProtocol.encodeRequest(request)
        assertEquals("""{"type":"Ping","requestId":"r1"}""", line)
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip RunBuild request with defaults`() {
        val request: HostRequest = HostRequest.RunBuild(
            requestId = "r2",
            projectPath = "/tmp/foo",
            tasks = listOf(":help"),
        )
        val line = IpcProtocol.encodeRequest(request)
        assertEquals(
            """{"type":"RunBuild","requestId":"r2","projectPath":"/tmp/foo","tasks":[":help"]}""",
            line,
        )
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip BuildFinish event with null failure`() {
        val event: HostEvent = HostEvent.BuildFinish(
            requestId = "r2",
            success = true,
            durationMs = 1234L,
            failureSummary = null,
        )
        val line = IpcProtocol.encodeEvent(event)
        assertEquals(
            """{"type":"BuildFinish","requestId":"r2","success":true,"durationMs":1234,"failureSummary":null}""",
            line,
        )
        assertEquals(event, IpcProtocol.decodeEvent(line))
    }

    @Test
    fun `all event subtypes roundtrip`() {
        val events = listOf<HostEvent>(
            HostEvent.Ready("r1", "1.0.0", "9.3.1"),
            HostEvent.Pong("r1"),
            HostEvent.BuildStart("r1", 1_700_000_000L),
            HostEvent.BuildProgress("r1", "> Task :help"),
            HostEvent.BuildFinish("r1", true, 1000L, null),
            HostEvent.BuildFinish("r1", false, 2000L, "compile error"),
            HostEvent.Log("r1", "LIFECYCLE", "hello"),
            HostEvent.Error("r1", "java.lang.RuntimeException", "boom"),
        )
        for (event in events) {
            val line = IpcProtocol.encodeEvent(event)
            assertEquals(event, IpcProtocol.decodeEvent(line))
        }
    }
}
