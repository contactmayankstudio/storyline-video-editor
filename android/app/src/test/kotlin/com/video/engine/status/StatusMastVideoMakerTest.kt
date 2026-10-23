package com.video.engine.status

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMastVideoMakerTest {

    @Test
    fun categories_containMastViralTabs() {
        val cats = StatusTemplateRepository.categories
        assertTrue(cats.contains("All"))
        assertTrue(cats.contains("⚡ 1-Photo Magic"))
        assertTrue(cats.contains("💖 Love"))
        assertTrue(cats.contains("😎 Attitude"))
        assertTrue(cats.contains("🎂 Birthday"))
        assertTrue(cats.contains("🔱 Bhakti"))
        assertTrue(cats.contains("✨ My Templates"))
    }

    @Test
    fun getByCategory_filtersAccurately() {
        val loveTemplates = StatusTemplateRepository.getByCategory("💖 Love")
        assertTrue(loveTemplates.isNotEmpty())
        assertTrue(loveTemplates.all {
            it.category.equals("Love", true) || it.title.contains("Love", true) || it.emoji in listOf("💖", "❤️", "💕")
        })

        val attitudeTemplates = StatusTemplateRepository.getByCategory("😎 Attitude")
        assertTrue(attitudeTemplates.isNotEmpty())
        assertTrue(attitudeTemplates.all {
            it.category.equals("Attitude", true) || it.title.contains("Phonk", true) || it.title.contains("Attitude", true) || it.emoji in listOf("😎", "🔥", "💥")
        })

        val bdayTemplates = StatusTemplateRepository.getByCategory("🎂 Birthday")
        assertTrue(bdayTemplates.isNotEmpty())
        assertTrue(bdayTemplates.all {
            it.title.contains("Birthday", true) || it.id.contains("bday") || it.emoji == "🎂"
        })

        val bhaktiTemplates = StatusTemplateRepository.getByCategory("🔱 Bhakti")
        assertTrue(bhaktiTemplates.isNotEmpty())
        assertTrue(bhaktiTemplates.all {
            it.id.contains("dev_") || it.title.contains("Ram", true) || it.title.contains("Krishna", true) || it.title.contains("Divine", true) || it.emoji in listOf("🪔", "🪶", "🚩")
        })
    }

    @Test
    fun getViralUsageCount_returnsFormattedString() {
        val tmpl = StatusTemplateRepository.allTemplates.first()
        val usage = tmpl.getViralUsageCount()
        assertTrue("Usage should start with 🔥, got $usage", usage.startsWith("🔥"))
        assertTrue("Usage should end with Uses, got $usage", usage.endsWith("Uses"))
    }
}
