package com.video.engine.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShayariLibraryRepositoryTest {

    @Test
    fun categoriesContainExpectedFiveCategories() {
        val categories = ShayariLibraryRepository.categories
        assertEquals(5, categories.size)

        val ids = categories.map { it.id }.toSet()
        assertTrue("Contains attitude", ids.contains("attitude"))
        assertTrue("Contains love", ids.contains("love"))
        assertTrue("Contains sad", ids.contains("sad"))
        assertTrue("Contains bhakti", ids.contains("bhakti"))
        assertTrue("Contains birthday", ids.contains("birthday"))
    }

    @Test
    fun eachCategoryHasAtLeastSixQuotes() {
        ShayariLibraryRepository.categories.forEach { category ->
            assertTrue(
                "Category ${category.title} should have at least 6 quotes, but had ${category.quotes.size}",
                category.quotes.size >= 6
            )
            category.quotes.forEach { quote ->
                assertTrue("Quote should not be blank", quote.isNotBlank())
            }
        }
    }

    @Test
    fun getCategoryByIdReturnsCorrectCategory() {
        val attitude = ShayariLibraryRepository.getCategoryById("attitude")
        assertNotNull(attitude)
        assertEquals("Attitude", attitude?.title)
        assertEquals("🔥", attitude?.emoji)

        val bhakti = ShayariLibraryRepository.getCategoryById("bhakti")
        assertNotNull(bhakti)
        assertEquals("Bhakti", bhakti?.title)
        assertEquals("🕉️", bhakti?.emoji)
    }

    @Test
    fun allQuotesReturnsFullFlattenedList() {
        val all = ShayariLibraryRepository.allQuotes
        val sum = ShayariLibraryRepository.categories.sumOf { it.quotes.size }
        assertEquals(sum, all.size)
        assertTrue(all.size >= 30)
    }
}
