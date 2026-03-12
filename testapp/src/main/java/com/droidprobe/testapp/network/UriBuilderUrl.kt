package com.droidprobe.testapp.network

import android.net.Uri

/**
 * Test pattern: Uri.Builder for constructing API URLs.
 * Common in Google SDK code (Maps Places, etc.)
 */
class UriBuilderUrl {

    fun buildPlacesUrl(apiKey: String, language: String): String {
        val builder = Uri.parse("https://maps.googleapis.com/").buildUpon()
        builder.appendEncodedPath("maps/api/place/")
        builder.appendEncodedPath("nearbysearch/json")
        builder.appendQueryParameter("key", apiKey)
        builder.appendQueryParameter("language", language)
        builder.appendQueryParameter("radius", "1000")
        return builder.build().toString()
    }

    fun buildDirectionsUrl(origin: String, destination: String, apiKey: String): String {
        return Uri.parse("https://maps.googleapis.com/")
            .buildUpon()
            .appendEncodedPath("maps/api/directions/json")
            .appendQueryParameter("origin", origin)
            .appendQueryParameter("destination", destination)
            .appendQueryParameter("key", apiKey)
            .build()
            .toString()
    }
}

/**
 * Test pattern: Abstract base class with Uri.Builder where subclasses provide
 * the path segment and query params — mirrors the Google Places SDK foz.java pattern.
 */
abstract class AbstractUriBuilderBase {

    @JvmField val apiKey: String = "testkey"

    protected abstract fun getEndpointPath(): String
    protected abstract fun getQueryParams(): Map<String, String>

    fun buildUrl(): String {
        val builder = Uri.parse("https://places.googleapis.com/").buildUpon()
        builder.appendEncodedPath("v1/places/")
        builder.appendEncodedPath(getEndpointPath())
        builder.appendQueryParameter("key", apiKey)
        for ((k, v) in getQueryParams()) {
            builder.appendQueryParameter(k, v)
        }
        return builder.build().toString()
    }
}

class PlaceDetailsBuilder : AbstractUriBuilderBase() {
    override fun getEndpointPath(): String = "details/json"
    override fun getQueryParams(): Map<String, String> {
        val map = HashMap<String, String>()
        map.put("placeid", "ChIJ")
        map.put("fields", "name,rating")
        return map
    }
}

class PlaceAutocompleteBuilder : AbstractUriBuilderBase() {
    override fun getEndpointPath(): String = "autocomplete/json"
    override fun getQueryParams(): Map<String, String> {
        val map = HashMap<String, String>()
        map.put("input", "coffee")
        map.put("sessiontoken", "abc")
        map.put("components", "country:us")
        return map
    }
}
