package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.capture.RtlSdrDefaults
import com.laviavi.adsbandroid.demod.Demodulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Phase 3 tests — USB source logic testable without Android framework.
 * Android-dependent items (Intent dispatch, BroadcastReceiver, Activity result)
 * require instrumented tests on device/emulator.
 */
class Phase3Tests {

    @Nested inner class IqSrcUriTests {

        @Test fun `default URI has correct scheme`() {
            val uri = RtlSdrDefaults.buildIqSrcUri()
            assertTrue(uri.toString().startsWith("iqsrc://"), "Expected iqsrc:// scheme")
        }

        @Test fun `URI contains host`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(host = "127.0.0.1")
            assertTrue(uri.toString().contains("-a 127.0.0.1"))
        }

        @Test fun `URI contains port`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(port = 1234)
            assertTrue(uri.toString().contains("-p 1234"))
        }

        @Test fun `URI contains ADS-B frequency`() {
            val uri = RtlSdrDefaults.buildIqSrcUri()
            assertTrue(uri.toString().contains("-f 1090000000"))
        }

        @Test fun `URI requests the sample rate the demodulator needs`() {
            // Second test that had been asserting 2400000 against the stub. The
            // driver is told the rate through this URI, so a wrong value here is
            // not cosmetic — it is what the dongle actually runs at.
            val uri = RtlSdrDefaults.buildIqSrcUri()
            assertTrue(
                uri.contains("-s ${Demodulator.REQUIRED_SAMPLE_RATE_HZ}"),
                "URI must request ${Demodulator.REQUIRED_SAMPLE_RATE_HZ} Hz, got: $uri",
            )
        }

        @Test fun `URI omits gain when zero (auto)`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(gainTenths = 0)
            assertFalse(uri.toString().contains("-g "))
        }

        @Test fun `URI includes gain when non-zero`() {
            // gainTenths is passed through raw (tenths of a dB) - the driver's
            // SdrTcpArguments.gain field expects tenths directly, not whole dB.
            val uri = RtlSdrDefaults.buildIqSrcUri(gainTenths = 280)  // 28.0 dB
            assertTrue(uri.toString().contains("-g 280"))
        }

        @Test fun `URI omits ppm when zero`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(ppm = 0)
            assertFalse(uri.toString().contains("-P "))
        }

        @Test fun `URI includes ppm when non-zero`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(ppm = 5)
            assertTrue(uri.toString().contains("-P 5"))
        }

        @Test fun `custom port reflected in URI`() {
            val uri = RtlSdrDefaults.buildIqSrcUri(port = 9000)
            assertTrue(uri.toString().contains("-p 9000"))
        }

        @Test fun `URI is parseable string`() {
            val uri = RtlSdrDefaults.buildIqSrcUri()
            assertNotNull(uri.toString())
            assertTrue(uri.toString().isNotEmpty())
        }
    }

    @Nested inner class UsbRtlSdrSourceTests {

        // The old `isOpen false before open` test asserted against a JVM stub of
        // UsbRtlSdrSource, so it only ever proved the stub's own literal. The real
        // source needs Android; its open/close lifecycle belongs in an
        // instrumented test (plan Phase 3), not a fake here.

        @Test fun `LOOPBACK_HOST is localhost`() {
            assertEquals("127.0.0.1", RtlSdrDefaults.LOOPBACK_HOST)
        }

        @Test fun `LOOPBACK_PORT is 1234`() {
            assertEquals(1234, RtlSdrDefaults.LOOPBACK_PORT)
        }

        @Test fun `CENTER_FREQ is 1090 MHz`() {
            assertEquals(1_090_000_000L, RtlSdrDefaults.CENTER_FREQ_HZ)
        }

        @Test fun `SAMPLE_RATE matches what the demodulator requires`() {
            // This read 2_400_000 and passed, because it asserted against a stub
            // that had drifted from the real source. That is precisely the value
            // that produced 1,043,100 frames and 0 valid decodes on hardware.
            assertEquals(2_000_000L, RtlSdrDefaults.SAMPLE_RATE_HZ)
            assertEquals(Demodulator.REQUIRED_SAMPLE_RATE_HZ, RtlSdrDefaults.SAMPLE_RATE_HZ)
        }
    }
}
