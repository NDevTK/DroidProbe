package com.droidprobe.testapp.network

import android.util.Log
import java.util.Locale

/**
 * Tests String.format() URL detection.
 * Mirrors patterns seen in real Google apps (e.g. Clock app's Maps API usage).
 */
class StringFormatUrl {

    @JvmField val TIMEZONE_URL = "https://maps.googleapis.com/maps/api/timezone/json?location=%s&timestamp=%d&key=%s"
    @JvmField val GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json?address=%s&key=%s"

    fun fetchTimezone(lat: Double, lng: Double, timestamp: Long, apiKey: String): String {
        val location = "$lat,$lng"
        val url = String.format(Locale.US, TIMEZONE_URL, location, timestamp, apiKey)
        Log.d("StringFormatUrl", "Timezone URL: $url")
        return url
    }

    fun geocodeAddress(address: String, apiKey: String): String {
        val url = String.format(GEOCODE_URL, address, apiKey)
        Log.d("StringFormatUrl", "Geocode URL: $url")
        return url
    }
}
