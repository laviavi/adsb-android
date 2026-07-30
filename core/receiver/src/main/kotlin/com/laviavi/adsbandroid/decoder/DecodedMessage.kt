package com.laviavi.adsbandroid.decoder

import com.laviavi.adsbandroid.crc.CrcChecker

sealed class DecodedMessage {
    abstract val frame: RawFrame
    abstract val icao: Int
    abstract val crcResult: CrcChecker.CrcResult

    data class AdsbMessage(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val typecode: Int,
        val subtype: Int,
        val fields: AdsbFields = AdsbFields(),
    ) : DecodedMessage()

    data class AllCallReply(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val capability: Int,
        val iiCode: Int? = null,
    ) : DecodedMessage()

    data class AltitudeReply(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val altitudeFt: Int?,
        val downlinkFormat: Int,
        val callsign: String? = null,
        val commB: CommBFields? = null,
        /** Only DF0 sets this (vertical-status bit); null for DF4/DF20. */
        val onGround: Boolean? = null,
    ) : DecodedMessage()

    data class IdentityReply(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val squawk: String,
        val downlinkFormat: Int,
        val callsign: String? = null,
        val commB: CommBFields? = null,
    ) : DecodedMessage()

    data class LongAirAir(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val altitudeFt: Int? = null,
        val onGround: Boolean? = null,
        val tcasSl: Int? = null,
        val tcasRaActive: Boolean = false,
        val tcasRaText: String? = null,
        val tcasRaComplement: String? = null,
        val tcasRaTerminated: Boolean = false,
        /** Intruder ICAO resolved via `MessageDecoder.resolveApIcao`, hex string e.g. "4840D6". */
        val tcasTargetIcao: String? = null,
    ) : DecodedMessage()

    data class Unknown(
        override val frame: RawFrame,
        override val icao: Int,
        override val crcResult: CrcChecker.CrcResult,
        val downlinkFormat: Int,
    ) : DecodedMessage()
}
