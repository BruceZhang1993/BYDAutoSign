package com.brucezhang.bydautosign

/**
 * A configured wxcode endpoint is identified by its local TCP port in stage 1.
 * Stage 2 will replace this mapping with authenticated account/instance IDs.
 */
object PortConfig {
    fun normalize(text: String): String? {
        val parts = text.split(',').map { it.trim() }
        if (parts.isEmpty() || parts.size > 20 || parts.any { it.isEmpty() }) return null
        val ports = parts.map { it.toIntOrNull() ?: return null }
        if (ports.any { it < 1024 || it > 65535 }) return null
        return ports.distinct().joinToString(",")
    }

    fun fromFile(content: String): Pair<String, Boolean> {
        val properties = content.lineSequence().map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        return (normalize(properties["ports"] ?: "8088") ?: "8088") to
            (properties["force_foreground_wake"] == "true")
    }
}
