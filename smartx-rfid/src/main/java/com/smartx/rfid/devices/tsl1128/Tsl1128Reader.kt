package com.smartx.rfid.devices.tsl1128

import android.content.Context
import android.util.Log
import com.smartx.rfid.core.*
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.commands.AbortCommand
import com.uk.tsl.rfid.asciiprotocol.commands.InventoryCommand
import com.uk.tsl.rfid.asciiprotocol.commands.SwitchActionCommand
import com.uk.tsl.rfid.asciiprotocol.device.ConnectionState
import com.uk.tsl.rfid.asciiprotocol.device.ObservableReaderList
import com.uk.tsl.rfid.asciiprotocol.device.Reader
import com.uk.tsl.rfid.asciiprotocol.device.ReaderManager
import com.uk.tsl.rfid.asciiprotocol.device.TransportType
import com.uk.tsl.rfid.asciiprotocol.enumerations.QuerySession
import com.uk.tsl.rfid.asciiprotocol.enumerations.SwitchAction
import com.uk.tsl.rfid.asciiprotocol.enumerations.SwitchState
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState
import com.uk.tsl.rfid.asciiprotocol.responders.ICommandResponseLifecycleDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.ISwitchStateReceivedDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.ITransponderReceivedDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.LoggerResponder
import com.uk.tsl.rfid.asciiprotocol.responders.SwitchResponder
import com.uk.tsl.rfid.asciiprotocol.responders.TransponderData
import com.uk.tsl.utils.HexEncoding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Adaptador para o leitor TSL 1128.
 * Padrão: InventoryModel.java do sample TSL — dois objetos InventoryCommand separados:
 *   • inventoryResponder: passivo, adicionado ao chain, captura respostas e callbacks
 *   • inventoryCommand:   ativo, executado a cada round (contém power + session atuais)
 *
 * Loop contínuo: responseEnded() chama executeCommand(inventoryCommand) DIRETAMENTE
 * (sem ioScope), exatamente como o sample da TSL — o callback já está em thread BG.
 *
 * startInventory() / stopInventory() usam ioScope porque podem ser chamados da
 * main thread (trigger KeyEvent, botão UI).
 */
class Tsl1128Reader : IRfidReader {

    override val readerId: String = "TSL1128"
    override val displayName: String = "TSL 1128"
    override val isBle: Boolean = true

    private val TAG = "Tsl1128Reader"

    override var targetMacAddress: String? = null

