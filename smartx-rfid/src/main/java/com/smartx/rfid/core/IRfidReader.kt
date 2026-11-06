package com.smartx.rfid.core

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrato unificado que todo leitor RFID deve implementar.
 *
 * Para adicionar suporte a um novo leitor:
 *  1. Crie uma classe em `devices/<modelo>/` implementando esta interface.
 *  2. Registre-a em `ReaderRegistry` (builtIns) ou, em tempo de execução,
 *     com `ReaderRegistry.register(id) { MeuLeitor() }`.
 *
 * Os apps NUNCA devem conhecer classes concretas: tudo o que a UI precisa
 * (opções de conexão, endereço BLE, logs, capacidades) está exposto aqui.
 */
interface IRfidReader {

    /** Identificador único e estável do modelo (ex.: "C72", "IH25"). Usado para reconexão automática. */
    val readerId: String

    /** Nome legível para exibição (ex.: "Chainway C72"). */
    val displayName: String

    /** true se o leitor se conecta via Bluetooth LE (requer seleção de dispositivo). */
    val isBle: Boolean get() = false

    /** Recursos/comportamentos específicos do modelo. */
    val capabilities: ReaderCapabilities get() = ReaderCapabilities()

    /**
     * Formas de conexão suportadas. Quando há mais de uma, o app deve perguntar ao usuário.
     * Padrão: BLE → [ConnectionOption.BLUETOOTH]; caso contrário → [ConnectionOption.INTEGRATED].
     */
    val connectionOptions: List<ConnectionOption>
        get() = if (isBle) listOf(ConnectionOption.BLUETOOTH) else listOf(ConnectionOption.INTEGRATED)

    /** Endereço (MAC) do dispositivo BLE alvo. Ignorado por leitores não-BLE. */
    var targetMacAddress: String?
        get() = null
        set(_) {}

    /** Recebe mensagens de diagnóstico da conexão (opcional; usado para exibir logs). */
    var logSink: ((String) -> Unit)?
        get() = null
        set(_) {}

    /**
     * Estado de inventário quando ele pode mudar sem comando do app (ex.: gatilho BLE).
     * `null` quando o leitor não notifica mudanças externas.
     */
    val inventoryStateFlow: StateFlow<Boolean>? get() = null

    /** Estado atual da conexão como StateFlow observável. */
    val connectionState: StateFlow<ReaderConnectionState>

    /** Stream reativo de tags lidas durante o inventário. */
    val tagFlow: Flow<RfidTag>

    /**
     * Aplica a escolha de conexão (opção + dispositivo) antes de [connect].
     * A implementação padrão repassa o endereço aos leitores BLE.
     */
    fun prepareConnection(request: ConnectionRequest) {
        if (isBle) targetMacAddress = request.address
    }

    /**
     * Inicializa e conecta ao leitor.
     * @return true se a conexão foi bem-sucedida
     */
    suspend fun connect(context: Context): Boolean

    /** Desconecta e libera recursos. */
    suspend fun disconnect()

    /** Inicia inventário contínuo; as tags chegam via [tagFlow]. */
    fun startInventory(): Boolean

    /** Para o inventário. */
    fun stopInventory(): Boolean

    /** Retorna true se o inventário está em execução. */
    fun isInventorying(): Boolean

    /** Aplica um bloco de configurações ao leitor. */
    suspend fun applyConfig(config: ReaderConfig): Boolean

    /** Lê a configuração atual do leitor. */
    suspend fun readConfig(): ReaderConfig

    /** Gatilho físico pressionado. Retorna true se o evento foi tratado. */
    fun onTriggerPressed(): Boolean

    /** Gatilho físico liberado. Retorna true se o evento foi tratado. */
    fun onTriggerReleased(): Boolean
}
