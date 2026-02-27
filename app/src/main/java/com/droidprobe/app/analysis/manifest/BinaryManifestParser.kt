package com.droidprobe.app.analysis.manifest

import com.droidprobe.app.analysis.AxmlParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Parses binary AndroidManifest.xml (AXML) from APK files to extract
 * complete intent filters that PackageManager query methods miss.
 */
class BinaryManifestParser {

    data class RawIntentFilter(
        val actions: MutableList<String> = mutableListOf(),
        val categories: MutableList<String> = mutableListOf(),
        val dataSchemes: MutableList<String> = mutableListOf(),
        val dataHosts: MutableList<String> = mutableListOf(),
        val dataPorts: MutableList<String> = mutableListOf(),
        val dataPaths: MutableList<String> = mutableListOf(),
        val dataMimeTypes: MutableList<String> = mutableListOf()
    )

    fun parseFromApk(apkPath: String): Map<String, List<RawIntentFilter>> {
        val bytes = ZipFile(File(apkPath)).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return emptyMap()
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val events = AxmlParser().parse(bytes) ?: return emptyMap()
        return buildFilters(events)
    }

    private fun buildFilters(
        events: List<AxmlParser.XmlEvent>
    ): Map<String, List<RawIntentFilter>> {
        var packageName = ""
        val result = mutableMapOf<String, MutableList<RawIntentFilter>>()

        var currentComponentName: String? = null
        var currentFilter: RawIntentFilter? = null
        var inApplication = false

        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")

        for (event in events) {
            when (event) {
                is AxmlParser.XmlEvent.StartElement -> {
                    when (event.name) {
                        "manifest" -> {
                            packageName = event.attributes["package"] ?: ""
                        }
                        "application" -> inApplication = true
                        in componentTags -> {
                            if (inApplication) {
                                val rawName = event.attributes["name"] ?: ""
                                currentComponentName = resolveComponentName(rawName, packageName)
                            }
                        }
                        "intent-filter" -> {
                            if (currentComponentName != null) {
                                currentFilter = RawIntentFilter()
                            }
                        }
                        "action" -> {
                            event.attributes["name"]?.let { currentFilter?.actions?.add(it) }
                        }
                        "category" -> {
                            event.attributes["name"]?.let { currentFilter?.categories?.add(it) }
                        }
                        "data" -> {
                            currentFilter?.let { filter ->
                                event.attributes["scheme"]?.let { filter.dataSchemes.add(it) }
                                event.attributes["host"]?.let { filter.dataHosts.add(it) }
                                event.attributes["port"]?.let { filter.dataPorts.add(it) }
                                event.attributes["path"]?.let { filter.dataPaths.add(it) }
                                event.attributes["pathPrefix"]?.let { filter.dataPaths.add(it) }
                                event.attributes["pathPattern"]?.let { filter.dataPaths.add(it) }
                                event.attributes["mimeType"]?.let { filter.dataMimeTypes.add(it) }
                            }
                        }
                    }
                }
                is AxmlParser.XmlEvent.EndElement -> {
                    when (event.name) {
                        "application" -> {
                            inApplication = false
                            currentComponentName = null
                        }
                        in componentTags -> {
                            currentComponentName = null
                            currentFilter = null
                        }
                        "intent-filter" -> {
                            val filter = currentFilter
                            val comp = currentComponentName
                            if (filter != null && comp != null) {
                                result.getOrPut(comp) { mutableListOf() }.add(filter)
                            }
                            currentFilter = null
                        }
                    }
                }
            }
        }

        return result
    }

    private fun resolveComponentName(name: String, packageName: String): String {
        return when {
            name.startsWith(".") -> "$packageName$name"
            !name.contains(".") -> "$packageName.$name"
            else -> name
        }
    }
}
