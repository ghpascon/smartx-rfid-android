package com.smartx.rfid.manager

import android.content.Context
import android.util.Log
import com.smartx.rfid.core.ConnectionRequest
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.tags.TagCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ponto único de acesso aos leitores RFID.
 *
 * Responsável por: lista de leitores disponíveis, conexão (com tentativas e log), inventário,
 * configuração, gatilho físico e coleta de tags ([tags]). O app conversa apenas com esta classe
 * e com [IRfidReader] — nunca com leitores concretos.
 *
 * ```
 * val manager = ReaderManager(appContext, ReaderRegistry.create(listOf("C72", "ZEBRA")))
 * manager.connect(reader, ConnectionRequest(ConnectionOption.BLUETOOTH, mac))
 * manager.startInventory()
 * manager.tags.tags.collect { ... }
 * ```
 *
 * Crie uma instância por processo (ex.: na `Application`) para que serviços e telas compartilhem
 * os mesmos leitores.
 */
class ReaderManager(
    context: Context,
    readers: List<IRfidReader> = ReaderRegistry.createAll(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val tags: TagCollector = TagCollector(scope)
) {

    private val appContext: Context = context.applicationContext ?: context

    /** Leitores disponíveis (mesmas instâncias durante toda a vida do manager). */
    val readers: List<IRfidReader> = readers.toList()

    private val _state = MutableStateFlow(ReaderManagerState())
    val state: StateFlow<ReaderManagerState> = _state.asStateFlow()

    /** Leitor atualmente conectado, se houver. */
    val activeReader: IRfidReader? get() = _state.value.activeReader

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())

    /** Linhas de log da última conexão (com tempo decorrido). */
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    @Volatile private var logStartMs = System.currentTimeMillis()
    private val observeJobs = mutableListOf<Job>()

    fun findById(readerId: String): IRfidReader? = readers.firstOrNull { it.readerId == readerId }

    // -------------------------------------------------------------------------
    // Conexão
    // -------------------------------------------------------------------------

    /**
     * Conecta ao [reader] tentando até [maxAttempts] vezes. Ao conectar: lê e reaplica a
     * configuração do leitor, publica-o como [activeReader] e passa a coletar suas tags.
     * Cancelar a coroutine cancela a conexão.
     *
     * @return true se conectou
     */
    suspend fun connect(
        reader: IRfidReader,
        request: ConnectionRequest = ConnectionRequest(),
        maxAttempts: Int = 3,
        retryDelayMs: Long = 1000L
    ): Boolean {
        if (_state.value.isConnecting) return false
        if (activeReader != null && activeReader !== reader) disconnect()

        logStartMs = System.currentTimeMillis()
        _connectionLog.value = emptyList()
        log("Iniciando conexão com ${reader.displayName}...")
        _state.update { it.copy(isConnecting = true, lastError = null) }

        reader.prepareConnection(request)
        reader.logSink = { msg -> log(msg) }

        var ok = false
        try {
            for (attempt in 1..maxAttempts) {
                log("Tentativa $attempt de $maxAttempts...")
                ok = try {
                    reader.connect(appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "connect attempt=$attempt reader=${reader.readerId}", e)
                    false
                }
                if (ok) break
                if (attempt < maxAttempts) {
                    log("Tentativa $attempt falhou — nova tentativa em ${retryDelayMs}ms...")
                    delay(retryDelayMs)
                }
            }

            if (ok) {
                _state.update {
                    it.copy(
                        activeReader = reader,
                        isConnecting = false,
                        connectionState = ReaderConnectionState.CONNECTED
                    )
                }
                observe(reader)
                log("Lendo configuração do leitor...")
                val config = reader.readConfig()
                reader.applyConfig(config)
                _state.update { it.copy(config = config) }
                log("✓ Pronto! (%.1fs)".format(elapsedSeconds()))
            } else {
                log("✗ Falha ao conectar. Verifique se o leitor está ligado e próximo.")
                _state.update {
                    it.copy(isConnecting = false, lastError = "Falha ao conectar ${reader.displayName}")
                }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(isConnecting = false) }
            log("Conexão cancelada.")
            throw e
        } finally {
            reader.logSink = null
        }
        return ok
    }

    /** Para o inventário, desconecta o leitor ativo e limpa as tags. */
    suspend fun disconnect() {
        val reader = activeReader ?: return
        runCatching {
            if (reader.isInventorying()) reader.stopInventory()
            reader.disconnect()
        }.onFailure { Log.w(TAG, "disconnect reader=${reader.readerId}", it) }
        stopObserving()
        tags.detach()
        tags.clear()
        _state.update {
            it.copy(
                activeReader = null,
                connectionState = ReaderConnectionState.DISCONNECTED,
                isInventorying = false
            )
        }
    }

    /** Adiciona uma linha ao log de conexão, prefixada com o tempo decorrido. */
    fun log(message: String) {
        val line = "[+%.2fs] %s".format(elapsedSeconds(), message)
        _connectionLog.update { it + line }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    // -------------------------------------------------------------------------
    // Inventário
    // -------------------------------------------------------------------------

    /** Inicia o inventário do leitor ativo. */
    fun startInventory(): Boolean {
        val reader = activeReader ?: return false
        val ok = reader.startInventory()
        _state.update { it.copy(isInventorying = ok) }
        return ok
    }

    fun stopInventory() {
        val reader = activeReader ?: return
        if (reader.isInventorying()) reader.stopInventory()
        _state.update { it.copy(isInventorying = false) }
    }

    /** Alterna o inventário. Retorna true se ficou ligado. */
    fun toggleInventory(): Boolean {
        val reader = activeReader ?: return false
        return if (reader.isInventorying()) {
            stopInventory()
            false
        } else {
            startInventory()
        }
    }

    /** Remove as tags coletadas. */
    fun clearTags() = tags.clear()

    /** Gatilho físico pressionado (repasse de KeyEvent ou callback). */
    fun onTriggerPressed(): Boolean {
        val reader = activeReader ?: return false
        val handled = reader.onTriggerPressed()
        _state.update { it.copy(isInventorying = reader.isInventorying()) }
        return handled
    }

    /** Gatilho físico liberado. */
    fun onTriggerReleased(): Boolean {
        val reader = activeReader ?: return false
        val handled = reader.onTriggerReleased()
        _state.update { it.copy(isInventorying = reader.isInventorying()) }
        return handled
    }

    // -------------------------------------------------------------------------
    // Configuração
    // -------------------------------------------------------------------------

    /** Lê a configuração do leitor ativo e a publica em [state]. */
    suspend fun readConfig(): ReaderConfig? {
        val reader = activeReader ?: return null
        val config = reader.readConfig()
        _state.update { it.copy(config = config) }
        return config
    }

    /**
     * Aplica [config] ao leitor ativo.
     * @param remember se true, guarda a configuração em [state] quando aplicada com sucesso
     *                 (use false para ajustes temporários, como o modo radar)
     */
    suspend fun applyConfig(config: ReaderConfig, remember: Boolean = true): Boolean {
        val reader = activeReader ?: return false
        val ok = reader.applyConfig(config)
        if (ok && remember) _state.update { it.copy(config = config) }
        return ok
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    /** Libera o manager (cancela coletores). Chame ao encerrar o processo, se necessário. */
    suspend fun release() {
        disconnect()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internos
    // -------------------------------------------------------------------------

    private fun elapsedSeconds() = (System.currentTimeMillis() - logStartMs) / 1000.0

    private fun observe(reader: IRfidReader) {
        stopObserving()
        observeJobs += scope.launch {
            reader.connectionState.collect { s ->
                _state.update {
                    it.copy(
                        connectionState = s,
                        isConnecting = false,
                        isInventorying = if (s == ReaderConnectionState.DISCONNECTED) false else it.isInventorying
                    )
                }
            }
        }
        // Inventário pode mudar sem comando do app (ex.: gatilho BLE do X714)
        reader.inventoryStateFlow?.let { flow ->
            observeJobs += scope.launch {
                flow.collect { on -> _state.update { it.copy(isInventorying = on) } }
            }
        }
        tags.attach(reader.tagFlow)
    }

    private fun stopObserving() {
        observeJobs.forEach { it.cancel() }
        observeJobs.clear()
    }

    private companion object {
        const val TAG = "ReaderManager"
    }
}
