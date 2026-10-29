package pl.mateuszkaflowski.ambiled.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPortsTest {
    @Test
    fun persistentPortIsUsedAfterRebootWhenServicePropertyIsEmpty() {
        assertEquals(5555, AdbPorts.tcp("", "5555"))
    }

    @Test
    fun runtimePortOverridesPersistentConfiguration() {
        assertEquals(43210, AdbPorts.tcp("43210", "5555"))
    }

    @Test
    fun explicitRuntimeDisableDoesNotFallBackToPersistentPort() {
        assertNull(AdbPorts.tcp("-1", "5555"))
        assertNull(AdbPorts.tcp("0", "5555"))
    }

    @Test
    fun missingAndInvalidPortsAreNotConnectionTargets() {
        listOf("", "-1", "0", "65536", "not-a-port").forEach {
            assertNull(AdbPorts.tcp("", it))
        }
        assertNull(AdbPorts.tcp("invalid", "5555"))
    }

    @Test
    fun propertyWhitespaceAndValidPortBoundariesAreHandled() {
        assertEquals(5555, AdbPorts.tcp(" \n", "5555\n"))
        assertEquals(1, AdbPorts.parse("1"))
        assertEquals(65535, AdbPorts.parse("65535"))
    }
}
