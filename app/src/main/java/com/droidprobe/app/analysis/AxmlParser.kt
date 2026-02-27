package com.droidprobe.app.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for Android Binary XML (AXML) format.
 * Handles AndroidManifest.xml, resource XML files, and other compiled XML in APKs.
 */
class AxmlParser {

    sealed class XmlEvent {
        data class StartElement(
            val name: String,
            val attributes: Map<String, String>
        ) : XmlEvent()

        data class EndElement(val name: String) : XmlEvent()
    }

    fun parse(data: ByteArray): List<XmlEvent>? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val fileType = buf.short.toInt() and 0xFFFF
        buf.short // headerSize
        val fileSize = buf.int
        if (fileType != 0x0003) return null // Not RES_XML_TYPE

        val strings = mutableListOf<String>()
        val events = mutableListOf<XmlEvent>()

        while (buf.position() < fileSize && buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            buf.short // chunkHeaderSize
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

        return events
    }

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
        buf.int // stylesStart

        val isUtf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount) { buf.int }
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
        // Character length (1-2 bytes, ULEB128-style)
        var b = buf.get().toInt() and 0xFF
        if (b and 0x80 != 0) buf.get()
        // Byte length (1-2 bytes)
        b = buf.get().toInt() and 0xFF
        val byteLen = if (b and 0x80 != 0) {
            ((b and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
        } else b
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

    private fun parseStartElement(
        buf: ByteBuffer,
        strings: List<String>,
        events: MutableList<XmlEvent>
    ) {
        buf.int // lineNumber
        buf.int // comment
        buf.int // namespace
        val nameIdx = buf.int
        buf.short // attrStart
        buf.short // attrSize
        val attrCount = buf.short.toInt() and 0xFFFF
        buf.short // idIndex
        buf.short // classIndex
        buf.short // styleIndex

        val attrs = mutableMapOf<String, String>()
        for (i in 0 until attrCount) {
            buf.int // namespace
            val aNameIdx = buf.int
            val aRawValue = buf.int
            buf.short // typedValue size
            buf.get() // res0
            val aType = buf.get().toInt() and 0xFF
            val aData = buf.int

            val attrName = getString(strings, aNameIdx)
            val attrValue = when {
                aRawValue >= 0 && aRawValue < strings.size -> strings[aRawValue]
                aType == 0x03 && aData >= 0 && aData < strings.size -> strings[aData]
                else -> continue
            }
            if (attrName.isNotEmpty()) {
                attrs[attrName] = attrValue
            }
        }

        events.add(XmlEvent.StartElement(getString(strings, nameIdx), attrs))
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

    private fun getString(strings: List<String>, index: Int): String =
        if (index in strings.indices) strings[index] else ""
}
