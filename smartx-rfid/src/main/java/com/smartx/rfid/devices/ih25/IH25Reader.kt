package com.smartx.rfid.devices.ih25

import android.content.Context
import android.util.Log
import com.honeywell.rfidservice.EventListener
import com.honeywell.rfidservice.RfidManager
import com.honeywell.rfidservice.TriggerMode
import com.honeywell.rfidservice.rfid.AntennaPower
import com.honeywell.rfidservice.rfid.Gen2
import com.honeywell.rfidservice.rfid.OnTagReadListener
import com.honeywell.rfidservice.rfid.RfidReader
import com.honeywell.rfidservice.rfid.TagAdditionData
import com.honeywell.rfidservice.rfid.TagReadData
import com.honeywell.rfidservice.rfid.TagReadOption
import com.smartx.rfid.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adaptador para o leitor Honeywell IH25.
 * SDK: com.honeywell.rfidservice (RfidManager + RfidReader)
 *
 * Fluxo:
 *  1. connect() → manager.connect(null) → onDeviceConnected → manager.createReader()
 *  2. onReaderCreated(success, reader) → reader pronto, setOnTagReadListener configurado
 *  3. onRfidTriggered(pressed) → dispara startInventory / stopInventory automático
 *  4. disconnect() → reader.release() + manager.disconnect()
 */
class IH25Reader : IRfidReader {

    override val readerId: String = "IH25"
    override val displayName: String = "Honeywell IH25"
    override val isBle: Boolean = true

    private val TAG = "IH25Reader"

    /** Endereço MAC BLE do dispositivo selecionado pelo usuário */
    override var targetMacAddress: String? = null

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private var rfidManager: RfidManager? = null
    private var rfidReader: RfidReader? = null
    private var _isInventorying = false
    // Timestamp para instrumentação de conexão (ms)
    private var connectStartMs: Long = 0L

