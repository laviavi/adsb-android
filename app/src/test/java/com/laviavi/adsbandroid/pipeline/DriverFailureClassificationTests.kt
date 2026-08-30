package com.laviavi.adsbandroid.pipeline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DriverFailureClassificationTests {

    @Test fun `null message falls back to no-dongle`() {
        assertEquals(PipelineService.NO_DONGLE_MESSAGE, PipelineService.classifyDriverFailureMessage(null))
    }

    @Test fun `LIBUSB_ERROR_BUSY is a driver-holding-the-dongle message`() {
        assertEquals(
            PipelineService.DRIVER_BUSY_MESSAGE,
            PipelineService.classifyDriverFailureMessage("[-6] LIBUSB_ERROR_BUSY"),
        )
    }

    @Test fun `the driver's own wrong-arguments string is also a driver-holding-the-dongle message`() {
        // Confirmed live 2026-08-29: a stale marto.rtl_tcp_andro process left listening on
        // the loopback port produced this exact message, not LIBUSB_ERROR_BUSY — force-stopping
        // the driver app was what actually cleared it, same remedy as the BUSY case.
        assertEquals(
            PipelineService.DRIVER_BUSY_MESSAGE,
            PipelineService.classifyDriverFailureMessage("[1] Wrong arguments were supplied! Please, check the sdr_tcp manual!"),
        )
    }

    @Test fun `a timeout marker wins over BUSY-like text`() {
        assertEquals(
            PipelineService.DRIVER_TIMEOUT_MESSAGE,
            PipelineService.classifyDriverFailureMessage("driver did not respond within 20000ms (DRIVER_RESULT_TIMEOUT)"),
        )
    }

    @Test fun `an unrecognized failure falls back to no-dongle`() {
        assertEquals(
            PipelineService.NO_DONGLE_MESSAGE,
            PipelineService.classifyDriverFailureMessage("some other driver error"),
        )
    }
}
