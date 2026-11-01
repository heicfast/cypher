package chat.cypher.android.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Pure Kotlin Geohash encoder, decoder, bounds calculator, and 8-neighbor resolver,
 * ported from bitchat-android's geohash package for offline location channels.
 */
object Geohash {
    private val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray()
    private val charToValue: Map<Char, Int> = base32Chars.withIndex().associate { it.value to it.index }

    data class Bounds(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    )

    fun isValid(geohash: String): Boolean {
        val cleaned = geohash.trim().removePrefix("#").lowercase(Locale.US)
        if (cleaned.length !in 1..12) return false
        return cleaned.all { it in charToValue }
    }

    fun normalizeTag(input: String): String {
        val trimmed = input.trim().lowercase(Locale.US)
        val withoutHash = trimmed.removePrefix("#")
        return "#$withoutHash"
    }

    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        if (precision <= 0) return ""
        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0

        var isEven = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder(precision.coerceIn(1, 12))

        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)
        val targetPrecision = precision.coerceIn(1, 12)

        while (geohash.length < targetPrecision) {
            if (isEven) {
                val mid = (lonInterval.first + lonInterval.second) / 2.0
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonInterval = mid to lonInterval.second
                } else {
                    lonInterval = lonInterval.first to mid
                }
            } else {
                val mid = (latInterval.first + latInterval.second) / 2.0
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

    fun decodeToBounds(geohash: String): Bounds {
        val cleaned = geohash.trim().removePrefix("#").lowercase(Locale.US)
        if (cleaned.isEmpty()) return Bounds(0.0, 0.0, 0.0, 0.0)

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0
        var isEven = true

        cleaned.forEach { ch ->
            val cd = charToValue[ch] ?: return Bounds(0.0, 0.0, 0.0, 0.0)
            for (mask in listOf(16, 8, 4, 2, 1)) {
                if (isEven) {
                    val mid = (lonInterval.first + lonInterval.second) / 2.0
                    lonInterval = if ((cd and mask) != 0) {
                        mid to lonInterval.second
                    } else {
                        lonInterval.first to mid
                    }
                } else {
                    val mid = (latInterval.first + latInterval.second) / 2.0
                    latInterval = if ((cd and mask) != 0) {
                        mid to latInterval.second
                    } else {
                        latInterval.first to mid
                    }
                }
                isEven = !isEven
            }
        }
        return Bounds(
            latMin = latInterval.first,
            latMax = latInterval.second,
            lonMin = lonInterval.first,
            lonMax = lonInterval.second,
        )
    }

    fun decodeToCenter(geohash: String): Pair<Double, Double> {
        val b = decodeToBounds(geohash)
        val lat = (b.latMin + b.latMax) / 2.0
        val lon = (b.lonMin + b.lonMax) / 2.0
        return lat to lon
    }

    fun neighborsSamePrecision(geohash: String): Set<String> {
        val cleaned = geohash.trim().removePrefix("#").lowercase(Locale.US)
        if (cleaned.isEmpty() || !isValid(cleaned)) return emptySet()
        val b = decodeToBounds(cleaned)
        val dLat = b.latMax - b.latMin
        val dLon = b.lonMax - b.lonMin
        val latCenter = (b.latMin + b.latMax) / 2.0
        val lonCenter = (b.lonMin + b.lonMax) / 2.0
        val p = cleaned.length

        fun wrapLon(x: Double): Double {
            var lon = x
            while (lon > 180.0) lon -= 360.0
            while (lon < -180.0) lon += 360.0
            return lon
        }

        val results = LinkedHashSet<String>(8)
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nLat = (latCenter + dy * dLat).coerceIn(-89.999999, 89.999999)
                val nLon = wrapLon(lonCenter + dx * dLon)
                val enc = encode(nLat, nLon, p)
                if (enc.isNotEmpty() && enc != cleaned) {
                    results.add(enc)
                }
            }
        }
        return results
    }

    fun approximateRangeLabel(precision: Int): String {
        return when (precision) {
            8 -> "~ 60 ft"
            7 -> "~ 0.1 mi"
            6 -> "~ 0.8 mi"
            5 -> "~ 3 mi"
            4 -> "~ 25 mi"
            3 -> "~ 100 mi"
            2 -> "~ 600 mi"
            else -> "~ 10 mi"
        }
    }
}

