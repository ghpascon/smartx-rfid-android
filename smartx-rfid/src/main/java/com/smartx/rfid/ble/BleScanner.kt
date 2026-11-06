package com.smartx.rfid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** Dispositivo Bluetooth encontrado em um scan. */
data class BleDevice(
    val name: String,
    val address: String,
    val isPaired: Boolean
)

/**
 * Escaneia dispositivos BLE (sem UI). Use [scan] e colete o Flow; ele termina sozinho após
 * `timeoutMs`, ou quando a coleta for cancelada. Solicite [BlePermissions] antes de coletar.
 */
class BleScanner(context: Context) {

    private val appContext = context.applicationContext
    private val adapter get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** true se o Bluetooth existe e está ligado. */
    val isBluetoothEnabled: Boolean
        @SuppressLint("MissingPermission")
        get() = adapter?.isEnabled == true

    /**
     * Emite os dispositivos encontrados (pareados primeiro, depois os vistos no scan).
     * Pode emitir o mesmo endereço mais de uma vez — deduplique no consumidor.
     *
     * @throws IllegalStateException se o Bluetooth estiver desligado ou indisponível
     * @throws SecurityException se faltarem permissões ([BlePermissions.missing])
     */
    @SuppressLint("MissingPermission")
    fun scan(timeoutMs: Long = DEFAULT_TIMEOUT_MS, includeBonded: Boolean = true): Flow<BleDevice> = callbackFlow {
        val bt = adapter
        check(bt != null && bt.isEnabled) { "Bluetooth desligado ou indisponível" }
        if (!BlePermissions.hasAll(appContext)) {
            throw SecurityException("Permissões de Bluetooth ausentes: ${BlePermissions.missing(appContext)}")
        }

        if (includeBonded) {
            bt.bondedDevices?.forEach { trySend(it.toBleDevice(isPaired = true)) }
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                trySend(device.toBleDevice(device.bondState == BluetoothDevice.BOND_BONDED))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanner = bt.bluetoothLeScanner
        scanner?.startScan(null, settings, callback)

        launch {
            delay(timeoutMs)
            close()
        }
        awaitClose { scanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBleDevice(isPaired: Boolean) = BleDevice(
        name = name?.takeIf { it.isNotBlank() } ?: "Desconhecido",
        address = address,
        isPaired = isPaired
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
