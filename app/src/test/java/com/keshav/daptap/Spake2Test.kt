package com.keshav.daptap

import com.keshav.daptap.applink.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class Spake2Test {
    private val client = "adb pair client\u0000".toByteArray()
    private val server = "adb pair server\u0000".toByteArray()

    @Test fun `alice and bob agree, and a wrong code doesn't`() {
        val pw = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val a = Spake2(true, client, server)
        val b = Spake2(false, server, client)
        val ma = a.generateMessage(pw)
        val mb = b.generateMessage(pw)
        assertArrayEquals(a.processMessage(mb), b.processMessage(ma))

        val c = Spake2(true, client, server)
        val d = Spake2(false, server, client)
        val mc = c.generateMessage(pw)
        val md = d.generateMessage("654321".toByteArray() + ByteArray(64) { it.toByte() })
        assertFalse(c.processMessage(md)!!.contentEquals(d.processMessage(mc)))
    }

    @Test fun `rejects a message that isn't a curve point`() {
        val a = Spake2(true, client, server).apply { generateMessage("1".toByteArray()) }
        // y = 2 has no matching x on edwards25519.
        assertNull(a.processMessage(ByteArray(32).also { it[0] = 2 }))
    }
}
