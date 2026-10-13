package com.example.llamanode.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerStateTest {

    @Before
    fun setUp() {
        RpcServerManager.clearLogs()
        RpcServerManager.clearError()
        RpcServerManager.setState(ServerState.STOPPED)
    }

    @Test
    fun testPortValidation() {
        assertTrue(RpcServerManager.isValidPort(1024))
        assertTrue(RpcServerManager.isValidPort(8080))
        assertTrue(RpcServerManager.isValidPort(50052))
        assertTrue(RpcServerManager.isValidPort(65535))

        assertFalse(RpcServerManager.isValidPort(0))
        assertFalse(RpcServerManager.isValidPort(80))
        assertFalse(RpcServerManager.isValidPort(443))
        assertFalse(RpcServerManager.isValidPort(1023))
        assertFalse(RpcServerManager.isValidPort(65536))
        assertFalse(RpcServerManager.isValidPort(-50052))
    }

    @Test
    fun testErrorLifecycle() {
        assertEquals(ServerState.STOPPED, RpcServerManager.serverState.value)
        assertNull(RpcServerManager.errorMessage.value)

        RpcServerManager.setError("Failed to bind socket")
        assertEquals(ServerState.ERROR, RpcServerManager.serverState.value)
        assertEquals("Failed to bind socket", RpcServerManager.errorMessage.value)

        RpcServerManager.clearError()
        assertEquals(ServerState.STOPPED, RpcServerManager.serverState.value)
        assertNull(RpcServerManager.errorMessage.value)
    }

    @Test
    fun testStateTransitions() {
        RpcServerManager.setState(ServerState.STARTING)
        assertEquals(ServerState.STARTING, RpcServerManager.serverState.value)

        RpcServerManager.setState(ServerState.RUNNING)
        assertEquals(ServerState.RUNNING, RpcServerManager.serverState.value)

        RpcServerManager.setState(ServerState.STOPPING)
        assertEquals(ServerState.STOPPING, RpcServerManager.serverState.value)

        RpcServerManager.setState(ServerState.STOPPED)
        assertEquals(ServerState.STOPPED, RpcServerManager.serverState.value)
    }

    @Test
    fun testLogQueueCapping() {
        for (i in 1..600) {
            RpcServerManager.addLog("Log entry $i")
        }
        val logs = RpcServerManager.logs.value
        assertEquals(500, logs.size)
        assertEquals("Log entry 101", logs.first())
        assertEquals("Log entry 600", logs.last())
    }
}
