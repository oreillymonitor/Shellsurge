package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `defaults to null token and clears cleanly when blank`() {
        assertNull(UpdatePreferences.getGithubToken(context))

        UpdatePreferences.setGithubToken(context, "  github_pat_1234567890abcdef  ")
        assertEquals("github_pat_1234567890abcdef", UpdatePreferences.getGithubToken(context))

        UpdatePreferences.setGithubToken(context, "   ")
        assertNull(UpdatePreferences.getGithubToken(context))

        UpdatePreferences.setGithubToken(context, "abc")
        UpdatePreferences.setGithubToken(context, null)
        assertNull(UpdatePreferences.getGithubToken(context))
    }

    @Test
    fun `maskedPreview hides middle characters`() {
        assertNull(UpdatePreferences.maskedPreview(null))
        assertNull(UpdatePreferences.maskedPreview("   "))
        assertEquals("••••ef", UpdatePreferences.maskedPreview("abcdef"))
        assertEquals("gith••••cdef", UpdatePreferences.maskedPreview("github_pat_1234567890abcdef"))
    }
}
