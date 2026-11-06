package com.smartx.rfid.tags

import com.smartx.rfid.core.RfidTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coleta, filtra e deduplica (por EPC) as tags vindas de um leitor.
 *
 * - [tags]: lista consolidada, atualizada no máximo a cada [refreshIntervalMs] (throttle de UI)
 * - [events]: cada leitura individual que passou pelo [filter] (sem throttle), útil para
 *   radar, buzzer, contadores etc.
 *
 * Seguro para chamadas de qualquer thread.
 */
class TagCollector(
    private val scope: CoroutineScope,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_MS
) {

    companion object {
        const val DEFAULT_REFRESH_MS = 200L
    }

    /** Filtro atual. Pode ser trocado a qualquer momento; vale para as próximas leituras. */
    @Volatile
    var filter: TagFilter = TagFilter()

    private val map = LinkedHashMap<String, RfidTag>()
    private val lock = Any()
    @Volatile private var dirty = false

    private val _tags = MutableStateFlow<List<RfidTag>>(emptyList())
    val tags: StateFlow<List<RfidTag>> = _tags.asStateFlow()

    private val _events = MutableSharedFlow<RfidTag>(extraBufferCapacity = 64)
    val events: SharedFlow<RfidTag> = _events.asSharedFlow()

    private var attachJob: Job? = null
    private var tickerJob: Job? = null

    /** Quantidade de EPCs únicos coletados (valor imediato, sem throttle). */
    val count: Int get() = synchronized(lock) { map.size }

    /** Cópia imediata das tags coletadas (sem throttle). */
    fun snapshot(): List<RfidTag> = synchronized(lock) { map.values.toList() }

    /** Começa a consumir [source]; substitui qualquer fonte anterior. */
    fun attach(source: Flow<RfidTag>) {
        attachJob?.cancel()
        ensureTicker()
        attachJob = scope.launch { source.collect { add(it) } }
    }

    /** Para de consumir a fonte atual (não limpa as tags coletadas). */
    fun detach() {
        attachJob?.cancel()
        attachJob = null
    }

    /**
     * Processa uma tag manualmente. Retorna false se foi descartada pelo [filter].
     * Tags repetidas incrementam `readCount` e atualizam RSSI/timestamp.
     */
    fun add(tag: RfidTag): Boolean {
        if (!filter.accepts(tag)) return false
        synchronized(lock) {
            val existing = map[tag.epc]
            map[tag.epc] = if (existing == null) {
                tag
            } else {
                existing.copy(
                    readCount = existing.readCount + 1,
                    rssi = tag.rssi,
                    timestamp = tag.timestamp
                )
            }
            dirty = true
        }
        _events.tryEmit(tag)
        return true
    }

    /** Remove todas as tags coletadas. */
    fun clear() {
        synchronized(lock) {
            map.clear()
            dirty = false
            _tags.value = emptyList()
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(refreshIntervalMs)
                synchronized(lock) {
                    if (dirty) {
                        dirty = false
                        _tags.value = map.values.toList()
                    }
                }
            }
        }
    }
}
