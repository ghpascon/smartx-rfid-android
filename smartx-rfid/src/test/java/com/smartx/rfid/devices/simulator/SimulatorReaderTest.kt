package com.smartx.rfid.devices.simulator

import android.content.Context
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.manager.ReaderRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class SimulatorReaderTest {

    @Test
    fun `registered simulator connects applies config and emits sequential tags`() = runBlocking {
        val reader = ReaderRegistry.create("SIMULATOR") as? SimulatorReader
        assertNotNull(reader)
        reader!!

        assertFalse(reader.startInventory())
        assertTrue(reader.connect(Mockito.mock(Context::class.java)))
        assertEquals(ReaderConnectionState.CONNECTED, reader.connectionState.value)

        val config = ReaderConfig(txPower = 18, session = 2)
        assertTrue(reader.applyConfig(config))
        assertEquals(config, reader.readConfig())

        val tags = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(3_000) { reader.tagFlow.take(2).toList() }
        }
        assertTrue(reader.startInventory())
        val emittedTags = tags.await()

        assertEquals("00${"1".padStart(22, '0')}", emittedTags[0].epc)
        assertEquals("e28000${"1".padStart(18, '0')}", emittedTags[0].tid)
        assertEquals("00${"2".padStart(22, '0')}", emittedTags[1].epc)
        assertEquals("e28000${"2".padStart(18, '0')}", emittedTags[1].tid)
        assertTrue(reader.isInventorying())

        assertTrue(reader.onTriggerReleased())
        assertFalse(reader.isInventorying())
        reader.disconnect()
        assertEquals(ReaderConnectionState.DISCONNECTED, reader.connectionState.value)
    }
}