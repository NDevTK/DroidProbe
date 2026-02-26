package com.droidprobe.app.analysis.manifest

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        return parse(bytes)
    }

    internal fun parse(data: ByteArray): Map<String, List<RawIntentFilter>> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // File header: type(u16) + headerSize(u16) + size(u32)
        val fileType = buf.short.toInt() and 0xFFFF
        buf.short // headerSize
        val fileSize = buf.int
        if (fileType != 0x0003) return emptyMap() // RES_XML_TYPE

        // Parse all chunks
        val strings = mutableListOf<String>()
        val events = mutableListOf<XmlEvent>()

        while (buf.position() < fileSize && buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            val chunkHeaderSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int

            if (chunkSize < 8 || chunkStart + chunkSize > data.size) break

            when (chunkType) {
                0x0001 -> parseStringPool(buf, chunkStart, chunkSize, strings)
                0x0102 -> parseStartElement(buf, strings, events)
                0x0103 -> parseEndElement(buf, strings, events)
                // Skip namespace events (0x0100, 0x0101) and resource map (0x0180)
            }

            buf.position(chunkStart + chunkSize)
        }

        return buildFilters(strings, events)
    }

    // --- String pool parsing ---

    private fun parseStringPool(
        buf: ByteBuffer,
        chunkStart: Int,
        chunkSize: Int,
        strings: MutableList<String>
    ) {
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        @Suppress("UNUSED_VARIABLE") val stylesStart = buf.int

        val isUtf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount) { buf.int }
        // Skip style offsets
        repeat(styleCount) { buf.int }

        val poolDataStart = chunkStart + stringsStart

        for (i in 0 until stringCount) {
            val offset = poolDataStart + offsets[i]
            if (offset < 0 || offset >= chunkStart + chunkSize) {
                strings.add("")
                continue
            }
            buf.position(offset)
            try {
                strings.add(if (isUtf8) readUtf8String(buf) else readUtf16String(buf))
            } catch (_: Exception) {
                strings.add("")
            }
        }
    }

    private fun readUtf8String(buf: ByteBuffer): String {
        // Character length (1-2 bytes, non-standard ULEB128-style)
        var b = buf.get().toInt() and 0xFF
        if (b and 0x80 != 0) {
            buf.get() // consume second byte of char length
        }
        // Byte length (1-2 bytes)
        b = buf.get().toInt() and 0xFF
        val byteLen = if (b and 0x80 != 0) {
            ((b and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
        } else {
            b
        }
        if (byteLen <= 0 || buf.remaining() < byteLen) return ""
        val bytes = ByteArray(byteLen)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(buf: ByteBuffer): String {
        var charLen = buf.short.toInt() and 0xFFFF
        if (charLen and 0x8000 != 0) {
            charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
        }
        if (charLen <= 0 || buf.remaining() < charLen * 2) return ""
        val bytes = ByteArray(charLen * 2)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_16LE)
    }

    // --- XML event parsing ---

    private sealed class XmlEvent {
        data class StartElement(
            val name: String,
            val attrs: List<XmlAttribute>
        ) : XmlEvent()

        data class EndElement(val name: String) : XmlEvent()
    }

    private data class XmlAttribute(
        val nameIdx: Int,
        val rawValueIdx: Int,
        val valueType: Int,
        val valueData: Int
    )

    private fun parseStartElement(
        buf: ByteBuffer,
        strings: List<String>,
        events: MutableList<XmlEvent>
    ) {
        buf.int // lineNumber
        buf.int // comment
        buf.int // namespace
        val nameIdx = buf.int
        val attrStart = buf.short.toInt() and 0xFFFF
        val attrSize = buf.short.toInt() and 0xFFFF
        val attrCount = buf.short.toInt() and 0xFFFF
        buf.short // idIndex
        buf.short // classIndex
        buf.short // styleIndex

        val attrs = mutableListOf<XmlAttribute>()
        for (i in 0 until attrCount) {
            buf.int // namespace
            val aName = buf.int
            val aRawValue = buf.int
            buf.short // typedValue size
            buf.get() // res0
            val aType = buf.get().toInt() and 0xFF
            val aData = buf.int
            attrs.add(XmlAttribute(aName, aRawValue, aType, aData))
        }

        val elementName = getString(strings, nameIdx)
        events.add(XmlEvent.StartElement(elementName, attrs))
    }

    private fun parseEndElement(
        buf: ByteBuffer,
        strings: List<String>,
        events: MutableList<XmlEvent>
    ) {
        buf.int // lineNumber
        buf.int // comment
        buf.int // namespace
        val nameIdx = buf.int
        events.add(XmlEvent.EndElement(getString(strings, nameIdx)))
    }

    // --- Build intent filters from events ---

    private fun buildFilters(
        strings: List<String>,
        events: List<XmlEvent>
    ): Map<String, List<RawIntentFilter>> {
        var packageName = ""
        val result = mutableMapOf<String, MutableList<RawIntentFilter>>()

        var currentComponentName: String? = null
        var currentFilter: RawIntentFilter? = null
        var inApplication = false

        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")

        for (event in events) {
            when (event) {
                is XmlEvent.StartElement -> {
                    when (event.name) {
                        "manifest" -> {
                            packageName = getStringAttr(event.attrs, strings, "package") ?: ""
                        }
                        "application" -> inApplication = true
                        in componentTags -> {
                            if (inApplication) {
                                val rawName = getStringAttr(event.attrs, strings, "name") ?: ""
                                currentComponentName = resolveComponentName(rawName, packageName)
                            }
                        }
                        "intent-filter" -> {
                            if (currentComponentName != null) {
                                currentFilter = RawIntentFilter()
                            }
                        }
                        "action" -> {
                            val name = getStringAttr(event.attrs, strings, "name")
                            if (name != null) currentFilter?.actions?.add(name)
                        }
                        "category" -> {
                            val name = getStringAttr(event.attrs, strings, "name")
                            if (name != null) currentFilter?.categories?.add(name)
                        }
                        "data" -> {
                            currentFilter?.let { filter ->
                                getStringAttr(event.attrs, strings, "scheme")?.let { filter.dataSchemes.add(it) }
                                getStringAttr(event.attrs, strings, "host")?.let { filter.dataHosts.add(it) }
                                getStringAttr(event.attrs, strings, "port")?.let { filter.dataPorts.add(it) }
                                getStringAttr(event.attrs, strings, "path")?.let { filter.dataPaths.add(it) }
                                getStringAttr(event.attrs, strings, "pathPrefix")?.let { filter.dataPaths.add(it) }
                                getStringAttr(event.attrs, strings, "pathPattern")?.let { filter.dataPaths.add(it) }
                                getStringAttr(event.attrs, strings, "mimeType")?.let { filter.dataMimeTypes.add(it) }
                            }
                        }
                    }
                }
                is XmlEvent.EndElement -> {
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

    // --- Helpers ---

    private fun getString(strings: List<String>, index: Int): String {
        return if (index >= 0 && index < strings.size) strings[index] else ""
    }

    private fun getStringAttr(
        attrs: List<XmlAttribute>,
        strings: List<String>,
        attrName: String
    ): String? {
        for (attr in attrs) {
            val name = getString(strings, attr.nameIdx)
            if (name != attrName) continue

            // Try raw string value first
            if (attr.rawValueIdx >= 0 && attr.rawValueIdx < strings.size) {
                return strings[attr.rawValueIdx]
            }
            // Then try typed string value
            if (attr.valueType == 0x03 && attr.valueData >= 0 && attr.valueData < strings.size) {
                return strings[attr.valueData]
            }
            return null
        }
        return null
    }

    private fun resolveComponentName(name: String, packageName: String): String {
        return when {
            name.startsWith(".") -> "$packageName$name"
            !name.contains(".") -> "$packageName.$name"
            else -> name
        }
    }
}
