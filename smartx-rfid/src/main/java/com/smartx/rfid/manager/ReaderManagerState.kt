package com.smartx.rfid.manager

import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState

/**
 * Estado observável do [ReaderManager].
 *
 * @property activeReader leitor conectado (null enquanto desconectado)
 * @property connectionState estado de conexão do leitor ativo
 * @property isConnecting true durante o processo de conexão (inclui tentativas)
 * @property isInventorying true enquanto o inventário estiver rodando
 * @property config última configuração lida/aplicada no leitor ativo
 * @property lastError mensagem do último erro de conexão (limpe com `clearError()`)
 */
data class ReaderManagerState(
    val activeReader: IRfidReader? = null,
    val connectionState: ReaderConnectionState = ReaderConnectionState.DISCONNECTED,
    val isConnecting: Boolean = false,
    val isInventorying: Boolean = false,
    val config: ReaderConfig = ReaderConfig(),
    val lastError: String? = null
) {
    val isConnected: Boolean get() = connectionState == ReaderConnectionState.CONNECTED
}
