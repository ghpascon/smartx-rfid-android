package com.smartx.rfid

import com.smartx.rfid.manager.ReaderRegistry

/** Informações gerais da biblioteca. */
object SmartxRfid {

    /** Versão da biblioteca (vem de `config.json` no build). */
    const val VERSION: String = BuildConfig.VERSION

    /** Ids de todos os leitores suportados/registrados. */
    fun supportedReaderIds(): List<String> = ReaderRegistry.registeredIds()
}
