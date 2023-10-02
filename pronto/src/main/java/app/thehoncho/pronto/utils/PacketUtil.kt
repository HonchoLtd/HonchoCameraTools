package app.thehoncho.pronto.utils

import java.nio.ByteBuffer

object PacketUtil {
    fun readU32Array(b: ByteBuffer): IntArray {
        val len = b.int
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.int
        }
        return a
    }

    fun readU16Array(b: ByteBuffer): IntArray {
        val len = b.int
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.short.toInt() and 0xFFFF
        }
        return a
    }

    fun writeU16Array(b: ByteBuffer, a: IntArray) {
        b.putInt(a.size)
        for (v in a) {
            b.putShort(v.toShort())
        }
    }

    fun readU8Array(b: ByteBuffer): IntArray {
        val len = b.int
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.get().toInt() and 0xFF
        }
        return a
    }

    fun readU32Enumeration(b: ByteBuffer): IntArray {
        val len = b.short.toInt() and 0xFFFF
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.int
        }
        return a
    }

    fun readS16Enumeration(b: ByteBuffer): IntArray {
        val len = b.short.toInt() and 0xFFFF
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.short.toInt()
        }
        return a
    }

    fun readU16Enumeration(b: ByteBuffer): IntArray {
        val len = b.short.toInt() and 0xFFFF
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.short.toInt() and 0xFFFF
        }
        return a
    }

    fun readU8Enumeration(b: ByteBuffer): IntArray {
        val len = b.short.toInt() and 0xFFFF
        val a = IntArray(len)
        for (i in 0 until len) {
            a[i] = b.get().toInt() and 0xFF
        }
        return a
    }

    fun readString(b: ByteBuffer): String {
        val len = b.get().toInt() and 0xFF
        if (len > 0) {
            val ch = CharArray(len - 1)
            for (i in 0 until len - 1) {
                ch[i] = b.char
            }
            // read '\0'
            b.char
            return String(ch)
        }
        return ""
    }

    fun writeString(b: ByteBuffer, s: String) {
        b.put(s.length.toByte())
        if (s.length > 0) {
            for (i in 0 until s.length) {
                b.putShort(s[i].code.toShort())
            }
            b.putShort(0.toShort())
        }
    }

    fun hexDumpToString(a: ByteArray, offset: Int, len: Int): String {
        val lines = len / 16
        val rest = len % 16
        val b = StringBuilder((lines + 1) * 97)
        for (i in 0 until lines) {
            b.append(String.format("%04x ", i * 16))
            for (k in 0..15) {
                b.append(String.format("%02x ", a[offset + i * 16 + k]))
            }
            for (k in 0..15) {
                val ch = Char(a[offset + i * 16 + k].toUShort())
                b.append(if (ch.code in 0x20..0x7E) ch else '.')
            }
            b.append('\n')
        }
        if (rest != 0) {
            b.append(String.format("%04x ", lines * 16))
            for (k in 0 until rest) {
                b.append(String.format("%02x ", a[offset + lines * 16 + k]))
            }
            for (k in 0 until (16 - rest) * 3) {
                b.append(' ')
            }
            for (k in 0 until rest) {
                val ch = Char(a[offset + lines * 16 + k].toUShort())
                b.append(if (ch.code in 0x20..0x7E) ch else '.')
            }
            b.append('\n')
        }
        return b.toString()
    }
}