package com.smartx.rfid.manager

import android.util.Log
import com.smartx.rfid.core.IRfidReader
import com.smartx.rfid.devices.c72.C72Reader
import com.smartx.rfid.devices.cfh301.CfH301Reader
import com.smartx.rfid.devices.ih25.IH25Reader
import com.smartx.rfid.devices.simulator.SimulatorReader
import com.smartx.rfid.devices.tsl1128.Tsl1128Reader
import com.smartx.rfid.devices.x714.X714Reader
import com.smartx.rfid.devices.xr2.XR2Reader
import com.smartx.rfid.devices.zebra.ZebraReader

/**
 * Catálogo de fábricas de leitores suportados.
 *
 * Para adicionar um leitor NA BIBLIOTECA: implemente [IRfidReader] em `devices/<modelo>/`
 * e inclua uma linha em [builtIns]. Apps não precisam de nenhuma alteração.
 *
 * Um app pode escolher quais leitores habilitar por id (ver [create]) e até registrar
 * leitores próprios em tempo de execução com [register].
 */
object ReaderRegistry {

    private const val TAG = "ReaderRegistry"

    private val factories = LinkedHashMap<String, () -> IRfidReader>()

    /** Leitores embutidos na biblioteca: id -> fábrica. */
    private val builtIns: List<Pair<String, () -> IRfidReader>> = listOf(
        "C72" to { C72Reader() },
        "SIMULATOR" to { SimulatorReader() },
        "CF-H301" to { CfH301Reader() },
        "IH25" to { IH25Reader() },
        "ZEBRA" to { ZebraReader() },
        "TSL1128" to { Tsl1128Reader() },
        "X714" to { X714Reader() },
        "XR2" to { XR2Reader() }
    )

    init {
        builtIns.forEach { (id, factory) -> factories[id] = factory }
    }

    /** Registra (ou substitui) uma fábrica de leitor. */
    @Synchronized
    fun register(readerId: String, factory: () -> IRfidReader) {
        factories[readerId] = factory
    }

    /** Ids de todos os leitores conhecidos, na ordem de registro. */
    @Synchronized
    fun registeredIds(): List<String> = factories.keys.toList()

    /** Cria uma nova instância do leitor, ou null se o id for desconhecido. */
    @Synchronized
    fun create(readerId: String): IRfidReader? = factories[readerId]?.invoke()

    /**
     * Cria os leitores pedidos, na ordem informada. Ids desconhecidos são ignorados (com log).
     * Lista vazia => todos os leitores registrados.
     */
    fun create(readerIds: Collection<String>): List<IRfidReader> {
        if (readerIds.isEmpty()) return createAll()
        return readerIds.mapNotNull { id ->
            create(id) ?: run {
                Log.w(TAG, "Leitor desconhecido ignorado: $id")
                null
            }
        }
    }

    /** Cria uma instância de cada leitor registrado. */
    fun createAll(): List<IRfidReader> = registeredIds().mapNotNull { create(it) }
}
