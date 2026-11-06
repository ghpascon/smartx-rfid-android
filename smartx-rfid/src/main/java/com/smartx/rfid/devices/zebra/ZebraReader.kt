package com.smartx.rfid.devices.zebra

import android.content.Context
import android.util.Log
import com.smartx.rfid.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
    
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.TagData
import com.zebra.rfid.api3.AntennaInfo
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.OperationFailureException
    

/**
 * Adaptador para leitores Zebra (API3). Suporta transporte Bluetooth/ZIOTC/etc.
 * Este adaptador segue a interface `IRfidReader` do app.
 */
class ZebraReader : IRfidReader {

    enum class TransportMode {
        BLUETOOTH,
        SERIAL
    }

    override val readerId: String = "ZEBRA"
    override val displayName: String = "Zebra RFID"
    override val isBle: Boolean = true

    /** Zebra exige escolha explícita do transporte (Bluetooth/Serial): sem auto-reconexão. */
    override val capabilities = ReaderCapabilities(supportsAutoReconnect = false)

    override val connectionOptions: List<ConnectionOption> =
        listOf(ConnectionOption.BLUETOOTH, ConnectionOption.SERIAL)

    override fun prepareConnection(request: ConnectionRequest) {
        if (request.option?.id == ConnectionOption.SERIAL.id) {
            transportMode = TransportMode.SERIAL
            targetMacAddress = null
        } else {
            transportMode = TransportMode.BLUETOOTH
            targetMacAddress = request.address
        }
    }

    private val TAG = "ZebraReader"

