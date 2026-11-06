package com.smartx.rfid.devices.c72

import android.content.Context
import android.util.Log
import com.rscja.deviceapi.RFIDWithUHFA8
import com.rscja.deviceapi.entity.UHFTAGInfo
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback
import com.smartx.rfid.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Adaptador para o leitor Chainway C72.
 * SDK: com.rscja.deviceapi (RFIDWithUHFA8 — usado no C72 como módulo embutido UART)
 *
 * Fluxo: connect() → applyConfig() → startInventory() → (tags via tagFlow) → stopInventory() → disconnect()
 *
 * O gatilho físico do C72 emite KeyEvent.KEYCODE_F1 (ou KEYCODE_CAMERA).
 * A Activity captura esse evento e delega para onTriggerPressed/Released.
 */
class C72Reader : IRfidReader {

    override val readerId: String = "C72"
    override val displayName: String = "Chainway C72"
    override val capabilities = ReaderCapabilities(usesKeyEventTrigger = true)

    private val TAG = "C72Reader"

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private var rfid: RFIDWithUHFA8? = null
    private var _isInventorying = false

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        return@withContext try {
            val instance = RFIDWithUHFA8.getInstance()
            val ok = instance.init(context)
            if (ok) {
                rfid = instance
                _connectionState.value = ReaderConnectionState.CONNECTED
                Log.i(TAG, "Conectado ao C72")
                true
            } else {
                _connectionState.value = ReaderConnectionState.ERROR
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao conectar C72", e)
            _connectionState.value = ReaderConnectionState.ERROR
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (_isInventorying) stopInventory()
            rfid?.free()
            rfid = null
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar C72", e)
        }
        Unit
    }

    override fun startInventory(): Boolean {
        val r = rfid ?: return false
        r.setInventoryCallback(object : IUHFInventoryCallback {
            override fun callback(info: UHFTAGInfo) {
                val tag = RfidTag(
                    epc = info.getEPC() ?: "",
                    rssi = info.getRssi() ?: "",
                    tid = info.getTid() ?: ""
                )
                _tagChannel.tryEmit(tag)
            }
        })
        val ok = r.startInventoryTag()
        if (ok) _isInventorying = true
        return ok
    }

    override fun stopInventory(): Boolean {
        val r = rfid ?: return false
        val ok = r.stopInventory()
        if (ok) _isInventorying = false
        return ok
    }

    override fun isInventorying() = _isInventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        val r = rfid ?: return@withContext false

        // O C72 não aceita alterações de config enquanto o inventário está rodando.
        // Para, aplica e reinicia se necessário.
        val wasInventorying = _isInventorying
        if (wasInventorying) {
            r.stopInventory()
            _isInventorying = false
        }

        var success = true

        // Potência: setPower(dBm) aceita valor direto em dBm
        success = success && r.setPower(config.txPower.coerceIn(0, 33))

        // Região: só altera se explicitamente configurado (>= 0)
        if (config.region >= 0) {
            success = success && r.setFrequencyMode(config.region)
        }

        // Modo de inventário
        success = success && when (config.inventoryMode) {
            ReaderConfig.InventoryMode.EPC_ONLY -> r.setEPCMode()
            ReaderConfig.InventoryMode.EPC_TID -> r.setEPCAndTIDMode()
            ReaderConfig.InventoryMode.EPC_TID_USER -> r.setEPCAndTIDUserMode(0, 4)
        }

        // Session Gen2
        val gen2 = r.getGen2()
        if (gen2 != null) {
            gen2.setQuerySession(config.session)
            success = success && r.setGen2(gen2)
        }

        // Reinicia inventário se estava rodando antes
        if (wasInventorying) {
            val restarted = r.startInventoryTag()
            if (restarted) _isInventorying = true
        }

        success
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        val r = rfid ?: return@withContext ReaderConfig()
        val gen2 = r.getGen2()
        val power = try { r.getPower() } catch (e: Exception) { 30 }
        ReaderConfig(
            txPower = power,
            session = gen2?.getQuerySession() ?: 1,
            region = r.getFrequencyMode().takeIf { it >= 0 } ?: 0x02
        )
    }

    override fun onTriggerPressed(): Boolean {
        if (_connectionState.value != ReaderConnectionState.CONNECTED) return false
        if (!_isInventorying) startInventory()
        return true
    }

    override fun onTriggerReleased(): Boolean {
        if (_isInventorying) stopInventory()
        return true
    }
}