    /** Completa quando onReaderCreated dispara; permite suspender connect() até o leitor estar pronto. */
    private var connectionDeferred: CompletableDeferred<Boolean>? = null

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    // Scope para operações de leitura síncronas de fallback (ex: readTagData)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tagReadListener = OnTagReadListener { data: Array<TagReadData> ->
        data.forEach { tagData ->
            val tidBytes = tagData.getAdditionData()
            val tid = if (tidBytes != null && tidBytes.isNotEmpty()) {
                tidBytes.joinToString("") { "%02X".format(it) }
            } else ""

            val epc = tagData.getEpcHexStr() ?: ""
            val rssiStr = tagData.getRssi().toString()

            // Only emit tags that include a TID. If TID is missing, attempt a fallback
            // synchronous read and emit only if TID is obtained.
            if (tid.isNotEmpty()) {
                _tagChannel.tryEmit(RfidTag(epc = epc, rssi = rssiStr, tid = tid))
            } else if (epc.isNotBlank()) {
                val readerRef = rfidReader
                if (readerRef != null) {
                    ioScope.launch {
                        try {
                            // bank: 2 = TID (Gen2 banks: 0=RESERVED,1=EPC,2=TID,3=USER)
                            // Lê até 6 blocos (cada bloco = 2 words) — ajuste se necessário
                            val tidHex = try {
                                // use a short timeout to avoid long blocking calls
                                withTimeoutOrNull(1500L) {
                                    readerRef.readTagData(epc, 2, 0, 6, "")
                                }
                            } catch (e: Exception) {
                                null
                            }

                            val tidClean = tidHex?.replace("\\s".toRegex(), "")?.uppercase()
                            if (!tidClean.isNullOrBlank()) {
                                _tagChannel.tryEmit(RfidTag(epc = epc, rssi = rssiStr, tid = tidClean))
                            } else {
                                Log.d(TAG, "IH25 no TID found for $epc (fallback)")
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "IH25 fallback readTagData failed for $epc", e)
                        }
                    }
                }
            }
        }
    }

    private val eventListener = object : EventListener {
        override fun onDeviceConnected(device: Any) {
            val now = System.currentTimeMillis()
            Log.i(TAG, "IH25 dispositivo conectado t=%d".format(now))
            if (connectStartMs != 0L) Log.d(TAG, "IH25 delta desde connect=%d ms".format(now - connectStartMs))
            _connectionState.value = ReaderConnectionState.CONNECTING
            // Dispositivo conectado — solicita criação do leitor RFID
            rfidManager?.createReader()
        }

        override fun onDeviceDisconnected(device: Any) {
            Log.i(TAG, "IH25 dispositivo desconectado")
            rfidReader = null
            _isInventorying = false
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        }

        override fun onReaderCreated(success: Boolean, reader: RfidReader) {
            if (success) {
                val now = System.currentTimeMillis()
                Log.i(TAG, "IH25 reader criado com sucesso t=%d".format(now))
                if (connectStartMs != 0L) Log.d(TAG, "IH25 readerCreated delta since connect=%d ms".format(now - connectStartMs))
                rfidReader = reader
                reader.setOnTagReadListener(tagReadListener)
                rfidManager?.setTriggerMode(TriggerMode.RFID)
                _connectionState.value = ReaderConnectionState.CONNECTED
            } else {
                Log.e(TAG, "IH25 falha ao criar reader")
                _connectionState.value = ReaderConnectionState.ERROR
            }
            // Desbloqueia connect() que está aguardando
            connectionDeferred?.complete(success)
            connectionDeferred = null
        }

        override fun onRfidTriggered(pressed: Boolean) {
            if (pressed) onTriggerPressed() else onTriggerReleased()
        }

        override fun onTriggerModeSwitched(mode: TriggerMode) {
            Log.d(TAG, "Modo de gatilho alterado: $mode")
        }
    }

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        return@withContext try {
            val deferred = CompletableDeferred<Boolean>()
            connectionDeferred = deferred

            // Instrumentação: marca o início da tentativa de conexão
            connectStartMs = System.currentTimeMillis()
            Log.i(TAG, "IH25.connect start t=%d".format(connectStartMs))

            val manager = RfidManager.getInstance(context)
            manager.addEventListener(eventListener)
            val bleStarted = manager.connect(targetMacAddress)
            rfidManager = manager

            val afterConnectCallMs = System.currentTimeMillis()
            Log.d(TAG, "IH25.manager.connect returned=%s t=%d delta=%d".format(bleStarted, afterConnectCallMs, afterConnectCallMs - connectStartMs))

            if (!bleStarted) {
                // BLE nem conseguiu iniciar — falha imediata
                connectionDeferred = null
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            // Aguarda onReaderCreated (timeout 20s para pareamento BLE + criação do reader)
            val ready = withTimeoutOrNull(20_000L) { deferred.await() } ?: false
            if (!ready) {
                Log.e(TAG, "IH25 timeout aguardando reader")
                _connectionState.value = ReaderConnectionState.ERROR
            }
            // Limpa timestamp de conexão após término
            val endMs = System.currentTimeMillis()
            Log.i(TAG, "IH25.connect end ready=%s t=%d total=%d".format(ready, endMs, endMs - connectStartMs))
            connectStartMs = 0L
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao conectar IH25", e)
            connectionDeferred = null
            _connectionState.value = ReaderConnectionState.ERROR
            connectStartMs = 0L
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (_isInventorying) stopInventory()
            // Cancel any pending fallback reads
            try { ioScope.coroutineContext[Job]?.cancel() } catch (_: Exception) {}
            rfidReader?.release()
            rfidManager?.removeEventListener(eventListener)
            rfidManager?.disconnect()
            rfidManager = null
            rfidReader = null
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar IH25", e)
        }
        Unit
    }

    override fun startInventory(): Boolean {
        val reader = rfidReader ?: return false
        val option = TagReadOption().apply {
            setData(true)
            setRssi(true)
        }
        val ok = reader.read(TagAdditionData.TID_BANK, option)
        if (ok) _isInventorying = true
        return ok
    }

    override fun stopInventory(): Boolean {
        val reader = rfidReader ?: return false
        val ok = reader.stopRead()
        if (ok) _isInventorying = false
        return ok
    }

    override fun isInventorying() = _isInventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        val reader = rfidReader ?: return@withContext false
        var success = true
        try {
            // SDK IH25 usa centidBm (cdBm): 30 dBm → 3000 cdBm
            val cdBm = config.txPower.coerceIn(5, 30) * 100
            val ap = AntennaPower(1, cdBm, cdBm)
            val t0 = System.currentTimeMillis()
            Log.d(TAG, "applyConfig: setAntennaPower start t=%d power=%d".format(t0, config.txPower))
            reader.setAntennaPower(arrayOf(ap))
            val t1 = System.currentTimeMillis()
            Log.d(TAG, "applyConfig: setAntennaPower end t=%d delta=%d".format(t1, t1 - t0))

            val session = when (config.session) {
                0 -> Gen2.Session.Session0
                1 -> Gen2.Session.Session1
                2 -> Gen2.Session.Session2
                else -> Gen2.Session.Session3
            }
            Log.d(TAG, "applyConfig: setSession start t=%d session=%d".format(System.currentTimeMillis(), config.session))
            reader.setSession(session)
            Log.d(TAG, "applyConfig: setSession end t=%d".format(System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar configuração IH25", e)
            success = false
        }
        success
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        val reader = rfidReader ?: return@withContext ReaderConfig()
        return@withContext try {
            val powers = reader.getAntennaPower()
            // SDK retorna cdBm; converte para dBm dividindo por 100
            val cdBm = powers?.firstOrNull()?.readPower ?: 3000
            val dBm = (cdBm / 100).coerceIn(5, 30)
            val sessionEnum = reader.getSession()
            val sessionInt = when (sessionEnum) {
                Gen2.Session.Session0 -> 0
                Gen2.Session.Session1 -> 1
                Gen2.Session.Session2 -> 2
                Gen2.Session.Session3 -> 3
                else -> 1
            }
            ReaderConfig(txPower = dBm, session = sessionInt)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler configuração IH25", e)
            ReaderConfig()
        }
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
