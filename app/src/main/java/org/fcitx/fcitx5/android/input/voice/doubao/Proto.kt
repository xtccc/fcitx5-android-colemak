/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.doubao

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format support.
 *
 * The Doubao ASR protocol only uses varint (wire type 0) and length-delimited
 * (wire type 2) fields, so neither a protobuf runtime nor code generation is
 * needed here. Field numbers and semantics mirror the reference implementation
 * in `vinput-registry/resources/providers/doubaoime/streaming/entry.py`.
 */
internal object Proto {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5

    class Writer(initialCapacity: Int = 1024) {

        private val out = ByteArrayOutputStream(initialCapacity)

        private fun varint(value: Long) {
            var remaining = value
            while (true) {
                val b = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining != 0L) {
                    out.write(b or 0x80)
                } else {
                    out.write(b)
                    return
                }
            }
        }

        private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        /** Empty values are omitted, exactly like the reference implementation. */
        fun string(field: Int, value: String) {
            if (value.isEmpty()) return
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            if (value.isEmpty()) return
            tag(field, WIRE_LENGTH_DELIMITED)
            varint(value.size.toLong())
            out.write(value, 0, value.size)
        }

        fun int(field: Int, value: Int) {
            if (value == 0) return
            tag(field, WIRE_VARINT)
            varint(value.toLong())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Fields {

        private val values = LinkedHashMap<Int, MutableList<Any>>()

        fun add(field: Int, value: Any) {
            values.getOrPut(field) { ArrayList(1) }.add(value)
        }

        private fun last(field: Int): Any? = values[field]?.lastOrNull()

        fun string(field: Int): String = when (val v = last(field)) {
            is ByteArray -> String(v, Charsets.UTF_8)
            is Long -> v.toString()
            else -> ""
        }

        fun int(field: Int): Int = (last(field) as? Long)?.toInt() ?: 0
    }

    fun parse(data: ByteArray): Fields {
        val fields = Fields()
        var index = 0
        while (index < data.size) {
            val key = readVarint(data, index)
            index = key.second
            val field = (key.first ushr 3).toInt()
            when ((key.first and 0x07).toInt()) {
                WIRE_VARINT -> {
                    val value = readVarint(data, index)
                    index = value.second
                    fields.add(field, value.first)
                }

                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint(data, index)
                    index = length.second
                    val end = index + length.first.toInt()
                    require(end <= data.size) { "truncated length-delimited field $field" }
                    fields.add(field, data.copyOfRange(index, end))
                    index = end
                }

                WIRE_FIXED_64 -> {
                    require(index + 8 <= data.size) { "truncated fixed64 field $field" }
                    index += 8
                }

                WIRE_FIXED_32 -> {
                    require(index + 4 <= data.size) { "truncated fixed32 field $field" }
                    index += 4
                }

                else -> error("unsupported protobuf wire type ${key.first and 0x07}")
            }
        }
        return fields
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var index = start
        while (true) {
            require(index < data.size) { "unexpected end of protobuf varint" }
            val b = data[index].toInt() and 0xFF
            index++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to index
            shift += 7
            require(shift < 64) { "protobuf varint is too long" }
        }
    }
}