    // MAC / host ou identificador selecionado pelo usuário (BleScanDialog passa o address)
    override var targetMacAddress: String? = null
    var transportMode: TransportMode = TransportMode.BLUETOOTH

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 128)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    private var readers: Readers? = null
    private var availableDevices: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var rfidReader: RFIDReader? = null

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _isInventorying = false

    private val eventsListener = object : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents?) {
            try {
                // Use getReadTags (stable across SDK versions)
                val tags = try { rfidReader?.Actions?.getReadTags(1000) } catch (_: Throwable) { null } ?: return

                for (t in tags) {
                    try {
                        val epc = try { t.getTagID() } catch (ex: Exception) {
                            Log.e(TAG, "Tag.getTagID() threw", ex)
                            null
                        }
                        if (epc.isNullOrBlank()) {
                            val opCode = try { t.getOpCode().toString() } catch (_: Exception) { "?" }
                            val opStatus = try { t.getOpStatus().toString() } catch (_: Exception) { "?" }
                            val mem = try { t.getMemoryBankData() ?: "" } catch (_: Exception) { "" }
                            Log.w(TAG, "Tag missing TagID (possible translation error). opCode=$opCode opStatus=$opStatus memBankData=${mem.take(80)}")
                            continue
                        }

                        val rssiStr = try { t.getPeakRSSI().toString() } catch (ex: Exception) { Log.e(TAG, "getPeakRSSI() threw for $epc", ex); "" }
                        val rssiNum = rssiStr.toDoubleOrNull()
                        if (rssiNum != null && rssiNum < -75.0) {
                            Log.w(TAG, "Low RSSI for $epc: $rssiNum dBm — tag translation may fail at low power")
                        }

                        Log.d(TAG, "Tag read: epc=$epc rssi=$rssiStr")

                        val tagObj = RfidTag(epc = epc, rssi = rssiStr, tid = "")

                        // Try non-suspending emit first; if buffer full, launch coroutine to suspend and emit
                        val emitted = _tagChannel.tryEmit(tagObj)
                        if (!emitted) {
                            Log.w(TAG, "tagFlow buffer full, launching emitter for $epc")
                            ioScope.launch { try { _tagChannel.emit(tagObj) } catch (ex: Exception) { Log.e(TAG, "failed to emit tag via coroutine", ex) } }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "error processing TagData element", ex)
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "eventReadNotify error", ex)
            }
        }

        override fun eventStatusNotify(e: RfidStatusEvents?) {
            try {
                val t = e?.StatusEventData
                if (t != null && t.getStatusEventType() != null) {
                    // Trigger events: HANDHELD_TRIGGER_EVENT -> forward to app via onTriggerPressed
                    val st = t.getStatusEventType()
                    // HANDHELD_TRIGGER_EVENT handling
                    try {
                        val h = com.zebra.rfid.api3.STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT
                        if (st == h) {
                            val hh = t.HandheldTriggerEventData
                            if (hh != null) {
                                val pressed = hh.getHandheldEvent() == com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED
                                if (pressed) onTriggerPressed() else onTriggerReleased()
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (ex: Exception) {
                Log.e(TAG, "eventStatusNotify error", ex)
            }
        }
    }

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        try {
            val transport = if (transportMode == TransportMode.SERIAL) {
                ENUM_TRANSPORT.SERVICE_SERIAL
            } else {
                ENUM_TRANSPORT.BLUETOOTH
            }

            readers = Readers(context.applicationContext, transport)
            availableDevices = readers?.GetAvailableRFIDReaderList()
            if (availableDevices.isNullOrEmpty()) {
                Log.w(TAG, "Nenhum Zebra encontrado para transporte=$transport")
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            // Find by name/address if provided
            if (transportMode == TransportMode.BLUETOOTH && !targetMacAddress.isNullOrBlank() && !availableDevices.isNullOrEmpty()) {
                for (d in availableDevices!!) {
                    if (d.getName() != null && d.getName().contains(targetMacAddress!!, ignoreCase = true)) {
                        readerDevice = d
                        break
                    }
                }
            }

            // fallback: pick first available
            if (readerDevice == null && !availableDevices.isNullOrEmpty()) {
                readerDevice = availableDevices!![0]
            }

            if (readerDevice == null) {
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            rfidReader = readerDevice?.getRFIDReader()
            if (rfidReader == null) {
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            // connect
            if (!rfidReader!!.isConnected) {
                try {
                    rfidReader!!.connect()
                } catch (ie: InvalidUsageException) {
                    Log.e(TAG, "InvalidUsageException connect", ie)
                } catch (of: OperationFailureException) {
                    Log.e(TAG, "OperationFailureException connect", of)
                }
            }

            if (rfidReader!!.isConnected) {
                // configure events
                rfidReader!!.Events.addEventsListener(eventsListener)
                rfidReader!!.Events.setTagReadEvent(true)
                rfidReader!!.Events.setHandheldEvent(true)
                // Do not attach extra tag data to read events to reduce processing overhead (Zebra não envia TID aqui)
                try { rfidReader!!.Events.setAttachTagDataWithReadEvent(false) } catch (_: Exception) {}
                try { rfidReader!!.Config.setUniqueTagReport(false) } catch (_: Exception) {}
                try {
                    val pows = try { rfidReader!!.ReaderCapabilities.getTransmitPowerLevelValues() } catch (_: Exception) { null }
                    val antCount = try { rfidReader!!.ReaderCapabilities.getNumAntennaSupported() } catch (_: Exception) { -1 }
                    if (pows != null) Log.d(TAG, "Connected reader power values: ${pows.joinToString(", ")}")
                    Log.d(TAG, "Connected reader antennas: $antCount")
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to read reader capabilities on connect", ex)
                }
                _connectionState.value = ReaderConnectionState.CONNECTED
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Zebra connect error", e)
        }
        _connectionState.value = ReaderConnectionState.ERROR
        false
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                if (rfidReader != null) {
                    try { rfidReader!!.Events.removeEventsListener(eventsListener) } catch (_: Exception) {}
                    try { rfidReader!!.disconnect() } catch (_: Exception) {}
                }
                try { readers?.Dispose() } catch (_: Exception) {}
                rfidReader = null
                readerDevice = null
                readers = null
                _connectionState.value = ReaderConnectionState.DISCONNECTED
            } catch (e: Exception) {
                Log.e(TAG, "Zebra disconnect error", e)
            }
        }
    }

    override fun startInventory(): Boolean {
        try {
            rfidReader?.let { reader ->
                // Build AntennaInfo with all supported antennas to mirror sample apps
                val antennaCount = try { reader.ReaderCapabilities.getNumAntennaSupported() } catch (_: Exception) { 1 }
                val antennaInfo = AntennaInfo()
                try {
                    val ids = ShortArray(antennaCount.coerceAtLeast(1))
                    for (i in 0 until ids.size) ids[i] = (i + 1).toShort()
                    antennaInfo.setAntennaID(ids)
                } catch (_: Exception) {}

                // Ensure operating mode for inventory
                try { reader.Config.setOperatingMode(com.zebra.rfid.api3.ENUM_OPERATING_MODE.INVENTORY_MODE) } catch (_: Exception) {}

                try {
                    reader.Actions.Inventory.perform(null, null, antennaInfo)
                } catch (e: Exception) {
                    // fallback to no-arg perform if signature differs
                    reader.Actions.Inventory.perform()
                }
                _isInventorying = true
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startInventory", e)
        }
        return false
    }

    override fun stopInventory(): Boolean {
        try {
            rfidReader?.let { reader ->
                reader.Actions.Inventory.stop()
                _isInventorying = false
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopInventory", e)
        }
        return false
    }

    override fun isInventorying(): Boolean = _isInventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            // Zebra uses transmit power index; map dBm to nearest index
            val reader = rfidReader ?: return@withContext false
            val values = reader.ReaderCapabilities.getTransmitPowerLevelValues()
            Log.d(TAG, "applyConfig: available transmitPower values: ${values?.joinToString(", ")}")
            if (values != null && values.isNotEmpty()) {
                // Detect scale (many readers report 0..300 -> 0.1 dBm steps)
                val maxRaw = values.maxOrNull()?.toDouble() ?: 0.0
                val scale = if (maxRaw > 100.0) 10.0 else 1.0
                val scaled = values.map { it.toDouble() / scale }

                var bestIdx = 0
                var bestDiff = Double.MAX_VALUE
                for (i in scaled.indices) {
                    val diff = kotlin.math.abs(scaled[i] - config.txPower)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestIdx = i
                    }
                }

                val chosenRaw = values[bestIdx]
                val chosenScaled = scaled[bestIdx]
                Log.d(TAG, "applyConfig: chosen bestIdx=$bestIdx raw=$chosenRaw scaled=$chosenScaled for requested ${config.txPower} dBm (scale=$scale)")

                // Apply the chosen index to all supported antennas for consistency
                val antennaCount = try { reader.ReaderCapabilities.getNumAntennaSupported() } catch (_: Exception) { 1 }
                for (antennaId in 1..antennaCount) {
                    try {
                        Log.d(TAG, "applyConfig: setting antenna $antennaId transmitPowerIndex=$bestIdx")
                        val antennaConfig = reader.Config.Antennas.getAntennaRfConfig(antennaId)
                        // set safe defaults used by samples
                        try { antennaConfig.setrfModeTableIndex(0) } catch (_: Exception) {}
                        try { antennaConfig.setTari(0) } catch (_: Exception) {}
                        antennaConfig.setTransmitPowerIndex(bestIdx)
                        reader.Config.Antennas.setAntennaRfConfig(antennaId, antennaConfig)

                        val verifyIdx = try { reader.Config.Antennas.getAntennaRfConfig(antennaId).getTransmitPowerIndex() } catch (_: Exception) { -1 }
                        val verifyRaw = if (verifyIdx >= 0 && verifyIdx < values.size) values[verifyIdx] else -1
                        val verifyScaled = if (verifyRaw >= 0) verifyRaw.toDouble() / scale else -1.0
                        Log.d(TAG, "applyConfig: antenna $antennaId verifyIdx=$verifyIdx verifyRaw=$verifyRaw verifyScaled=$verifyScaled dBm")
                    } catch (ex: Exception) {
                        Log.e(TAG, "applyConfig: failed setting antenna $antennaId powerIndex=$bestIdx", ex)
                    }
                }

                // Try to ensure the reader is in inventory mode
                try { reader.Config.setOperatingMode(com.zebra.rfid.api3.ENUM_OPERATING_MODE.INVENTORY_MODE) } catch (_: Exception) {}

                Log.d(TAG, "applyConfig: setTransmitPowerIndex=$bestIdx for requested ${config.txPower}")
            }
            // map session -> set SingulationControl per antenna (S0..S3)
            try {
                val sessionEnum = when (config.session) {
                    0 -> com.zebra.rfid.api3.SESSION.SESSION_S0
                    1 -> com.zebra.rfid.api3.SESSION.SESSION_S1
                    2 -> com.zebra.rfid.api3.SESSION.SESSION_S2
                    else -> com.zebra.rfid.api3.SESSION.SESSION_S3
                }
                val antennaCount2 = try { reader.ReaderCapabilities.getNumAntennaSupported() } catch (_: Exception) { 1 }
                for (antennaId in 1..antennaCount2) {
                    try {
                        val sing = reader.Config.Antennas.getSingulationControl(antennaId)
                        try { sing.setSession(sessionEnum) } catch (_: Exception) {}
                        try { sing.Action.setInventoryState(com.zebra.rfid.api3.INVENTORY_STATE.INVENTORY_STATE_A) } catch (_: Exception) {}
                        try { sing.Action.setSLFlag(com.zebra.rfid.api3.SL_FLAG.SL_ALL) } catch (_: Exception) {}
                        reader.Config.Antennas.setSingulationControl(antennaId, sing)
                        Log.d(TAG, "applyConfig: antenna $antennaId session set=${config.session}")
                    } catch (ex: Exception) {
                        Log.e(TAG, "applyConfig: failed setting session for antenna $antennaId", ex)
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "applyConfig: session mapping failed", ex)
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "applyConfig error", e)
            return@withContext false
        }
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        try {
            val reader = rfidReader ?: return@withContext ReaderConfig()
            val powers = reader.ReaderCapabilities.getTransmitPowerLevelValues()
            val idx = try { reader.Config.Antennas.getAntennaRfConfig(1).getTransmitPowerIndex() } catch (_: Exception) { -1 }
            Log.d(TAG, "readConfig: available powers=${powers?.joinToString(", ")} idx=$idx")
            val txDbm = if (powers != null && idx >= 0 && idx < powers.size) {
                // Normalize possible scales
                val raw = powers[idx].toDouble()
                when {
                    raw in 0.0..40.0 -> raw.roundToInt()
                    (raw / 10.0) in 0.0..40.0 -> (raw / 10.0).roundToInt()
                    (raw / 100.0) in 0.0..40.0 -> (raw / 100.0).roundToInt()
                    else -> raw.roundToInt()
                }
            } else ReaderConfig().txPower
            Log.d(TAG, "readConfig: resolved txDbm=$txDbm")
            // map session if available: read one antenna singulation control to infer session
            var sessionResolved = ReaderConfig().session
            try {
                val sing = reader.Config.Antennas.getSingulationControl(1)
                val s = try { sing.getSession() } catch (_: Exception) { null }
                sessionResolved = when (s) {
                    com.zebra.rfid.api3.SESSION.SESSION_S0 -> 0
                    com.zebra.rfid.api3.SESSION.SESSION_S1 -> 1
                    com.zebra.rfid.api3.SESSION.SESSION_S2 -> 2
                    com.zebra.rfid.api3.SESSION.SESSION_S3 -> 3
                    else -> sessionResolved
                }
                Log.d(TAG, "readConfig: resolved session=$sessionResolved from singulation control")
            } catch (ex: Exception) {
                Log.d(TAG, "readConfig: could not read singulation control session", ex)
            }
            return@withContext ReaderConfig(txPower = txDbm, session = sessionResolved)
        } catch (e: Exception) {
            Log.e(TAG, "readConfig error", e)
            return@withContext ReaderConfig()
        }
    }

    override fun onTriggerPressed(): Boolean {
        // For handheld triggers, start inventory
        if (_connectionState.value != ReaderConnectionState.CONNECTED) return false
        startInventory()
        return true
    }

    override fun onTriggerReleased(): Boolean {
        stopInventory()
        return true
    }
}
