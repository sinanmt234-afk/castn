package com.easycast.shared

object Proto {
    const val CONTROL_PORT = 8765
    const val MIRROR_PORT = 8766
    const val SERVICE_TYPE = "_easycast._tcp."
    const val SERVICE_PREFIX = "EasyCast-"
    // mirror packet types: [type:1][pts:8][len:4][payload]
    const val PKT_HEADER = 0   // payload: width(4) height(4)
    const val PKT_VCONFIG = 1
    const val PKT_VFRAME = 2
    const val PKT_ACONFIG = 3
    const val PKT_AFRAME = 4
}
