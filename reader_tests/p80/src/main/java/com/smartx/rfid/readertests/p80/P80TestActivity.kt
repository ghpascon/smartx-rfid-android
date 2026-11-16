package com.smartx.rfid.readertests.p80

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.smartx.rfid.core.ConnectionRequest
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.manager.ReaderManager
import com.smartx.rfid.manager.ReaderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Programa de teste do P80.
 *
 * - Lista os leitores disponíveis na biblioteca (P80 vem selecionado).
 * - Conecta no leitor escolhido.
 * - Mostra TODA tecla recebida (DOWN/UP, keyCode, scanCode) e se ela é gatilho.
 * - Mostra quando a leitura INICIA e PARA e se o botão scan está comandando isso.
 * - Mostra as tags lidas (total de leituras, EPCs únicos e a última tag).
 *
 * Para saber o keyCode do botão scan: conecte, aperte o botão e veja a linha "TECLA".
 */
class P80TestActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: ReaderManager

    private var selectedReader: IRfidReader? = null
    private var anyKeyMode = false

    // Contadores exibidos no painel
    private var keyDowns = 0
    private var keyUps = 0
    private var triggerPresses = 0
    private var triggerReleases = 0
    private var inventoryStarts = 0
    private var inventoryStops = 0
    private var totalReads = 0
    private var lastKey = "-"
    private var lastTag = "-"
    private var wasInventorying = false
    private val seenEpcs = HashSet<String>()

    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        manager = ReaderManager(applicationContext, ReaderRegistry.createAll())
        selectedReader = manager.readers.firstOrNull { it.readerId == "P80" } ?: manager.readers.firstOrNull()

        setContentView(buildUi())
        observeManager()

        log("Leitores disponíveis: " + manager.readers.joinToString { it.readerId })
        log("Selecione o leitor, toque em CONECTAR e aperte o botão scan.")
        refreshStatus()
    }

    override fun onDestroy() {
        uiScope.cancel()
        val m = manager
        CoroutineScope(Dispatchers.Default).launch { m.disconnect() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Teclas
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        val down = event.action == KeyEvent.ACTION_DOWN
        val repeat = down && event.repeatCount > 0
        val reader = manager.activeReader
        val isTriggerKey = reader?.capabilities?.triggerKeyCodes?.contains(code) == true
        val name = KeyEvent.keyCodeToString(code)

        if (!repeat) {
            if (down) keyDowns++ else keyUps++
            val tag = when {
                reader == null -> " (sem leitor conectado)"
                isTriggerKey -> " [GATILHO]"
                anyKeyMode -> " [modo qualquer tecla]"
                else -> " (ignorada: não é gatilho do ${reader.readerId})"
            }
            val dir = if (down) "DOWN" else "UP  "
            lastKey = "$dir $name ($code) scan=${event.scanCode}"
            log("TECLA $dir $name ($code) scan=${event.scanCode}$tag")
        }

        val handled = when {
            anyKeyMode -> if (down) manager.onTriggerPressed() else manager.onTriggerReleased()
            else -> if (down) manager.onKeyDown(code) else manager.onKeyUp(code)
        }
        if (handled && !repeat) {
            if (down) triggerPresses++ else triggerReleases++
        }
        refreshStatus()
        return handled || super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------
    // Estado do manager
    // ---------------------------------------------------------------------

    private fun observeManager() {
        uiScope.launch {
            manager.state.collect { s ->
                if (s.isInventorying != wasInventorying) {
                    wasInventorying = s.isInventorying
                    if (s.isInventorying) {
                        inventoryStarts++
                        log(">>> LEITURA INICIADA")
                    } else {
                        inventoryStops++
                        log("<<< LEITURA PARADA")
                    }
                }
                refreshStatus()
            }
        }
        uiScope.launch {
            var shown = 0
            manager.connectionLog.collect { lines ->
                if (lines.size < shown) shown = 0
                lines.drop(shown).forEach { log("conexão: $it") }
                shown = lines.size
            }
        }
        uiScope.launch {
            manager.tags.events.collect { tag ->
                totalReads++
                lastTag = "EPC=${tag.epc} TID=${tag.tid.ifBlank { "-" }} RSSI=${tag.rssi.ifBlank { "-" }}"
                if (seenEpcs.add(tag.epc)) log("TAG NOVA  $lastTag")
                refreshStatus()
            }
        }
    }

    private fun connect() {
        val reader = selectedReader ?: return log("Nenhum leitor selecionado.")
        if (manager.state.value.isConnecting) return log("Já conectando...")
        uiScope.launch {
            log("Conectando em ${reader.displayName} [${reader.readerId}]...")
            val ok = manager.connect(reader, ConnectionRequest(reader.connectionOptions.firstOrNull()))
            log(if (ok) "CONECTADO em ${reader.displayName}" else "FALHA: ${manager.state.value.lastError}")
            refreshStatus()
        }
    }

    private fun disconnect() {
        uiScope.launch {
            manager.disconnect()
            log("Desconectado.")
            refreshStatus()
        }
    }

    private fun resetCounters() {
        keyDowns = 0; keyUps = 0; triggerPresses = 0; triggerReleases = 0
        inventoryStarts = 0; inventoryStops = 0; totalReads = 0
        lastKey = "-"; lastTag = "-"
        seenEpcs.clear()
        manager.clearTags()
        logLines.clear()
        refreshStatus()
        log("Contadores e log limpos.")
    }

    // ---------------------------------------------------------------------
    // UI (montada em código para o app não depender de resources/AppCompat)
    // ---------------------------------------------------------------------

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val s = manager.state.value
        val reader = s.activeReader
        val triggers = reader?.capabilities?.triggerKeyCodes?.sorted()?.joinToString().orEmpty()
        statusView.text = buildString {
            appendLine("CONEXÃO : ${s.connectionState}${if (s.isConnecting) " (conectando...)" else ""}  ${reader?.displayName ?: "-"}")
            appendLine("LEITURA : ${if (s.isInventorying) "LENDO" else "PARADA"}   (iniciou ${inventoryStarts}x, parou ${inventoryStops}x)")
            appendLine("ÚLT.TECLA: $lastKey")
            appendLine("TECLAS  : down=$keyDowns up=$keyUps | gatilho acionou: press=$triggerPresses solta=$triggerReleases")
            appendLine("TAGS    : únicas=${manager.tags.count} leituras=$totalReads")
            appendLine("ÚLT.TAG : $lastTag")
            append("GATILHOS ACEITOS: ${triggers.ifEmpty { "-" }}")
        }
        statusView.setTextColor(if (s.isInventorying) 0xFF1B8E3E.toInt() else 0xFF222222.toInt())
        toggleButton.text = if (s.isInventorying) "PARAR (manual)" else "INICIAR (manual)"
    }

    private fun log(message: String) {
        logLines.addFirst("${timeFormat.format(Date())}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeLast()
        if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
    }

    private fun buildUi(): View {
        val pad = dp(10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(text("P80 Reader Test", 20f, bold = true))
        root.addView(text("Leitores disponíveis:", 14f, bold = true))

        val group = RadioGroup(this)
        manager.readers.forEach { reader ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = buildString {
                    append("${reader.displayName} [${reader.readerId}]")
                    if (reader.isBle) append(" · BLE")
                    if (reader.capabilities.usesKeyEventTrigger) append(" · gatilho por tecla")
                }
                isChecked = reader === selectedReader
                setOnCheckedChangeListener { _, checked -> if (checked) selectedReader = reader }
            }
            group.addView(rb)
        }
        root.addView(group)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("CONECTAR") { connect() })
        row1.addView(button("DESCONECTAR") { disconnect() })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleButton = button("INICIAR (manual)") {
            if (manager.activeReader == null) log("Conecte antes de iniciar.") else manager.toggleInventory()
            refreshStatus()
        }
        row2.addView(toggleButton)
        row2.addView(button("LIMPAR") { resetCounters() })
        root.addView(row2)

        root.addView(CheckBox(this).apply {
            text = "Qualquer tecla = gatilho (diagnóstico)"
            setOnCheckedChangeListener { _, checked ->
                anyKeyMode = checked
                log("Modo qualquer tecla: ${if (checked) "LIGADO" else "desligado"}")
            }
        })

        statusView = text("", 12f, mono = true)
        root.addView(statusView)

        root.addView(text("Log (mais recente primeiro):", 14f, bold = true))
        logView = text("", 11f, mono = true)
        root.addView(logView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xFF222222.toInt())
            if (mono) typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = false // a tecla do scan nunca deve "clicar" um botão focado
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_LOG_LINES = 300
    }
}
