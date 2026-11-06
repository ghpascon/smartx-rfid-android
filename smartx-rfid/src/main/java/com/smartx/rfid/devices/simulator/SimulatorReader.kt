package com.smartx.rfid.devices.simulator

import android.content.Context
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Leitor virtual para testar fluxos de conexão, configuração e inventário sem hardware. */
class SimulatorReader : IRfidReader {

    override val readerId: String = "SIMULATOR"
    override val displayName: String = "RFID Simulator"

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState

    private val _tags = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    override val tagFlow: Flow<RfidTag> = _tags.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inventoryJob: Job? = null
    private var nextTagNumber = 1L
    private var config = ReaderConfig()

    override suspend fun connect(context: Context): Boolean {
        _connectionState.value = ReaderConnectionState.CONNECTING
        _connectionState.value = ReaderConnectionState.CONNECTED
        logSink?.invoke("Simulator conectado")
        return true
    }

    override suspend fun disconnect() {
        stopInventory()
        _connectionState.value = ReaderConnectionState.DISCONNECTED
        logSink?.invoke("Simulator desconectado")
    }

    @Synchronized
    override fun startInventory(): Boolean {
        if (_connectionState.value != ReaderConnectionState.CONNECTED) return false
        if (inventoryJob?.isActive == true) return true

        logSink?.invoke("Inventário do Simulator iniciado")
        inventoryJob = scope.launch {
            while (isActive) {
                val number = nextTagNumber++
                val suffix = number.toString(16).uppercase(Locale.ROOT)
                _tags.emit(
                    RfidTag(
                        epc = "00${suffix.padStart(22, '0')}",
                        tid = "e28000${suffix.padStart(18, '0')}",
                        rssi = "-45",
                        antenna = 1
                    )
                )
                delay(TAG_INTERVAL_MS)
            }
        }
        return true
    }

    @Synchronized
    override fun stopInventory(): Boolean {
        inventoryJob?.cancel()
        inventoryJob = null
        logSink?.invoke("Inventário do Simulator parado")
        return true
    }

    @Synchronized
    override fun isInventorying(): Boolean = inventoryJob?.isActive == true

    override suspend fun applyConfig(config: ReaderConfig): Boolean {
        this.config = config
        logSink?.invoke("Configuração do Simulator atualizada")
        return true
    }

    override suspend fun readConfig(): ReaderConfig = config

    override fun onTriggerPressed(): Boolean = startInventory()

    override fun onTriggerReleased(): Boolean = stopInventory()

    companion object {
        private const val TAG_INTERVAL_MS = 1_000L
    }
}