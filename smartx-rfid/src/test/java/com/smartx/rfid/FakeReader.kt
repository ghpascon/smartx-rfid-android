package com.smartx.rfid

import android.content.Context
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Leitor falso para testes sem hardware. */
class FakeReader(
    override val readerId: String = "FAKE",
    override val displayName: String = "Fake Reader",
    override val isBle: Boolean = false,
    /** Quantas chamadas a connect() devem falhar antes de conectar. */
    var failuresBeforeSuccess: Int = 0
) : IRfidReader {

    private val _state = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _state

    private val _tags = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    override val tagFlow: Flow<RfidTag> = _tags.asSharedFlow()

    override var targetMacAddress: String? = null

    var connectCalls = 0
    var appliedConfigs = mutableListOf<ReaderConfig>()
    private var inventorying = false
    var config = ReaderConfig(txPower = 20)

    fun emit(tag: RfidTag) = _tags.tryEmit(tag)

    override suspend fun connect(context: Context): Boolean {
        connectCalls++
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            _state.value = ReaderConnectionState.ERROR
            return false
        }
        _state.value = ReaderConnectionState.CONNECTED
        return true
    }

    override suspend fun disconnect() {
        inventorying = false
        _state.value = ReaderConnectionState.DISCONNECTED
    }

    override fun startInventory(): Boolean {
        inventorying = true
        return true
    }

    override fun stopInventory(): Boolean {
        inventorying = false
        return true
    }

    override fun isInventorying() = inventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean {
        appliedConfigs += config
        this.config = config
        return true
    }

    override suspend fun readConfig() = config

    override fun onTriggerPressed(): Boolean = startInventory()
    override fun onTriggerReleased(): Boolean = stopInventory()
}
