package io.github.kevinbuckham.minilogsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * The fixtures were made with macOS's own newfs_msdos (FAT12, 1088 KB - the size of
 * the ECU's drive - and FAT16, 16 MB) and files copied in through the OS, so the
 * reader is checked against a real FAT implementation, not one written alongside it.
 * macOS added "._name" AppleDouble files to both, including "._rusefi_uaefi_pro.ini",
 * which is exactly the kind of junk the ini picker must not be fooled by.
 */
class FatImageTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun image(name: String): ByteArray =
        GZIPInputStream(resource(name).inputStream()).use { it.readBytes() }

    private fun checkVolume(imgName: String) {
        val vol = FatImage.open(image(imgName))
        assertNotNull(vol)
        val names = vol!!.entries.map { it.name }
        // Long file names decoded, spaces and case kept.
        assertTrue(names.toString(), "rusEFI Forum.url" in names)
        assertTrue(names.toString(), "README-2026-10-20.txt" in names)
        assertTrue(names.toString(), "rusefi_uaefi_pro.ini" in names)

        // The 33 KB ini spans many 2 KB clusters: the whole chain must be followed.
        val ini = vol.entries.single { it.name == "rusefi_uaefi_pro.ini" }
        assertArrayEquals(resource("fat_test.ini"), vol.read(ini))

        val m = IniModel.parse(vol.read(ini)!!.inputStream().bufferedReader())
        assertEquals("rusEFI test.2026.10.20.uaefi_pro.42", m.signature)
        assertEquals(8, m.blockSize)

        // The AppleDouble twin also ends in .ini but carries no signature.
        val junk = vol.entries.single { it.name == "._rusefi_uaefi_pro.ini" }
        val jm = IniModel.parse(vol.read(junk)!!.inputStream().bufferedReader())
        assertEquals("", jm.signature)
    }

    @Test fun readsFat12LikeTheEcuDrive() = checkVolume("fat12_ini.img.gz")

    @Test fun readsFat16() = checkVolume("fat16_ini.img.gz")

    @Test fun rejectsNonFatAndTruncatedImages() {
        assertNull(FatImage.open(ByteArray(100)))
        assertNull(FatImage.open(ByteArray(4096)))                    // all zeros
        val img = image("fat12_ini.img.gz")
        val vol = FatImage.open(img.copyOf(48 * 1024))!!              // ini (33-66 KB) cut off
        val ini = vol.entries.single { it.name == "rusefi_uaefi_pro.ini" }
        assertNull("a chain running off the image is reported, not padded", vol.read(ini))
    }
}
