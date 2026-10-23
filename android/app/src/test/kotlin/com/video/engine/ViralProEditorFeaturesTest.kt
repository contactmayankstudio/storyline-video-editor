package com.video.engine

import com.video.engine.effects.ProFilterPresets
import com.video.engine.pip.PipLayoutPreset
import com.video.engine.sfx.ViralSfxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class ViralProEditorFeaturesTest {

    @Test
    fun testViralSfxLibraryItemsAndSynthesis() {
        val items = ViralSfxLibrary.items
        assertTrue("Expected at least 10 viral SFX items", items.size >= 10)

        val vineBoom = items.find { it.id == "vine_boom" }
        assertNotNull(vineBoom)
        assertEquals("Vine Boom", vineBoom?.name)

        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_viral_sfx").apply { mkdirs() }
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = tempDir
        }

        for (item in items) {
            val file = ViralSfxLibrary.getOrGenerateSfxFile(mockContext, item)
            assertTrue("Generated SFX file must exist: ${file.name}", file.exists())
            assertTrue("Generated SFX file must have data: ${file.name}", file.length() > 44L)

            // Verify RIFF WAVE header
            val header = ByteArray(44)
            FileInputStream(file).use { it.read(header) }
            val riff = String(header, 0, 4)
            val wave = String(header, 8, 4)
            assertEquals("RIFF", riff)
            assertEquals("WAVE", wave)
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun testPipLayoutPresets() {
        val presets = PipLayoutPreset.values()
        assertTrue("Expected PIP presets", presets.isNotEmpty())

        val floatingBr = PipLayoutPreset.FLOATING_BOTTOM_RIGHT
        assertEquals("pip_br", floatingBr.id)
        assertTrue(floatingBr.scaleX in 0.1f..1.0f)
        assertTrue(floatingBr.scaleY in 0.1f..1.0f)

        val duetSplit = PipLayoutPreset.DUET_SPLIT_HORIZONTAL
        assertEquals(0.5f, duetSplit.scaleX, 0.001f)
        assertEquals(1.0f, duetSplit.scaleY, 0.001f)
    }

    @Test
    fun testRetroGlitchFilterPresets() {
        val vhs = ProFilterPresets.findByName("VHS 1998")
        assertNotNull(vhs)
        assertEquals("VHS 1998", vhs?.name)
        assertTrue((vhs?.params?.saturation ?: 0f) > 1.2f)

        val cyberpunk = ProFilterPresets.findByName("Cyberpunk 2077")
        assertNotNull(cyberpunk)
        assertTrue((cyberpunk?.params?.contrast ?: 0f) > 1.3f)

        val crt = ProFilterPresets.findByName("CRT 80s TV")
        assertNotNull(crt)

        val glitch = ProFilterPresets.findByName("Vapor Glitch")
        assertNotNull(glitch)
    }
}
