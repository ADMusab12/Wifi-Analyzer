package com.codetech.wifianalyzer.abstraction

data class WifiSignal(
    val ssid: String,
    val strength: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val ping: Int
)
