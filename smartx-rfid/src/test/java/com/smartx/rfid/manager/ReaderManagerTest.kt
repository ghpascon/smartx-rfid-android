package com.smartx.rfid.manager

import android.content.Context
import com.smartx.rfid.FakeReader
import com.smartx.rfid.core.ConnectionOption
import com.smartx.rfid.core.ConnectionRequest
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderManagerTest {

    private val context: Context = Mockito.mock(Context::class.java)

    @Test
    fun `connect succeeds, publishes active reader and applies config`() = runTest {
        val reader = FakeReader()
        val manager = ReaderManager(context, listOf(reader), backgroundScope)

        assertTrue(manager.connect(reader, retryDelayMs = 0))

        val state = manager.state.value
        assertSame(reader, state.activeReader)
        assertTrue(state.isConnected)
        assertFalse(state.isConnecting)
        assertEquals(20, state.config.txPower)
        assertEquals(1, reader.appliedConfigs.size)
        assertTrue(manager.connectionLog.value.any { it.contains("Pronto") })
    }

    @Test
    fun `connect retries until success`() = runTest {
        val reader = FakeReader(failuresBeforeSuccess = 2)
        val manager = ReaderManager(context, listOf(reader), backgroundScope)

        assertTrue(manager.connect(reader, maxAttempts = 3, retryDelayMs = 0))
        assertEquals(3, reader.connectCalls)
    }

    @Test
    fun `connect fails after max attempts`() = runTest {
        val reader = FakeReader(failuresBeforeSuccess = 10)
        val manager = ReaderManager(context, listOf(reader), backgroundScope)

        assertFalse(manager.connect(reader, maxAttempts = 2, retryDelayMs = 0))
        assertEquals(2, reader.connectCalls)
        assertNull(manager.activeReader)
        assertNotNull(manager.state.value.lastError)
        assertFalse(manager.state.value.isConnecting)

        manager.clearError()
        assertNull(manager.state.value.lastError)
    }

    @Test
    fun `prepareConnection forwards BLE address`() = runTest {
        val reader = FakeReader(isBle = true)
        val manager = ReaderManager(context, listOf(reader), backgroundScope)

        manager.connect(reader, ConnectionRequest(ConnectionOption.BLUETOOTH, "AA:BB:CC:DD:EE:FF"), retryDelayMs = 0)
        assertEquals("AA:BB:CC:DD:EE:FF", reader.targetMacAddress)
    }

    @Test
    fun `tags from active reader are collected and cleared on disconnect`() = runTest {
        val reader = FakeReader()
        val manager = ReaderManager(context, listOf(reader), backgroundScope)
        manager.connect(reader, retryDelayMs = 0)
        runCurrent()

        reader.emit(RfidTag(epc = "E200"))
        reader.emit(RfidTag(epc = "E200"))
        runCurrent()
        assertEquals(1, manager.tags.count)
        assertEquals(2, manager.tags.snapshot().first().readCount)

        manager.disconnect()
        assertEquals(0, manager.tags.count)
        assertNull(manager.activeReader)
        assertEquals(ReaderConnectionState.DISCONNECTED, manager.state.value.connectionState)
    }

    @Test
    fun `inventory and trigger update state`() = runTest {
        val reader = FakeReader()
        val manager = ReaderManager(context, listOf(reader), backgroundScope)
        manager.connect(reader, retryDelayMs = 0)

        assertTrue(manager.toggleInventory())
        assertTrue(manager.state.value.isInventorying)
        assertFalse(manager.toggleInventory())
        assertFalse(manager.state.value.isInventorying)

        manager.onTriggerPressed()
        assertTrue(manager.state.value.isInventorying)
        manager.onTriggerReleased()
        assertFalse(manager.state.value.isInventorying)
    }

    @Test
    fun `applyConfig remembers only when asked`() = runTest {
        val reader = FakeReader()
        val manager = ReaderManager(context, listOf(reader), backgroundScope)
        manager.connect(reader, retryDelayMs = 0)

        manager.applyConfig(ReaderConfig(txPower = 10, session = 0), remember = false)
        assertEquals(20, manager.state.value.config.txPower)

        manager.applyConfig(ReaderConfig(txPower = 10))
        assertEquals(10, manager.state.value.config.txPower)
    }

    @Test
    fun `findById returns the registered instance`() = runTest {
        val a = FakeReader(readerId = "A")
        val b = FakeReader(readerId = "B")
        val manager = ReaderManager(context, listOf(a, b), backgroundScope)
        assertSame(b, manager.findById("B"))
        assertNull(manager.findById("Z"))
    }
}
