package com.smartx.rfid.devices.x714

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.smartx.rfid.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Adaptador para o leitor próprio X714 via BLE (Nordic UART Service — NUS).
 *
 * Protocolo:
 *  - Serviço NUS:  6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *  - RX (write):   6E400002-B5A3-F393-E0A9-E50E24DCCA9E  ← app envia comandos
 *  - TX (notify):  6E400003-B5A3-F393-E0A9-E50E24DCCA9E  ← leitor notifica eventos
 *
 * Setup: envia os comandos de configuração um a um ao conectar (gpi_start sempre off).
 *        Só considera conectado ao receber "#setup_done".
 *
 * Inicio/parada de leitura:
 *  - Comando software: envia "#read:on" / "#read:off"
 *  - Gatilho físico GPI: recebe "#in_1:on" / "#in_1:off" → mesma ação
 *  - Resposta do leitor: "#read:on" / "#read:off" (quando iniciado por software)
 *  Ambos os modos funcionam simultaneamente.
 *
 * Tags: "#t+@epc|tid|ant|rssi|protect"
 * Configs:
 *  - Ler potência:  envia "#get_power"   → recebe "#power:x"
 *  - Ler session:   envia "#get_session" → recebe "#session:x"
 *  - Setar potência: envia "#read_power:x" → recebe "#power:x"
 *  - Setar session:  envia "#session:x"    → recebe "#session:x"
 */
@SuppressLint("MissingPermission")
class X714Reader : IRfidReader {

    override val readerId: String = "X714"
    override val displayName: String = "X714"
    override val isBle: Boolean = true

    private val TAG = "X714Reader"

    // ── NUS UUIDs ─────────────────────────────────────────────────────────────
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHAR_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write
    private val CHAR_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify
    private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // ── Sequência de setup ────────────────────────────────────────────────────
    // gpi_start sempre off — a lógica GPI é tratada pelo app via #in_1:on/off
    private val SETUP_COMMANDS = listOf(
        "#simple_send:off",
        "#protected_inventory:off",
        "#start_reading:off",
        "#gpi_start:off",
        "#always_send:on",
        "#keyboard:off",
        "#buzzer:on",
        "#decode_gtin:off",
        "#hotspot:on",
        "#prefix:",
        "#setup_reader"
    )

