package com.smartx.rfid.devices.xr2

import android.content.Context
import android.device.DeviceManager
import android.os.SystemClock
import android.util.Log
import com.smartx.rfid.core.*
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.rfid.RfidManager
import com.ubx.usdk.rfid.aidl.IRfidCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Adaptador para o leitor XR2 (Ubx / URFIDLibrary).
 * SDK: com.ubx.usdk (RFIDSDKManager / RfidManager)
 *
 * Conexão: ligar módulo via power(true), aguardar ~1500ms, chamar connect() em thread IO.
 * Escala de potência: SDK usa dBm diretamente em setOutputPower(byte).
 * Modo de inventário: setQueryMode(int) — 0=EPC, 1=EPC+TID, 2=EPC+TID+User.
 * Callback de tags: registerCallback(IRfidCallback) → onInventoryTag(epc, tid, rssi).
 */
class XR2Reader : IRfidReader {

    override val readerId: String = "XR2"
    override val displayName: String = "XR2"
    override val isBle: Boolean = false
    override val capabilities = ReaderCapabilities(usesKeyEventTrigger = true)

    private val TAG = "XR2Reader"

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 128)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    @Volatile private var rfidManager: RfidManager? = null
    @Volatile private var _isInventorying = false

    // Potência em dBm — cacheada pois o SDK não expõe getPower() de forma confiável
    private var cachedPower: Int = 30
    private var cachedQueryMode: Int = 1 // 1 = EPC+TID

    private val tagCallback = object : IRfidCallback {
        override fun onInventoryTag(epc: String?, tid: String?, rssi: String?) {
            if (epc.isNullOrBlank()) return
            // SDK entrega RSSI com offset 128 (ex: 73 → 73-128 = -55 dBm)
            val rssiDbm = rssi?.trim()?.toIntOrNull()?.let { "${it - 128}" } ?: (rssi?.trim() ?: "")
            _tagChannel.tryEmit(
                RfidTag(
                    epc = epc.trim(),
                    tid = tid?.trim() ?: "",
                    rssi = rssiDbm
                )
            )
        }

        override fun onInventoryTagEnd() {
            // Inventário contínuo — não altera _isInventorying aqui.
            // O SDK pode chamar este callback entre ciclos de leitura.
            Log.d(TAG, "onInventoryTagEnd()")
        }
    }

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        return@withContext try {
            // Remove keycode 523 (trigger) da lista de scan de código de barras
            // para que o gatilho nunca acione o leitor óptico.
            configureBarcodeScanKeys()

            RFIDSDKManager.getInstance().power(true)
            // SDK precisa de ~1500ms para o módulo estabilizar após ligar
            SystemClock.sleep(1500)

            val connected = RFIDSDKManager.getInstance().connect()
            Log.i(TAG, "RFIDSDKManager.connect() = $connected")

            if (connected) {
                rfidManager = RFIDSDKManager.getInstance().getRfidManager()
                rfidManager?.registerCallback(tagCallback)
                _connectionState.value = ReaderConnectionState.CONNECTED
                true
            } else {
                RFIDSDKManager.getInstance().power(false)
                _connectionState.value = ReaderConnectionState.ERROR
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar XR2", e)
            _connectionState.value = ReaderConnectionState.ERROR
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (_isInventorying) {
                rfidManager?.stopInventory()
                _isInventorying = false
            }
            rfidManager = null
            RFIDSDKManager.getInstance().disConnect()
            RFIDSDKManager.getInstance().power(false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar XR2", e)
        } finally {
            // Mantém o trigger fora das teclas de scanner também ao desconectar.
            configureBarcodeScanKeys()
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        }
        Unit
    }

    override fun startInventory(): Boolean {
        val r = rfidManager ?: return false
        if (_isInventorying) return true
        return try {
            r.startRead()
            _isInventorying = true
            Log.i(TAG, "Inventário iniciado")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar inventário", e)
            false
        }
    }

    override fun stopInventory(): Boolean {
        val r = rfidManager ?: run {
            _isInventorying = false
            return false
        }
        return try {
            r.stopInventory()
            _isInventorying = false
            Log.i(TAG, "Inventário parado")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar inventário", e)
            _isInventorying = false
            false
        }
    }

    override fun isInventorying(): Boolean = _isInventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        val r = rfidManager ?: return@withContext false
        return@withContext try {
            // Potência: SDK usa dBm direto (0–33)
            val sdkPower = config.txPower.coerceIn(0, 33)
            val ret = r.setOutputPower(sdkPower)
            if (ret == 0) {
                cachedPower = config.txPower
                Log.d(TAG, "setOutputPower($sdkPower) OK")
            } else {
                Log.w(TAG, "setOutputPower($sdkPower) ret=$ret")
            }

            // Modo de inventário: 0=EPC, 1=EPC+TID, 2=EPC+TID+User
            val queryMode = when (config.inventoryMode) {
                ReaderConfig.InventoryMode.EPC_ONLY     -> 0
                ReaderConfig.InventoryMode.EPC_TID      -> 1
                ReaderConfig.InventoryMode.EPC_TID_USER -> 2
            }
            r.setQueryMode(queryMode)
            cachedQueryMode = queryMode
            Log.d(TAG, "setQueryMode($queryMode)")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar configuração XR2", e)
            false
        }
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        // SDK não expõe getOutputPower() — retorna valores cacheados
        val mode = when (cachedQueryMode) {
            0    -> ReaderConfig.InventoryMode.EPC_ONLY
            2    -> ReaderConfig.InventoryMode.EPC_TID_USER
            else -> ReaderConfig.InventoryMode.EPC_TID
        }
        ReaderConfig(txPower = cachedPower, inventoryMode = mode)
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

    /**
     * Mantém o gatilho físico (523) fora da lista do scanner de código de barras.
     * Assim, o barcode fica disponível apenas nas teclas laterais (520/521/522).
     */
    private fun configureBarcodeScanKeys() {
        try {
            val dm = DeviceManager()
            dm.setSettingProperty("persist-persist.sys.rfid.key", "0-")
            val scanKeys = "520-521-522-"
            dm.setSettingProperty("persist-persist.sys.scan.key", scanKeys)
            Log.d(TAG, "configureBarcodeScanKeys(): scan.key=$scanKeys")
        } catch (e: Exception) {
            Log.w(TAG, "DeviceManager não disponível neste dispositivo: ${e.message}")
        }
    }
}