enum class GeohashChannelLevel(
    val precision: Int,
    val displayName: String,
    val rangeDescription: String,
) {
    BUILDING(8, "Building", "~ 60 ft"),
    BLOCK(7, "Block", "~ 0.1 mi"),
    NEIGHBORHOOD(6, "Neighborhood", "~ 0.8 mi"),
    CITY(5, "City", "~ 3 mi"),
    PROVINCE(4, "Province", "~ 25 mi"),
    REGION(2, "Region", "~ 600 mi"),
}

data class GeohashChannel(
    val level: GeohashChannelLevel,
    val geohash: String,
    val areaName: String = "",
) {
    val tag: String
        get() = "#${geohash.lowercase(Locale.US)}"

    val displayName: String
        get() = "${level.displayName} #$geohash"

    val subtitle: String
        get() = if (areaName.isNotBlank()) {
            "${level.rangeDescription} • $areaName"
        } else {
            "${level.rangeDescription} • #$geohash"
        }
}

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val areaName: String,
) {
    fun formatCoordinates(): String {
        val latHemisphere = if (latitude >= 0) "N" else "S"
        val lonHemisphere = if (longitude >= 0) "E" else "W"
        val latStr = String.format(Locale.US, "%.4f", kotlin.math.abs(latitude))
        val lonStr = String.format(Locale.US, "%.4f", kotlin.math.abs(longitude))
        return "$latStr° $latHemisphere, $lonStr° $lonHemisphere"
    }

    fun geohash(precision: Int = 7): String = Geohash.encode(latitude, longitude, precision)
}

data class NearbyLocationResult(
    val hasPermission: Boolean,
    val location: LocationSnapshot?,
    val channels: List<GeohashChannel>,
)

object LocationChannelResolver {
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun resolveBestLocation(context: Context): LocationSnapshot? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (provider in providers) {
            val candidate = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || candidate.time > best.time) {
                best = candidate
            }
        }
        val loc = best ?: return null
        val area = resolveAreaName(context, loc.latitude, loc.longitude)
        return LocationSnapshot(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            timestampMs = loc.time,
            areaName = area,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveAreaName(context: Context, latitude: Double, longitude: Double): String {
        return runCatching {
            if (!Geocoder.isPresent()) return@runCatching ""
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            val first = addresses?.firstOrNull() ?: return@runCatching ""
            first.locality
                ?: first.subAdminArea
                ?: first.adminArea
                ?: first.countryName
                ?: ""
        }.getOrDefault("")
    }

    fun computeChannelsForCoordinates(
        latitude: Double,
        longitude: Double,
        areaName: String = "",
    ): List<GeohashChannel> {
        val levels = listOf(
            GeohashChannelLevel.BLOCK,
            GeohashChannelLevel.NEIGHBORHOOD,
            GeohashChannelLevel.CITY,
            GeohashChannelLevel.PROVINCE,
            GeohashChannelLevel.REGION,
        )
        return levels.map { level ->
            GeohashChannel(
                level = level,
                geohash = Geohash.encode(latitude, longitude, level.precision),
                areaName = areaName,
            )
        }
    }

    fun computeNearbyGeohashChannels(context: Context): NearbyLocationResult {
        val hasPerm = hasLocationPermission(context)
        if (!hasPerm) {
            return NearbyLocationResult(
                hasPermission = false,
                location = null,
                channels = emptyList(),
            )
        }
        val snapshot = resolveBestLocation(context)
        if (snapshot == null) {
            return NearbyLocationResult(
                hasPermission = true,
                location = null,
                channels = emptyList(),
            )
        }
        val channels = computeChannelsForCoordinates(
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            areaName = snapshot.areaName,
        )
        return NearbyLocationResult(
            hasPermission = true,
            location = snapshot,
            channels = channels,
        )
    }
}
