package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.DEFAULT_TAGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the tag emoji helpers (TagEmoji.kt) and the tags a new install starts with. */
class TagEmojiTest {

    // ---------------------------------------------------------------- shipped tags

    @Test fun defaultTags_allStartWithAnEmoji_andAreAlreadyTidy() {
        DEFAULT_TAGS.forEach { (name, _) ->
            assertTrue("no emoji: $name", hasLeadingEmoji(name))
            assertTrue("empty words: $name", stripLeadingEmoji(name).isNotEmpty())
            assertTrue("too long: $name", name.length <= MAX_TAG_NAME)
            assertEquals(name, withTagEmoji(name, MAX_TAG_NAME))   // doing it again changes nothing
        }
    }

    @Test fun suggestedTags_allStartWithAnEmoji_andAreAlreadyTidy() {
        SUGGESTED_TAGS.forEach { (name, color) ->
            assertTrue("no emoji: $name", hasLeadingEmoji(name))
            assertTrue("too long: $name", name.length <= MAX_TAG_NAME)
            assertTrue("bad colour: $name", color in TAG_COLORS.indices)
            assertEquals(name, withTagEmoji(name, MAX_TAG_NAME))
        }
    }

    // ---------------------------------------------------------------- adding an emoji

    @Test fun nameWithoutEmoji_getsOneThatFits() {
        assertEquals("🛒 Groceries", withTagEmoji("Groceries", MAX_TAG_NAME))
        assertEquals("🍔 Food", withTagEmoji("  Food ", MAX_TAG_NAME))
        assertEquals("⛽ Petrol", withTagEmoji("Petrol", MAX_TAG_NAME))     // not mistaken for "pet"
        assertEquals("🐶 Pet food", withTagEmoji("Pet food", MAX_TAG_NAME))
        assertEquals("🧾 2024 Taxes", withTagEmoji("2024 Taxes", MAX_TAG_NAME))
    }

    @Test fun unknownName_getsTheLabelEmoji() {
        assertEquals("$DEFAULT_TAG_EMOJI Misc", withTagEmoji("Misc", MAX_TAG_NAME))
    }

    @Test fun emojiTypedByTheUser_isKept() {
        assertEquals("🎉 Rent", withTagEmoji("🎉 Rent", MAX_TAG_NAME))
        assertEquals("🎉 Rent", withTagEmoji("🎉Rent", MAX_TAG_NAME))
    }

    @Test fun emojiThatNeedsEmojiStyle_getsIt() {
        assertEquals("✈️ Trip", withTagEmoji("✈ Trip", MAX_TAG_NAME))
        assertEquals("✈️ Trip", withTagEmoji("✈️ Trip", MAX_TAG_NAME))     // not added twice
        assertEquals("🛍️ Shopping", withTagEmoji("Shopping", MAX_TAG_NAME))
        assertEquals("🏋️ Gym", withTagEmoji("Gym", MAX_TAG_NAME))
    }

    @Test fun blankStaysBlank_andEmojiAloneHasNoWords() {
        assertEquals("", withTagEmoji("   ", MAX_TAG_NAME))
        assertEquals("🎉", withTagEmoji("🎉", MAX_TAG_NAME))
        assertEquals("", stripLeadingEmoji("🎉"))
    }

    // ---------------------------------------------------------------- length

    @Test fun longName_isCutToTheLimit_withTheEmojiFirst() {
        val cut = withTagEmoji("Groceries and household things for the month", MAX_TAG_NAME)
        assertTrue(cut.length <= MAX_TAG_NAME)
        assertTrue(cut.startsWith("🛒 "))
    }

    @Test fun cutting_neverSplitsAnEmoji() {
        val cut = withTagEmoji("Birthday 🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉", MAX_TAG_NAME)
        assertTrue(cut.length <= MAX_TAG_NAME)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    // ---------------------------------------------------------------- finding the emoji

    @Test fun multiCharacterEmoji_countAsOne() {
        assertEquals(4, leadingEmojiLength("👍🏽 Ok"))                  // skin tone
        assertEquals("Family", stripLeadingEmoji("👨‍👩‍👧 Family"))       // joined family
        assertEquals("India", stripLeadingEmoji("🇮🇳 India"))           // flag (two letters)
        assertEquals("First", stripLeadingEmoji("1️⃣ First"))           // keycap
    }

    @Test fun lettersAndDigits_areNotEmoji() {
        assertEquals(0, leadingEmojiLength("2024 Taxes"))
        assertEquals(0, leadingEmojiLength("Groceries"))
        assertEquals(0, leadingEmojiLength(""))
    }
}
