package com.smartx.rfid.tags

import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagCollectorTest {

    private fun tag(epc: String, rssi: String = "-50") = RfidTag(epc = epc, rssi = rssi)

    @Test
    fun `deduplicates by epc and counts reads`() = runTest {
        val collector = TagCollector(backgroundScope)
        collector.add(tag("E200", "-60"))
        collector.add(tag("E200", "-40"))
        collector.add(tag("E201"))

        val snapshot = collector.snapshot()
        assertEquals(2, snapshot.size)
        val first = snapshot.first { it.epc == "E200" }
        assertEquals(2, first.readCount)
        assertEquals("-40", first.rssi)
        assertEquals(2, collector.count)
    }

    @Test
    fun `filter rejects by prefix and rssi`() = runTest {
        val collector = TagCollector(backgroundScope)
        collector.filter = TagFilter(prefixes = listOf("e2"), minRssi = -70)

        assertTrue(collector.add(tag("E200", "-60")))
        assertFalse(collector.add(tag("3000", "-60")))   // prefixo diferente
        assertFalse(collector.add(tag("E201", "-80")))   // RSSI baixo
        assertTrue(collector.add(tag("E202", "")))        // RSSI não numérico passa
        assertEquals(2, collector.count)
    }

    @Test
    fun `tags state is throttled and published when dirty`() = runTest {
        val collector = TagCollector(backgroundScope, refreshIntervalMs = 200)
        collector.attach(flowOf())          // inicia o ticker
        runCurrent()

        collector.add(tag("E200"))
        assertTrue(collector.tags.value.isEmpty())   // ainda não publicou

        advanceTimeBy(250)
        assertEquals(1, collector.tags.value.size)
    }

    @Test
    fun `clear empties everything`() = runTest {
        val collector = TagCollector(backgroundScope, refreshIntervalMs = 100)
        collector.attach(flowOf())
        collector.add(tag("E200"))
        advanceTimeBy(150)
        assertEquals(1, collector.tags.value.size)

        collector.clear()
        assertEquals(0, collector.count)
        assertTrue(collector.tags.value.isEmpty())
    }

    @Test
    fun `attach consumes source flow`() = runTest {
        val collector = TagCollector(backgroundScope)
        collector.attach(flowOf(tag("A"), tag("B"), tag("A")))
        runCurrent()
        assertEquals(2, collector.count)
    }
}
