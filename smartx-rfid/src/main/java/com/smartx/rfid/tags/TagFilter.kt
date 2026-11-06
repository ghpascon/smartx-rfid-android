package com.smartx.rfid.tags

import com.smartx.rfid.core.RfidTag

/**
 * Filtro aplicado às tags antes de entrarem na coleção.
 *
 * @property prefixes lista de prefixos de EPC aceitos (vazia = aceita tudo); case-insensitive
 * @property minRssi RSSI mínimo em dBm; tags com RSSI numérico menor são descartadas
 */
data class TagFilter(
    val prefixes: List<String> = emptyList(),
    val minRssi: Int = -120
) {
    fun accepts(tag: RfidTag): Boolean {
        if (prefixes.isNotEmpty() && prefixes.none { tag.epc.startsWith(it, ignoreCase = true) }) {
            return false
        }
        val rssi = tag.rssi.toDoubleOrNull()
        if (rssi != null && rssi < minRssi) return false
        return true
    }
}
