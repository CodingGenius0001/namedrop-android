package com.keshav.daptap

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.UID
import com.keshav.daptap.namedrop.PosterName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PosterNameTest {
    private fun card(poster: ByteArray) =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;;;;\r\nTEL:+15550100\r\nX-WALLPAPER:" +
            Base64.getEncoder().encodeToString(poster).chunked(74).joinToString("\r\n ") + "\r\nEND:VCARD\r\n"

    /** A keyed archive whose object points at its name strings by UID, the way NSKeyedArchiver writes them. */
    private fun archive(): ByteArray {
        val objects = NSArray(
            NSString("\$null"),
            NSDictionary().apply { put("givenName", UID("g", byteArrayOf(2))); put("familyName", UID("f", byteArrayOf(3))); put("\$class", UID("c", byteArrayOf(4))) },
            NSString("Jash"), NSString("Patel"),
            NSDictionary().apply { put("\$classname", "CNPosterName"); put("\$classes", NSArray(NSString("CNPosterName"))) },
        )
        val root = NSDictionary().apply { put("\$archiver", "NSKeyedArchiver"); put("\$objects", objects); put("\$version", 100000) }
        return BinaryPropertyListWriter.writeToArray(root)
    }

    @Test fun `finds the name in a keyed archive`() {
        val r = PosterName.inspect(card(archive()))
        assertEquals("Jash Patel", r.name)
        assertTrue(r.description, r.description.contains("CNPosterName"))
    }

    @Test fun `finds the name in a zipped poster inside a plist`() {
        val zip = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("contents/config.plist")); z.write(archive()); z.closeEntry() }
        }.toByteArray()
        val outer = BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("posterArchiveData", NSData(zip)); put("version", 2) })
        assertEquals("Jash Patel", PosterName.inspect(card(outer)).name)
    }

    @Test fun `file names and identifiers are not names`() {
        val p = BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("name", "poster.heic"); put("displayName", "com.apple.Photos") })
        assertNull(PosterName.inspect(card(p)).name)
        assertEquals("no poster", PosterName.inspect("BEGIN:VCARD\r\nN:;;;;\r\nEND:VCARD").description)
    }

    @Test fun `a poster title string inside a keyed archive counts as the name`() {
        val objects = NSArray(
            NSString("\$null"),
            NSDictionary().apply { put("displayNameText", UID("t", byteArrayOf(2))); put("fontName", UID("f", byteArrayOf(3))); put("\$class", UID("c", byteArrayOf(4))) },
            NSString("Jash"), NSString("SFPro-Bold"),
            NSDictionary().apply { put("\$classname", "PRPosterTitleConfiguration") },
        )
        val root = BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("\$objects", objects); put("\$archiver", "NSKeyedArchiver") })
        val r = PosterName.inspect(card(root))
        assertEquals("Jash", r.name)
        assertTrue(r.description, r.description.contains("\"SFPro-Bold\""))
    }
}
