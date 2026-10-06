package dev.kortex.finance.domain.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderGateTest {
    @Test
    fun `bank DLT headers may be read`() {
        listOf("AX-HDFCBK", "VM-ICICIT", "JD-SBIINB-S", "vk-kotakb", " BP-AXISBK-T ", "HDFCBK", "AD-PAYTMB").forEach {
            assertTrue(it, SenderGate.mayRead(it))
        }
    }

    @Test
    fun `phone numbers, short codes and anything else are dropped`() {
        listOf("+919876543210", "9876543210", "57575", "AX-123456", "123456", "AX-HDFC", "AX-HDFCBANK", "HDFC BK", "", "Mom").forEach {
            assertFalse(it, SenderGate.mayRead(it))
        }
    }
}