    private val _connectionState = MutableStateFlow(ReaderConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ReaderConnectionState> = _connectionState.asStateFlow()

    private val _tagChannel = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    override val tagFlow: Flow<RfidTag> = _tagChannel.asSharedFlow()

    private var currentReader: Reader? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    // Range válido da API TSL para potência (IAntennaParameters javadoc: 10–29)
    private val POWER_MIN = 10
    private val POWER_MAX = 29

    @Volatile private var cachedPower: Int = POWER_MAX
    @Volatile private var cachedSession: Int = 0   // Session 0: tags always respond (no B-state starvation)
    @Volatile private var continuousScanEnabled = false

    // Scope para startInventory/stopInventory (main thread → background)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // =========================================================================
    // inventoryCommand: objeto executado a cada round.
    // Criado uma vez; power/session atualizados antes de cada execução.
    // Segue o padrão de mInventoryCommand do InventoryModel.java da TSL.
    // =========================================================================
    private val inventoryCommand = InventoryCommand().apply {
        // SEM setResetParameters — evita resetar Q-algorithm a cada round,
        // o que degradaria a eficiência de leitura. O reset é feito UMA vez
        // no configCommand enviado no início de cada startInventory().
        setUseAlert(TriState.NO)
        setIncludeTransponderRssi(TriState.YES)
        setUsefastId(TriState.YES)   // Fast ID: lê EPC + TID no mesmo round RF
        setTakeNoAction(TriState.NO)
    }

    // =========================================================================
    // inventoryResponder: captura passiva — adicionado ao chain via addResponder.
    // setCaptureNonLibraryResponses(true) = captura também respostas iniciadas
    // pelo trigger físico do hardware (não só pelo app).
    // responseEnded() chama executeCommand(inventoryCommand) DIRETAMENTE para
    // não sair do thread do SDK e garantir continuidade do loop.
    // =========================================================================
    private val inventoryResponder = InventoryCommand().apply {
        setCaptureNonLibraryResponses(true)
        setIncludeTransponderRssi(TriState.YES)
        setUsefastId(TriState.YES)   // necessário para o responder parsear TID nos dados
        setTransponderReceivedDelegate(ITransponderReceivedDelegate { transponder, _ ->
            onTransponderReceived(transponder)
        })
        setResponseLifecycleDelegate(object : ICommandResponseLifecycleDelegate {
            override fun responseBegan() {}
            override fun responseEnded() {
                if (!continuousScanEnabled) return
                val commander = runCatching { AsciiCommander.sharedInstance() }.getOrNull()
                    ?: return
                if (!commander.isConnected) return
                // Atualiza parâmetros para o próximo round e dispara.
                // Chamada direta — igual a InventoryModel.java da TSL.
                applyParamsToCommand(inventoryCommand)
                runCatching { commander.executeCommand(inventoryCommand) }
                    .onFailure { Log.w(TAG, "responseEnded: executeCommand falhou", it) }
            }
        })
    }

    private fun onTransponderReceived(transponder: TransponderData) {
        val epc = transponder.epc?.takeIf { it.isNotBlank() } ?: return
        val rssi = transponder.rssi?.toString() ?: ""
        // InventoryCommand pode retornar TID em firmwares mais recentes via tidData
        val tid = transponder.tidData
            ?.takeIf { it.isNotEmpty() }
            ?.let { HexEncoding.bytesToString(it).replace(" ", "").uppercase() }
            ?: ""
        _tagChannel.tryEmit(RfidTag(epc = epc, rssi = rssi, tid = tid))
    }

    /** Stampa power e session atuais num objeto InventoryCommand existente. */
    private fun applyParamsToCommand(cmd: InventoryCommand) {
        cmd.setOutputPower(cachedPower.coerceIn(POWER_MIN, POWER_MAX))
        cmd.setQuerySession(sessionToQuerySession(cachedSession))
    }

    // =========================================================================
    // Responder de trigger físico
    // =========================================================================
    private val switchResponder = SwitchResponder().apply {
        setSwitchStateReceivedDelegate(ISwitchStateReceivedDelegate { state ->
            when {
                state != SwitchState.OFF && !continuousScanEnabled -> startInventory()
                state == SwitchState.OFF  && continuousScanEnabled  -> stopInventory()
            }
        })
    }

    // =========================================================================
    // Conexão
    // =========================================================================

    override suspend fun connect(context: Context): Boolean = withContext(Dispatchers.Main) {
        _connectionState.value = ReaderConnectionState.CONNECTING
        try {
            AsciiCommander.createSharedInstance(context.applicationContext)
            val commander = AsciiCommander.sharedInstance()

            commander.clearResponders()
            commander.addResponder(LoggerResponder())
            commander.addSynchronousResponder()

            ReaderManager.create(context.applicationContext)
            ReaderManager.sharedInstance().updateList()

            val mac = targetMacAddress
            if (mac.isNullOrBlank()) {
                Log.e(TAG, "TSL1128: nenhum MAC configurado")
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            val reader = findReaderByMac(mac)
            if (reader == null) {
                Log.e(TAG, "TSL1128: Reader não encontrado para MAC=$mac")
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            currentReader = reader
            commander.setReader(reader)

            val deferred = CompletableDeferred<Boolean>()
            connectDeferred = deferred

            val observer = com.uk.tsl.utils.Observable.Observer<String> { _, _ ->
                when (commander.connectionState) {
                    ConnectionState.CONNECTED -> {
                        _connectionState.value = ReaderConnectionState.CONNECTED
                        connectDeferred?.complete(true)
                        connectDeferred = null
                    }
                    ConnectionState.DISCONNECTED, ConnectionState.LOST -> {
                        _connectionState.value = ReaderConnectionState.DISCONNECTED
                        connectDeferred?.complete(false)
                        connectDeferred = null
                    }
                    else -> {}
                }
            }
            commander.stateChangedEvent().addObserver(observer)

            val started = if (reader.allowMultipleTransports() || reader.lastTransportType == null)
                reader.connect()
            else
                reader.connect(reader.lastTransportType)

            if (!started) {
                commander.stateChangedEvent().removeObserver(observer)
                connectDeferred = null
                _connectionState.value = ReaderConnectionState.ERROR
                return@withContext false
            }

            val ok = withTimeoutOrNull(20_000L) { deferred.await() } ?: false
            commander.stateChangedEvent().removeObserver(observer)

            if (ok) {
                commander.addResponder(inventoryResponder)
                commander.addResponder(switchResponder)
                ioScope.launch { configureSwitchActions(commander) }
            } else {
                _connectionState.value = ReaderConnectionState.ERROR
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "connect exception", e)
            connectDeferred?.complete(false)
            connectDeferred = null
            _connectionState.value = ReaderConnectionState.ERROR
            false
        }
    }

    /** Desativa ação de HW no trigger e habilita report assíncrono de SwitchState. */
    private fun configureSwitchActions(commander: AsciiCommander) {
        if (!commander.isConnected) return
        runCatching {
            val cmd = SwitchActionCommand.synchronousCommand()
            cmd.setSinglePressAction(SwitchAction.OFF)
            cmd.setDoublePressAction(SwitchAction.OFF)
            cmd.setAsynchronousReportingEnabled(TriState.YES)
            commander.executeCommand(cmd)
            Log.d(TAG, "SwitchAction: HW off, async report on")
        }.onFailure { Log.w(TAG, "configureSwitchActions falhou", it) }
    }

    private fun findReaderByMac(mac: String): Reader? {
        val list: ObservableReaderList = ReaderManager.sharedInstance().getReaderList()
        for (r in list.list()) {
            val info = r.getDisplayInfoLine() ?: ""
            val name = r.getDisplayName() ?: ""
            if (info.contains(mac, ignoreCase = true) || name.contains(mac, ignoreCase = true))
                return r
            val btTransport = r.getTransport(TransportType.BLUETOOTH)
            if (btTransport != null && btTransport.toString().contains(mac, ignoreCase = true))
                return r
        }
        return list.list().firstOrNull { it.hasTransportOfType(TransportType.BLUETOOTH) }
    }

    // =========================================================================
    // Inventário
    // =========================================================================

    override fun startInventory(): Boolean {
        val commander = runCatching { AsciiCommander.sharedInstance() }.getOrNull() ?: return false
        if (!commander.isConnected) return false
        if (continuousScanEnabled) return true

        continuousScanEnabled = true
        ioScope.launch {
            // Passo 1: config com reset — UMA vez, limpa firmware e aplica power/session
            val configCmd = InventoryCommand().apply {
                setResetParameters(TriState.YES)
                setOutputPower(cachedPower.coerceIn(POWER_MIN, POWER_MAX))
                setQuerySession(sessionToQuerySession(cachedSession))
                setIncludeTransponderRssi(TriState.YES)
                setUsefastId(TriState.YES)
                setUseAlert(TriState.NO)
                setTakeNoAction(TriState.YES)   // só configura
            }
            runCatching { commander.executeCommand(configCmd) }
                .onFailure { Log.w(TAG, "startInventory: configCmd falhou", it) }
            // Passo 2: primeiro round — responseEnded mantém o loop
            if (continuousScanEnabled && commander.isConnected) {
                applyParamsToCommand(inventoryCommand)
                runCatching { commander.executeCommand(inventoryCommand) }
                    .onFailure { Log.w(TAG, "startInventory: executeCommand falhou", it) }
            }
        }
        return true
    }

    override fun stopInventory(): Boolean {
        continuousScanEnabled = false
        val commander = runCatching { AsciiCommander.sharedInstance() }.getOrNull() ?: return false
        if (!commander.isConnected) return false
        ioScope.launch {
            runCatching { commander.executeCommand(AbortCommand()) }
        }
        return true
    }

    override fun isInventorying() = continuousScanEnabled

    // =========================================================================
    // Configuração
    // =========================================================================

    override suspend fun applyConfig(config: ReaderConfig): Boolean = withContext(Dispatchers.IO) {
        cachedPower   = config.txPower.coerceIn(POWER_MIN, POWER_MAX)
        cachedSession = config.session
        if (continuousScanEnabled) {
            // Durante scan: apenas atualiza o cache; responseEnded() chama
            // applyParamsToCommand() antes de cada round e já pega os novos valores.
            // NÃO enviamos config command agora para não interferir no loop ativo.
            Log.d(TAG, "applyConfig durante scan: power=$cachedPower session=$cachedSession (aplica no próximo round)")
        } else {
            // Fora do scan: envia config ao firmware para persistir
            val commander = runCatching { AsciiCommander.sharedInstance() }.getOrNull()
            if (commander != null && commander.isConnected) {
                val cmd = InventoryCommand().apply {
                    setResetParameters(TriState.YES)
                    setOutputPower(cachedPower)
                    setQuerySession(sessionToQuerySession(cachedSession))
                    setIncludeTransponderRssi(TriState.YES)
                    setUsefastId(TriState.YES)
                    setUseAlert(TriState.NO)
                    setTakeNoAction(TriState.YES)
                }
                runCatching { commander.executeCommand(cmd) }
                    .onFailure { Log.w(TAG, "applyConfig: send config falhou", it) }
            }
        }
        true
    }

    override suspend fun readConfig(): ReaderConfig =
        ReaderConfig(txPower = cachedPower, session = cachedSession)

    // =========================================================================
    // Trigger físico — fallback KeyEvent Android (outros dispositivos BT clássico)
    // Para o TSL 1128 o trigger chega via switchResponder (protocolo ASCII)
    // =========================================================================

    override fun onTriggerPressed(): Boolean {
        if (_connectionState.value != ReaderConnectionState.CONNECTED) return false
        if (!continuousScanEnabled) startInventory()
        return true
    }

    override fun onTriggerReleased(): Boolean {
        if (continuousScanEnabled) stopInventory()
        return true
    }

    // =========================================================================
    // Desconexão
    // =========================================================================

    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        try {
            if (continuousScanEnabled) stopInventory()
            val commander = runCatching { AsciiCommander.sharedInstance() }.getOrNull()
            commander?.removeResponder(inventoryResponder)
            commander?.removeResponder(switchResponder)
            currentReader?.disconnect()
            currentReader = null
            _connectionState.value = ReaderConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "disconnect exception", e)
        }
        Unit
    }

    // =========================================================================
    // Utilitários
    // =========================================================================

    private fun sessionToQuerySession(session: Int): QuerySession = when (session) {
        0    -> QuerySession.SESSION_0
        1    -> QuerySession.SESSION_1
        2    -> QuerySession.SESSION_2
        3    -> QuerySession.SESSION_3
        else -> QuerySession.SESSION_1
    }
}

