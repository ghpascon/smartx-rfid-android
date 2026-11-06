package com.smartx.rfid.core

/**
 * Modelo de configuração unificado para todos os leitores RFID.
 * Cada parâmetro tem um valor padrão; adapters mapeiam para a API específica do SDK.
 */
data class ReaderConfig(
    /** Potência de transmissão em dBm (ex.: 5..33) */
    val txPower: Int = 30,

    /** Session Gen2 (0 = S0, 1 = S1, 2 = S2, 3 = S3) */
    val session: Int = 1,

    /** Filtro RSSI mínimo em dBm (tags com RSSI abaixo são ignoradas) */
    val rssiFilter: Int = -120,

    /** Modo de leitura: EPC+TID por padrão */
    val inventoryMode: InventoryMode = InventoryMode.EPC_TID,

    /** Região de trabalho RF (usado pelo C72; -1 = usar padrão do hardware) */
    val region: Int = -1
) {
    enum class InventoryMode { EPC_ONLY, EPC_TID, EPC_TID_USER }
}
