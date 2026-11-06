package com.smartx.rfid.core

/**
 * Forma de conexão oferecida por um leitor.
 *
 * @property id identificador estável (ex.: "bluetooth")
 * @property label texto para exibir ao usuário
 * @property requiresDevice true se o usuário precisa escolher um dispositivo (ex.: scan BLE)
 */
data class ConnectionOption(
    val id: String,
    val label: String,
    val requiresDevice: Boolean = false
) {
    companion object {
        /** Leitor embutido no próprio coletor (sem escolha de dispositivo). */
        val INTEGRATED = ConnectionOption("integrated", "Integrado")

        /** Leitor externo via Bluetooth (exige escolher um dispositivo). */
        val BLUETOOTH = ConnectionOption("bluetooth", "Bluetooth", requiresDevice = true)

        /** Leitor via porta serial/USB do coletor. */
        val SERIAL = ConnectionOption("serial", "Serial")
    }
}

/**
 * Escolha feita pelo usuário para conectar.
 *
 * @property option opção escolhida (null = padrão do leitor)
 * @property address endereço do dispositivo (MAC BLE), quando a opção exige
 */
data class ConnectionRequest(
    val option: ConnectionOption? = null,
    val address: String? = null
)
