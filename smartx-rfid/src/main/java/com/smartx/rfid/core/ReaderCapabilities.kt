package com.smartx.rfid.core

/**
 * Descreve comportamentos específicos de cada modelo, para que o app tome decisões
 * sem precisar conhecer a classe concreta do leitor.
 */
data class ReaderCapabilities(
    /**
     * true se o app pode reconectar sozinho ao último leitor usado.
     * Leitores que exigem escolha explícita do usuário (ex.: transporte) devem retornar false.
     */
    val supportsAutoReconnect: Boolean = true,

    /**
     * true se o gatilho físico chega como `KeyEvent` na Activity (o app deve repassá-lo a
     * `ReaderManager.onTriggerPressed/Released`). Quando false, o SDK/protocolo do leitor
     * notifica o gatilho por conta própria.
     */
    val usesKeyEventTrigger: Boolean = false
)
