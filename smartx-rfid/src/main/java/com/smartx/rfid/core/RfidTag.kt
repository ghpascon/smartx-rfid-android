package com.smartx.rfid.core

import java.util.Date

data class RfidTag(
    val epc: String,
    val rssi: String = "",
    val tid: String = "",
    val user: String = "",
    val antenna: Int = 1,
    val readCount: Int = 1,
    val timestamp: Date = Date()
)