    // ── Estado ────────────────────────────────────────────────────────────────
    /** Endereço MAC BLE selecionado antes de connect() */
    override var targetMacAddress: String? = null

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 128)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null // char de escrita

    // StateFlow: permite que o ViewModel observe mudanças de estado acionadas via GPI (BLE)
    private val _inventoryState = MutableStateFlow(false)
    override val inventoryStateFlow: StateFlow<Boolean> = _inventoryState.asStateFlow()

    @Volatile private var setupDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var powerDeferred: CompletableDeferred<Int>? = null
    @Volatile private var sessionDeferred: CompletableDeferred<Int>? = null

    private var cachedPower: Int = 20
    private var cachedSession: Int = 1

    /** Buffer para montar linhas de fragmentos BLE */
    private val lineBuffer = StringBuilder()

    // ── Fila de escrita BLE ───────────────────────────────────────────────────
    // BLE GATT não suporta escritas concorrentes — cada write deve aguardar
    // onCharacteristicWrite antes de enviar o próximo.
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var writeInProgress = false
    private val writeLock = Any()

    /** Guarda o último chunk enviado para permitir 1 retry em caso de falha GATT. */
    @Volatile private var lastSentChunk: ByteArray? = null
    @Volatile private var writeSentAtMs: Long = 0L
    private val WRITE_TIMEOUT_MS = 3_000L // watchdog: se write não responder em 3s, desbloqueia

    // Watchdog ativo: verifica periodicamente se um write ficou preso.
    // Necessário porque o watchdog passivo no enqueueWrite só dispara se um novo write chegar.
    // Se a fila trava e nenhum comando novo é enviado, sem isso a fila congela para sempre.
    //
    // IMPORTANTE: o watchdog usa um scope próprio da classe, NÃO o scope do withContext em connect().
    // withContext (structured concurrency) aguarda TODOS os filhos antes de retornar — se o watchdog
    // fosse filho do withContext, connect() jamais retornaria (watchdog roda while(isActive) eterno).
    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    private fun startWriteWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = readerScope.launch {
            while (isActive) {
                delay(WRITE_TIMEOUT_MS)
                if (writeInProgress && System.currentTimeMillis() - writeSentAtMs > WRITE_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog ativo: write preso por >${WRITE_TIMEOUT_MS}ms — desbloqueando fila")
                    synchronized(writeLock) {
                        writeInProgress = false
                        lastSentChunk = null
                    }
                    drainWriteQueue()
                }
            }
        }
    }

    private fun stopWriteWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Payload máximo por pacote BLE (atualizado após negociação MTU via onMtuChanged).
     * Padrão BLE: 23 bytes ATT − 3 bytes overhead = 20 bytes de dados.
     * IMPORTANTE: comandos como "#protected_inventory:off\n" têm 26 bytes — excedem o padrão!
     * Por isso iniciamos em 20 e atualizamos após requestMtu.
     */
    @Volatile private var mtuPayload: Int = 20

    /**
     * Callback opcional para exibir progresso de conexão na UI.
     * Atribuído pelo MainViewModel antes de chamar connect().
     */
    override var logSink: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logSink?.invoke(msg)
    }

    // ── GATT Callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT conectado — descobrindo serviços BLE...")
                    val ok = g.discoverServices()
                    Log.d(TAG, "discoverServices() = $ok")
                    if (!ok) log("ERRO: discoverServices() retornou false")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT desconectado (status=$status)")
                    _inventoryState.value = false
                    _connectionState.value = ReaderConnectionState.DISCONNECTED
                    setupDeferred?.complete(false)
                    setupDeferred = null
                    writeQueue.clear()
                    lastSentChunk = null
                    writeInProgress = false
                    // status=133 (GATT_ERROR) = conexão perdida abruptamente.
                    // Chamar close() aqui é necessário para liberar o handle BLE;
                    // sem isso a próxima connectGatt() pode falhar.
                    gatt?.close()
                    gatt = null
                    rxChar = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descoberta de serviços falhou: $status")
                _connectionState.value = ReaderConnectionState.ERROR
                setupDeferred?.complete(false)
                return
            }

            log("${g.services.size} serviço(s) descoberto(s) — procurando NUS...")
            g.services.forEach { svc ->
                Log.d(TAG, "Serviço: ${svc.uuid}")
                svc.characteristics.forEach { c -> Log.d(TAG, "  Char: ${c.uuid} props=${c.properties}") }
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                log("ERRO: Serviço NUS não encontrado. O dispositivo é um X714?")
                _connectionState.value = ReaderConnectionState.ERROR
                setupDeferred?.complete(false)
                return
            }

            val txChar = service.getCharacteristic(CHAR_TX_UUID)
            val rxCharLocal = service.getCharacteristic(CHAR_RX_UUID)

            if (txChar == null || rxCharLocal == null) {
                log("ERRO: Características NUS ausentes. TX=$txChar RX=$rxCharLocal")
                _connectionState.value = ReaderConnectionState.ERROR
                setupDeferred?.complete(false)
                return
            }

            rxChar = rxCharLocal
            log("NUS encontrado — ativando notificações...")

            // Habilita entrega local de notificações no stack Android.
            // BUG COMUM: se retornar false, onCharacteristicChanged NUNCA dispara —
            // o leitor responde mas o app fica surdo.
            val notifOk = g.setCharacteristicNotification(txChar, true)
            if (!notifOk) {
                log("ERRO: setCharacteristicNotification falhou — respostas do leitor não serão recebidas!")
                _connectionState.value = ReaderConnectionState.ERROR
                setupDeferred?.complete(false)
                return
            }

            // Escreve CCCD no periférico para habilitá-lo a enviar notificações
            val descriptor = txChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (queued) {
                    log("CCCD enviado — aguardando confirmação...")
                } else {
                    log("AVISO: writeDescriptor falhou — tentando setup diretamente")
                    sendSetupCommands()
                }
            } else {
                log("AVISO: CCCD não encontrado — enviando setup sem descriptor")
                sendSetupCommands()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("AVISO: CCCD write falhou (status=$status) — notificações podem não funcionar")
                // Tenta prosseguir mesmo assim
            } else {
                log("Notificações confirmadas — negociando MTU...")
            }
            // Solicita MTU 512 para suportar comandos > 20 bytes sem fragmentação extra.
            // onMtuChanged() chamará sendSetupCommands(); se requestMtu() falhar, chama direto.
            if (!g.requestMtu(512)) {
                log("MTU: não suportado — usando ${mtuPayload}B (padrão BLE)")
                sendSetupCommands()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuPayload = maxOf(20, mtu - 3) // 3 bytes = overhead ATT
                log("MTU negociado: $mtu bytes → payload=${mtuPayload}B por pacote")
            } else {
                log("MTU não negociado (status=$status) — usando ${mtuPayload}B por pacote")
            }
            sendSetupCommands()
        }

        /**
         * Chamado após cada write de characteristic completar.
         * Avança a fila para o próximo chunk/comando.
         */
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val chunk = lastSentChunk
                if (chunk != null) {
                    // Reinsere o chunk com prioridade máxima para 1 retry
                    Log.w(TAG, "onCharacteristicWrite falhou (0x${status.toString(16)}) — retentando chunk")
                    lastSentChunk = null
                    synchronized(writeLock) {
                        writeInProgress = false
                        // Adiciona na frente da fila via substituição temporária
                        val pending = ArrayList<ByteArray>(writeQueue.size + 1)
                        pending.add(chunk)
                        writeQueue.drainTo(pending)
                        writeQueue.clear()
                        pending.forEach { writeQueue.offer(it) }
                    }
                } else {
                    Log.w(TAG, "onCharacteristicWrite falhou (0x${status.toString(16)}) — chunk descartado (sem retry)")
                    synchronized(writeLock) { writeInProgress = false }
                }
            } else {
                lastSentChunk = null
                synchronized(writeLock) { writeInProgress = false }
            }
            drainWriteQueue()
        }

        // API < 33
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            processIncoming(String(characteristic.value ?: return, Charsets.UTF_8))
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncoming(String(value, Charsets.UTF_8))
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun sendSetupCommands() {
        log("Enviando ${SETUP_COMMANDS.size} comandos de setup (MTU payload=${mtuPayload}B)...")
        SETUP_COMMANDS.forEach { cmd -> sendCommand(cmd) }
        log("Setup enfileirado — aguardando #setup_done...")
    }

    // ── Processamento de mensagens recebidas ──────────────────────────────────

    private fun processIncoming(text: String) {
        // Sincronizado: lineBuffer é acessado nesta thread (BLE callback) e em connect() (IO)
        val completeLines: List<String>
        synchronized(lineBuffer) {
            lineBuffer.append(text)
            val raw = lineBuffer.toString()

            // Normaliza o stream:
            // 1. Remove \r (dispositivos que usam \r\n)
            // 2. Insere \n antes de cada # que não esteja no início (ex: "#ok#setup_done" →
            //    "#ok\n#setup_done"). Isso suporta protocolos que concatenam respostas sem \n.
            val content = raw
                .replace("\r", "")
                .replace(Regex("(?<=\\S)(?=#)"), "\n")

            val lines = content.split('\n')

            // Só mantém no buffer fragmentos que NÃO começam com '#'.
            // Fragmentos começando com '#' são comandos completos do protocolo X714
            // mesmo sem '\n' final — o dispositivo nem sempre termina cada pacote BLE com '\n'.
            // Bufferizar esses fragmentos faz com que comandos como #setup_done, #in_1:on,
            // #read:on/off fiquem presos até o próximo pacote BLE chegar.
            val lastFragment = if (!content.endsWith('\n')) lines.last() else ""
            lineBuffer.clear()
            if (lastFragment.isNotEmpty() && !lastFragment.startsWith('#')) {
                lineBuffer.append(lastFragment)
            }

            completeLines = when {
                content.endsWith('\n')        -> lines               // todos completos por '\n'
                lastFragment.startsWith('#')  -> lines               // último é comando '#' completo
                else                          -> lines.dropLast(1)   // último é fragmento parcial real
            }
        }

        for (raw in completeLines) {
            // Normaliza: lowercase + remove espaços (padrão do protocolo X714)
            val line = raw.trim().lowercase().replace(" ", "")
            if (line.isEmpty()) continue

            // Mostra TUDO na tela de log — essencial para diagnóstico
            log("← $line")
            Log.d(TAG, "RX: $line")

            when {
                line == "#setup_done" -> {
                    // complete() retorna true apenas se o deferred ainda estava pendente.
                    // Se retornar false, o onConnectionStateChange(DISCONNECTED) já completou
                    // o deferred com false antes deste pacote chegar — race condition BLE comum.
                    // Nesse caso NÃO devemos setar CONNECTED nem logar sucesso falso.
                    val accepted = setupDeferred?.complete(true) == true
                    setupDeferred = null
                    if (accepted) {
                        log("✓ setup_done — leitor pronto!")
                        _connectionState.value = ReaderConnectionState.CONNECTED
                    } else {
                        Log.w(TAG, "#setup_done recebido após conexão encerrada — ignorado")
                    }
                }

                line.startsWith("#t+@") -> {
                    parseTag(line)
                }

                // Confirmação do leitor: única fonte de verdade para o estado de leitura.
                // Atualiza o StateFlow para que o ViewModel reflita na UI.
                line == "#read:on"  -> _inventoryState.value = true
                line == "#read:off" -> _inventoryState.value = false

                // Gatilho físico GPI (botão físico do leitor):
                // O leitor envia #in_1:on ao pressionar e #in_1:off ao soltar.
                // Atualização otimista: estado muda imediatamente para a UI ser responsiva,
                // pois o device pode não ecoar #read:on quando acionado por GPI.
                // O comando é enviado para garantir que o leitor processe a ação.
                line == "#in_1:on" -> {
                    _inventoryState.value = true
                    sendCommand("#read:on")
                }
                line == "#in_1:off" -> {
                    _inventoryState.value = false
                    sendCommand("#read:off")
                }

                // Respostas de potência e session
                line.startsWith("#power:") -> {
                    line.removePrefix("#power:").trim().toIntOrNull()?.let { v ->
                        cachedPower = v
                        powerDeferred?.complete(v)
                        powerDeferred = null
                    }
                }
                line.startsWith("#session:") -> {
                    line.removePrefix("#session:").trim().toIntOrNull()?.let { v ->
                        cachedSession = v
                        sessionDeferred?.complete(v)
                        sessionDeferred = null
                    }
                }

                // Qualquer outra resposta: logada acima, ignorada pelo protocolo
                else -> Log.v(TAG, "RX não tratado: $line")
            }
        }
    }

    /** Parseia linha "#t+@epc|tid|ant|rssi|protect" e emite um RfidTag */
    private fun parseTag(line: String) {
        // line já está em lowercase; epc/tid convertidos para uppercase (padrão RFID)
        val body = line.removePrefix("#t+@")
        val parts = body.split("|")
        if (parts.size < 4) {
            Log.w(TAG, "Formato de tag inesperado: $line")
            return
        }
        val epc  = parts[0].trim().uppercase()
        val tid  = parts.getOrNull(1)?.trim()?.uppercase() ?: ""
        val rssi = parts.getOrNull(3)?.trim() ?: ""
        if (epc.isBlank()) return
        _tagChannel.tryEmit(RfidTag(epc = epc, rssi = rssi, tid = tid))
    }

    // ── Escrita BLE (fila serializada) ────────────────────────────────────────

    /**
     * Enfileira [text] para envio via characteristic RX.
     * Strings maiores que [MTU_PAYLOAD] bytes são fragmentadas automaticamente.
     */
    private fun enqueueWrite(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val payload = mtuPayload // snapshot para evitar race se mudar durante loop
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + payload, bytes.size))
            writeQueue.offer(chunk)
            offset += payload
        }
        drainWriteQueue()
    }

    /**
     * Processa o próximo item da fila se nenhum write estiver em andamento.
     * Deve ser chamado depois de enfileirar ou em onCharacteristicWrite.
     */
    private fun drainWriteQueue() {
        val char = rxChar ?: return
        val g = gatt ?: return
        synchronized(writeLock) {
            if (writeInProgress) return
            val chunk = writeQueue.poll() ?: return
            writeInProgress = true
            lastSentChunk = chunk
            writeSentAtMs = System.currentTimeMillis()
            Log.v(TAG, "TX chunk (${chunk.size}B): ${chunk.toString(Charsets.UTF_8).trimEnd()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    char, chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        }
    }

    private fun sendCommand(cmd: String) {
        log("→ $cmd")
        enqueueWrite("$cmd\n") // \n é adicionado aqui — NUNCA no caller
    }

    // ── IRfidReader ───────────────────────────────────────────────────────────

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        val mac = targetMacAddress
        if (mac.isNullOrBlank()) {
            Log.e(TAG, "MAC não definido — chame targetMacAddress antes de connect()")
            return@withContext false
        }
        _connectionState.value = ReaderConnectionState.CONNECTING
        synchronized(lineBuffer) { lineBuffer.clear() }
        writeQueue.clear()
        writeInProgress = false
        mtuPayload = 20 // reset ao padrão BLE antes de cada nova conexão
        startWriteWatchdog()

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth indisponível ou desligado")
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            Log.e(TAG, "MAC inválido: $mac", e)
            _connectionState.value = ReaderConnectionState.ERROR
            return@withContext false
        }

        val deferred = CompletableDeferred<Boolean>()
        setupDeferred = deferred

        val startMs = System.currentTimeMillis()
        Log.i(TAG, "X714.connect start mac=$mac t=$startMs")
        log("Conectando a $mac...")

        // connectGatt precisa ser chamado na Main thread para callbacks corretos
        withContext(Dispatchers.Main) {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        if (gatt == null) {
            Log.e(TAG, "connectGatt retornou null")
            _connectionState.value = ReaderConnectionState.ERROR
            setupDeferred = null
            return@withContext false
        }

        // Timeout: 15s total (inclui GATT connect + service discovery + setup + #setup_done).
        // O leitor responde ao setup rapidamente após a conexão BLE ser estabelecida.
        val ready = withTimeoutOrNull(15_000L) { deferred.await() } ?: false
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "X714.connect end ready=$ready elapsed=${elapsed}ms")

        if (!ready) {
            Log.e(TAG, "Timeout ou falha ao conectar/receber #setup_done (${elapsed}ms)")
            log("ERRO: sem resposta do leitor em ${elapsed}ms. Verifique se está ligado e próximo.")
            _connectionState.value = ReaderConnectionState.ERROR
            setupDeferred = null
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            rxChar = null
        }
        ready
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        stopWriteWatchdog()
        try {
            writeQueue.clear()
            lastSentChunk = null
            writeInProgress = false
            if (_inventoryState.value) {
                // Tenta parar leitura antes de desconectar (best-effort, sem await)
                gatt?.let { g -> rxChar?.let { c ->
                    val bytes = "#read:off\n".toByteArray(Charsets.UTF_8)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        c.value = bytes
                        @Suppress("DEPRECATION")
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(c)
                    }
                }}
            }
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar", e)
        } finally {
            gatt = null
            rxChar = null
            _inventoryState.value = false
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        }
        Unit
    }

    override fun startInventory(): Boolean {
        sendCommand("#read:on")
        // _isInventorying será atualizado ao receber "#read:on" do leitor
        return true
    }

    override fun stopInventory(): Boolean {
        sendCommand("#read:off")
        // _isInventorying será atualizado ao receber "#read:off" do leitor
        return true
    }

    override fun isInventorying(): Boolean = _inventoryState.value

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        var ok = true

        // Potência
        val pwrD = CompletableDeferred<Int>()
        powerDeferred = pwrD
        sendCommand("#read_power:${config.txPower}")
        val power = withTimeoutOrNull(5_000L) { pwrD.await() }
        if (power != null) cachedPower = power else { ok = false; powerDeferred = null }

        // Session
        val sesD = CompletableDeferred<Int>()
        sessionDeferred = sesD
        sendCommand("#session:${config.session}")
        val session = withTimeoutOrNull(5_000L) { sesD.await() }
        if (session != null) cachedSession = session else { ok = false; sessionDeferred = null }

        Log.i(TAG, "applyConfig ok=$ok power=${cachedPower} session=${cachedSession}")
        ok
    }

    override suspend fun readConfig(): ReaderConfig = withContext(Dispatchers.IO) {
        // Potência
        val pwrD = CompletableDeferred<Int>()
        powerDeferred = pwrD
        sendCommand("#get_power")
        val power = withTimeoutOrNull(5_000L) { pwrD.await() } ?: cachedPower
        powerDeferred = null

        // Session
        val sesD = CompletableDeferred<Int>()
        sessionDeferred = sesD
        sendCommand("#get_session")
        val session = withTimeoutOrNull(5_000L) { sesD.await() } ?: cachedSession
        sessionDeferred = null

        Log.i(TAG, "readConfig power=$power session=$session")
        ReaderConfig(txPower = power, session = session)
    }

    // O X714 não tem gatilho físico mapeado no app (usa GPI interno via #in_1)
    override fun onTriggerPressed(): Boolean = false
    override fun onTriggerReleased(): Boolean = false
}
