package com.brucezhang.bydautosign

import org.junit.Assert.*
import org.junit.Test

class PortConfigTest {
    @Test fun normalizesPortLists() {
        assertEquals("8088,8098", PortConfig.normalize("8088, 8098,8088"))
    }
    @Test fun rejectsCommandInjectionAndInvalidPorts() {
        assertNull(PortConfig.normalize("8088;id"))
        assertNull(PortConfig.normalize("0"))
        assertNull(PortConfig.normalize("8088,,8098"))
        assertNull(PortConfig.normalize("65536"))
    }
    @Test fun retainsLegacySettings() {
        assertEquals("8088,8098" to true, PortConfig.fromFile("ports=8088,8098\nforce_foreground_wake=true\n"))
        assertEquals("8088" to false, PortConfig.fromFile(""))
    }
}
