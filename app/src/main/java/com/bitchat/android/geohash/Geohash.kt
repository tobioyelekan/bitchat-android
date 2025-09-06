package com.bitchat.android.geohash

/**
 * Lightweight Geohash encoder used for Location Channels.
 * Encodes latitude/longitude to base32 geohash with a fixed precision.
 * 
 * Port of iOS implementation for 100% compatibility
 */
object Geohash {
    
    private val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray()
    private val charToValue: Map<Char, Int> = base32Chars.withIndex().associate { it.value to it.index }

    /**
     * Encodes the provided coordinates into a geohash string.
     * @param latitude Latitude in degrees (-90...90)
     * @param longitude Longitude in degrees (-180...180)
     * @param precision Number of geohash characters (2-12 typical). Values <= 0 return an empty string.
     * @return Base32 geohash string of length `precision`.
     */
    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        if (precision <= 0) return ""

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0

        var isEven = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder()

        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)

        while (geohash.length < precision) {
            if (isEven) {
                val mid = (lonInterval.first + lonInterval.second) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonInterval = mid to lonInterval.second
                } else {
                    lonInterval = lonInterval.first to mid
                }
            } else {
                val mid = (latInterval.first + latInterval.second) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latInterval = mid to latInterval.second
                } else {
                    latInterval = latInterval.first to mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit += 1
            } else {
                geohash.append(base32Chars[ch])
                bit = 0
                ch = 0
            }
        }

        return geohash.toString()
    }

    /**
     * Decodes a geohash string to the center latitude/longitude of its cell.
     * @return Pair(latitude, longitude)
     */
    fun decodeToCenter(geohash: String): Pair<Double, Double> {
        if (geohash.isEmpty()) return 0.0 to 0.0

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0
        var isEven = true

        geohash.lowercase().forEach { ch ->
            val cd = charToValue[ch] ?: return 0.0 to 0.0
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (isEven) {
                    val mid = (lonInterval.first + lonInterval.second) / 2
                    if ((cd and mask) != 0) {
                        lonInterval = mid to lonInterval.second
                    } else {
                        lonInterval = lonInterval.first to mid
                    }
                } else {
                    val mid = (latInterval.first + latInterval.second) / 2
                    if ((cd and mask) != 0) {
                        latInterval = mid to latInterval.second
                    } else {
                        latInterval = latInterval.first to mid
                    }
                }
                isEven = !isEven
            }
        }

        val latCenter = (latInterval.first + latInterval.second) / 2
        val lonCenter = (lonInterval.first + lonInterval.second) / 2
        return latCenter to lonCenter
    }
}
