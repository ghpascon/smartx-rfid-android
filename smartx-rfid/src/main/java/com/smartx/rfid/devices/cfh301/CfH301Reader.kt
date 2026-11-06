package com.smartx.rfid.devices.cfh301

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.core.ReaderConfig
import com.smartx.rfid.core.ReaderConnectionState
import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptador para CF-H301 (protocolo 816UBT) via BLE.
 *
 * O demo oficial utiliza a característica 0xFFE1 para escrita e notificação
 * de frames binários com CRC-16 (polinômio 0x8408, init 0xFFFF).
 */
@SuppressLint("MissingPermission")
class CfH301Reader : IRfidReader {

    override val readerId: String = "CF-H301"
    override val displayName: String = "CF-H301"
    override val isBle: Boolean = true

    override var targetMacAddress: String? = null

    private val TAG = "CfH301Reader"

    private val FFE1_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 128)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothGatt: BluetoothGatt? = null
    private var txRxCharacteristic: BluetoothGattCharacteristic? = null

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var notifyDeferred: CompletableDeferred<Boolean>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null

    private val commandMutex = Mutex()
    private val commandResponses = Channel<ByteArray>(Channel.BUFFERED)
    private val inventoryFrames = Channel<ByteArray>(Channel.BUFFERED)

    private val rxLock = Any()
    private val rxBuffer = ByteArrayOutputStream(1024)

    @Volatile private var mtuPayload: Int = 20
    @Volatile private var commandAddress: Int = 0x00
    @Volatile private var _isInventorying: Boolean = false

    private var inventoryJob: Job? = null

    private var cachedConfig = ReaderConfig(
        txPower = 26,
        session = 0,
        inventoryMode = ReaderConfig.InventoryMode.EPC_TID,
        region = -1
    )

    private val tidCache = ConcurrentHashMap<String, String>()
    private val tidInflight = ConcurrentHashMap.newKeySet<String>()

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT conectado, descobrindo serviços")
                if (!gatt.discoverServices()) {
                    Log.e(TAG, "discoverServices() retornou false")
                    _connectionState.value = ReaderConnectionState.ERROR
                    connectDeferred?.complete(false)
                    connectDeferred = null
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT desconectado status=$status")
                _isInventorying = false
                inventoryJob?.cancel()
                inventoryJob = null
                if (_connectionState.value == ReaderConnectionState.CONNECTING) {
                    connectDeferred?.complete(false)
                    connectDeferred = null
                }
                _connectionState.value = ReaderConnectionState.DISCONNECTED
                closeGattInternal()
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Falha em onConnectionStateChange status=$status newState=$newState")
                _connectionState.value = ReaderConnectionState.ERROR
                connectDeferred?.complete(false)
                connectDeferred = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Falha ao descobrir serviços: $status")
                _connectionState.value = ReaderConnectionState.ERROR
                connectDeferred?.complete(false)
                connectDeferred = null
                return
            }

            val target = findFfe1Characteristic(gatt.services)
            if (target == null) {
                Log.e(TAG, "Característica FFE1 não encontrada")
                _connectionState.value = ReaderConnectionState.ERROR
                connectDeferred?.complete(false)
                connectDeferred = null
                return
            }

            txRxCharacteristic = target
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching { gatt.requestMtu(247) }
            }

            val notifyEnabled = gatt.setCharacteristicNotification(target, true)
            if (!notifyEnabled) {
                Log.e(TAG, "Falha ao habilitar notificação FFE1")
                _connectionState.value = ReaderConnectionState.ERROR
                connectDeferred?.complete(false)
                connectDeferred = null
                return
            }

            val cccd = target.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD ausente em FFE1, continuando")
                _connectionState.value = ReaderConnectionState.CONNECTED
                connectDeferred?.complete(true)
                connectDeferred = null
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            notifyDeferred = CompletableDeferred()
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!started) {
                Log.e(TAG, "Falha ao iniciar writeDescriptor CCCD")
                _connectionState.value = ReaderConnectionState.ERROR
                connectDeferred?.complete(false)
                connectDeferred = null
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            notifyDeferred?.complete(ok)
            notifyDeferred = null
            _connectionState.value = if (ok) ReaderConnectionState.CONNECTED else ReaderConnectionState.ERROR
            connectDeferred?.complete(ok)
            connectDeferred = null
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != FFE1_UUID) return
            val data = characteristic.value ?: return
            handleIncoming(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != FFE1_UUID) return
            handleIncoming(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            completeWrite(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuPayload = (mtu - 3).coerceAtLeast(20)
                Log.d(TAG, "MTU atualizado: $mtu (payload=$mtuPayload)")
            }
        }
    }

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        resetRuntimeState()

        val mac = targetMacAddress?.takeIf { it.isNotBlank() }
        if (mac == null) {
            Log.e(TAG, "MAC BLE não definido")
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth indisponível")
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            Log.e(TAG, "Dispositivo BLE inválido: $mac")
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        connectDeferred = CompletableDeferred()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context.applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context.applicationContext, false, gattCallback)
        }

        val ready = withTimeoutOrNull(20_000L) { connectDeferred?.await() } ?: false
        connectDeferred = null
        if (!ready) {
            Log.e(TAG, "Timeout/Falha ao conectar no CF-H301")
            closeGattInternal()
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        // Bootstrap do protocolo: lê info para obter endereço e potência atuais.
        runCatching {
            val info = sendCommand(buildGetReaderInfoCommand(), expectedCmd = 0x21, timeoutMs = 1_200L)
            if (info != null) parseReaderInfo(info)
        }.onFailure {
            Log.w(TAG, "GetReaderInfo falhou no bootstrap", it)
        }

        _connectionState.value = ReaderConnectionState.CONNECTED
        true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            stopInventory()
            closeGattInternal()
            resetRuntimeState(clearCaches = false)
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar CF-H301", e)
        }
        Unit
    }

    override fun startInventory(): Boolean {
        if (_connectionState.value != ReaderConnectionState.CONNECTED) return false
        if (_isInventorying) return true

        _isInventorying = true
        inventoryJob = readerScope.launch {
            while (isActive && _isInventorying && _connectionState.value == ReaderConnectionState.CONNECTED) {
                val includeTidInInventory = false
                val cmd = buildInventoryCommand(
                    session = cachedConfig.session.coerceIn(0, 3),
                    includeTid = includeTidInInventory
                )

                val response = sendCommand(cmd, expectedCmd = 0x01, timeoutMs = 900L)
                if (response == null) {
                    delay(120L)
                    continue
                }

                drainInventoryFrames()
                delay(25L)
            }
        }
        return true
    }

    override fun stopInventory(): Boolean {
        _isInventorying = false
        inventoryJob?.cancel()
        inventoryJob = null
        drainInventoryFrames()
        return true
    }

    override fun isInventorying(): Boolean = _isInventorying

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        var success = true
        val wasInventorying = _isInventorying

        if (wasInventorying) stopInventory()

        // Potência (demo usa valor direto no comando SetPower).
        val tx = config.txPower.coerceIn(0, 30)
        val powerResp = sendCommand(buildSetPowerCommand(tx), expectedCmd = 0x2F, timeoutMs = 900L)
        if (powerResp == null || powerResp.size <= 3 || u(powerResp[3]) != 0) {
            success = false
        }

        // Região: protocolo usa Min/Max channel codificados por banda.
        val regionRespOk = applyRegionIfSupported(config.region)
        success = success && regionRespOk

        cachedConfig = cachedConfig.copy(
            txPower = tx,
            session = config.session.coerceIn(0, 3),
            inventoryMode = config.inventoryMode,
            region = config.region
        )

        if (wasInventorying) startInventory()
        success
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        if (_connectionState.value == ReaderConnectionState.CONNECTED) {
            val info = sendCommand(buildGetReaderInfoCommand(), expectedCmd = 0x21, timeoutMs = 1_200L)
            if (info != null) parseReaderInfo(info)
        }
        cachedConfig
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

    private suspend fun sendCommand(frame: ByteArray, expectedCmd: Int, timeoutMs: Long): ByteArray? {
        return commandMutex.withLock {
            clearChannel(commandResponses)
            if (!sendRaw(frame)) return@withLock null

            val response = withTimeoutOrNull<ByteArray>(timeoutMs) {
                var matched: ByteArray? = null
                while (matched == null) {
                    val frameResponse = commandResponses.receive()
                    if (frameResponse.size > 3 && u(frameResponse[2]) == expectedCmd) {
                        matched = frameResponse
                    }
                }
                matched
            }
            response
        }
    }

    private suspend fun sendRaw(frame: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = txRxCharacteristic ?: return false

        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + mtuPayload, frame.size)
            val chunk = frame.copyOfRange(offset, end)

            val waiter = CompletableDeferred<Boolean>()
            writeDeferred = waiter

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!started) {
                writeDeferred = null
                return false
            }

            val wrote = withTimeoutOrNull(1_500L) { waiter.await() } ?: false
            if (!wrote) return false
            offset = end

            if (offset < frame.size) delay(12L)
        }
        return true
    }

    private fun handleIncoming(data: ByteArray) {
        val frames = mutableListOf<ByteArray>()
        synchronized(rxLock) {
            rxBuffer.write(data)
            val bytes = rxBuffer.toByteArray()
            var cursor = 0
            while (cursor < bytes.size) {
                if (bytes.size - cursor < 2) break

                val frameSize = u(bytes[cursor]) + 1
                if (frameSize < 5 || frameSize > 512) {
                    cursor += 1
                    continue
                }

                if (bytes.size - cursor < frameSize) break
                frames.add(bytes.copyOfRange(cursor, cursor + frameSize))
                cursor += frameSize
            }

            val remainder = bytes.copyOfRange(cursor, bytes.size)
            rxBuffer.reset()
            rxBuffer.write(remainder)
        }

        for (frame in frames) {
            if (!verifyFrameCrc(frame)) continue
            commandAddress = u(frame[1])
            if (isInventoryTagFrame(frame)) {
                inventoryFrames.trySend(frame)
            } else {
                commandResponses.trySend(frame)
            }
        }
    }

    private fun drainInventoryFrames() {
        while (true) {
            val frame = inventoryFrames.tryReceive().getOrNull() ?: break
            parseInventoryFrame(frame)
        }
    }

    private fun parseInventoryFrame(frame: ByteArray) {
        if (frame.size < 10) return

        val payloadStart = 6
        val payloadEndExclusive = frame.size - 2
        if (payloadStart >= payloadEndExclusive) return

        var idx = payloadStart
        while (idx + 1 < payloadEndExclusive) {
            val epcLen = u(frame[idx])
            if (epcLen <= 0) break

            val epcStart = idx + 1
            val epcEnd = epcStart + epcLen
            if (epcEnd > payloadEndExclusive) break

            val epc = toHex(frame, epcStart, epcLen)
            if (epc.isBlank()) break

            val rssiIndex = epcEnd
            val rssi = if (rssiIndex < payloadEndExclusive) {
                frame[rssiIndex].toInt().toString()
            } else {
                ""
            }

            val tid = tidCache[epc].orEmpty()
            _tagChannel.tryEmit(RfidTag(epc = epc, rssi = rssi, tid = tid))

            if (cachedConfig.inventoryMode != ReaderConfig.InventoryMode.EPC_ONLY && tid.isEmpty()) {
                scheduleTidRead(epc)
            }

            idx += (epcLen + 2)
        }
    }

    private fun scheduleTidRead(epc: String) {
        if (!tidInflight.add(epc)) return
        readerScope.launch {
            try {
                val tid = readTidForEpc(epc)
                if (tid.isNotBlank()) {
                    tidCache[epc] = tid
                    _tagChannel.tryEmit(RfidTag(epc = epc, rssi = "", tid = tid))
                }
            } finally {
                tidInflight.remove(epc)
            }
        }
    }

    private suspend fun readTidForEpc(epcHex: String): String {
        val epcBytes = hexToBytes(epcHex) ?: return ""
        if (epcBytes.isEmpty()) return ""

        val epcWords = (epcHex.length / 4).coerceIn(1, 255)
        val payload = ByteArray(1 + epcBytes.size + 7)
        payload[0] = epcWords.toByte()
        epcBytes.copyInto(payload, destinationOffset = 1)
        val base = 1 + epcBytes.size
        payload[base] = 0x02 // Mem bank TID
        payload[base + 1] = 0x00 // WordAddr
        payload[base + 2] = 0x06 // Num words
        payload[base + 3] = 0x00
        payload[base + 4] = 0x00
        payload[base + 5] = 0x00
        payload[base + 6] = 0x00

        val response = sendCommand(
            frame = buildCommand(commandAddress, 0x02, payload),
            expectedCmd = 0x02,
            timeoutMs = 1_200L
        ) ?: return ""

        if (response.size <= 6 || u(response[3]) != 0) return ""
        val dataStart = 4
        val dataEnd = response.size - 2
        if (dataEnd <= dataStart) return ""
        return toHex(response, dataStart, dataEnd - dataStart)
    }

    private suspend fun applyRegionIfSupported(region: Int): Boolean {
        if (region < 0) return true

        val band = when (region) {
            0x01 -> 4 // ETSI
            0x02 -> 2 // FCC/Brasil
            0x03 -> 1 // China
            0x04 -> 3 // Faixa próxima usada no demo
            else -> return true
        }

        val maxIdx = when (band) {
            1 -> 19
            2 -> 49
            3 -> 31
            4 -> 14
            8 -> 19
            13 -> 34
            else -> 19
        }
        val minIdx = 0

        val minFre = ((band and 0x03) shl 6) or (minIdx and 0x3F)
        val maxFre = ((band and 0x0C) shl 4) or (maxIdx and 0x3F)

        val regionResp = sendCommand(
            frame = buildSetRegionCommand(maxFre, minFre),
            expectedCmd = 0x22,
            timeoutMs = 900L
        )
        return regionResp != null && regionResp.size > 3 && u(regionResp[3]) == 0
    }

    private fun parseReaderInfo(frame: ByteArray) {
        if (frame.size < 11) return

        commandAddress = u(frame[1])
        val power = u(frame[10]).coerceIn(0, 30)

        val fre0 = u(frame[8])
        val fre1 = u(frame[9])
        val band = ((fre0 and 0xC0) shr 4) or ((fre1 and 0xC0) shr 6)
        val mappedRegion = when (band) {
            4 -> 0x01
            2 -> 0x02
            1, 8, 13 -> 0x03
            3 -> 0x04
            else -> cachedConfig.region
        }

        cachedConfig = cachedConfig.copy(txPower = power, region = mappedRegion)
    }

    private fun buildGetReaderInfoCommand(): ByteArray = buildCommand(0xFF, 0x21)

    private fun buildSetPowerCommand(power: Int): ByteArray =
        buildCommand(commandAddress, 0x2F, byteArrayOf(power.toByte()))

    private fun buildSetRegionCommand(maxFre: Int, minFre: Int): ByteArray =
        buildCommand(commandAddress, 0x22, byteArrayOf(maxFre.toByte(), minFre.toByte()))

    private fun buildInventoryCommand(session: Int, includeTid: Boolean): ByteArray {
        val qValue = 4
        val payload = if (includeTid) {
            byteArrayOf(qValue.toByte(), session.toByte(), 0x00, 0x06)
        } else {
            byteArrayOf(qValue.toByte(), session.toByte())
        }
        return buildCommand(commandAddress, 0x01, payload)
    }

    private fun buildCommand(address: Int, cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val body = ByteArray(2 + payload.size)
        body[0] = address.toByte()
        body[1] = cmd.toByte()
        payload.copyInto(body, destinationOffset = 2)

        val totalSize = 1 + body.size + 2
        val frame = ByteArray(totalSize)
        frame[0] = (totalSize - 1).toByte()
        body.copyInto(frame, destinationOffset = 1)
        appendCrc(frame, totalSize - 2)
        return frame
    }

    private fun appendCrc(data: ByteArray, dataLen: Int) {
        var crc = 0xFFFF
        for (i in 0 until dataLen) {
            crc = crc xor u(data[i])
            repeat(8) {
                crc = if ((crc and 0x01) != 0) {
                    (crc shr 1) xor 0x8408
                } else {
                    crc shr 1
                }
            }
        }
        data[dataLen] = (crc and 0xFF).toByte()
        data[dataLen + 1] = ((crc shr 8) and 0xFF).toByte()
    }

    private fun verifyFrameCrc(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val check = ByteArray(frame.size + 2)
        frame.copyInto(check, destinationOffset = 0)
        appendCrc(check, frame.size)
        return check[frame.size] == 0.toByte() && check[frame.size + 1] == 0.toByte()
    }

    private fun isInventoryTagFrame(frame: ByteArray): Boolean {
        if (frame.size < 7) return false
        val cmd = u(frame[2])
        val status = u(frame[3])
        return cmd == 0x01 && status in 0x01..0x04
    }

    private fun findFfe1Characteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (characteristic.uuid == FFE1_UUID) {
                    return characteristic
                }
            }
        }
        return null
    }

    private fun completeWrite(ok: Boolean) {
        writeDeferred?.let { waiter ->
            if (!waiter.isCompleted) waiter.complete(ok)
        }
        writeDeferred = null
    }

    private fun closeGattInternal() {
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        txRxCharacteristic = null
    }

    private fun resetRuntimeState(clearCaches: Boolean = true) {
        clearChannel(commandResponses)
        clearChannel(inventoryFrames)
        synchronized(rxLock) { rxBuffer.reset() }

        notifyDeferred?.complete(false)
        notifyDeferred = null
        writeDeferred?.complete(false)
        writeDeferred = null

        _isInventorying = false
        inventoryJob?.cancel()
        inventoryJob = null

        mtuPayload = 20
        commandAddress = 0x00

        if (clearCaches) {
            tidCache.clear()
            tidInflight.clear()
        }
    }

    private fun clearChannel(channel: Channel<ByteArray>) {
        while (channel.tryReceive().isSuccess) {
            // discard
        }
    }

    private fun toHex(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length * 2)
        for (i in offset until (offset + length)) {
            sb.append(String.format("%02X", u(bytes[i])))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim().replace(" ", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
