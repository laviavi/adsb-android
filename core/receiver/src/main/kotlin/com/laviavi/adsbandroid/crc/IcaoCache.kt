package com.laviavi.adsbandroid.crc

/**
 * ICAO address cache — port of Python `crc/icao_cache.py` (itself a port of
 * dump1090's icao_cache).
 *
 * Mode S has two CRC families:
 *  - Pure-CRC frames (DF 11, 17, 18): last 24 bits are a plain CRC, verifiable
 *    by anyone. On success the ICAO (bytes 1-3) is added here.
 *  - Parity-Address frames (DF 0, 4, 5, 16, 20, 21, 24): last 24 bits are
 *    CRC(message) XOR transponder_address. Unverifiable unless the address is
 *    already known — which is what this cache provides.
 *
 * Constants match dump1090: 1024 slots (power of two), 60s TTL, hash collisions
 * overwrite (last writer wins).
 */
class IcaoCache {

    private val addr = IntArray(CACHE_LEN)
    private val ts   = LongArray(CACHE_LEN)
    private val lock = Any()

    /** Record an ICAO confirmed from a pure-CRC frame. */
    fun add(icao: Int, nowMs: Long = System.currentTimeMillis()) {
        val slot = hash(icao)
        synchronized(lock) {
            addr[slot] = icao
            ts[slot] = nowMs
        }
    }

    /** True if [icao] was confirmed by a pure-CRC frame within the TTL. */
    fun contains(icao: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val slot = hash(icao)
        val a: Int
        val t: Long
        synchronized(lock) { a = addr[slot]; t = ts[slot] }
        return a != 0 && a == icao && (nowMs - t) <= CACHE_TTL_MS
    }

    /** Number of non-expired entries. */
    fun count(nowMs: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            return (0 until CACHE_LEN).count { addr[it] != 0 && (nowMs - ts[it]) <= CACHE_TTL_MS }
        }
    }

    fun clear() {
        synchronized(lock) {
            addr.fill(0)
            ts.fill(0L)
        }
    }

    companion object {
        const val CACHE_LEN = 1024
        const val CACHE_TTL_MS = 60_000L

        /**
         * dump1090's ICAOCacheHashAddress(). Int overflow is the intended
         * mod-2^32 behaviour; `ushr` (not `shr`) keeps the shift unsigned.
         */
        fun hash(icao: Int): Int {
            var a = icao
            a = ((a ushr 16) xor a) * 0x45d9f3b
            a = ((a ushr 16) xor a) * 0x45d9f3b
            a = (a ushr 16) xor a
            return a and (CACHE_LEN - 1)
        }
    }
}
