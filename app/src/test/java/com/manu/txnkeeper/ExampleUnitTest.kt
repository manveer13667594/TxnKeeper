package com.manu.txnkeeper

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}

/** Unit tests for the phrase typed to confirm "Delete data" (Deletedata.kt). */
class DeleteConfirmationTest {

    @Test fun exactPhrase_matches() {
        assertTrue(confirmationMatches("delete all data", DELETE_ALL_PHRASE))
        assertTrue(confirmationMatches("delete account", DELETE_ACCOUNT_PHRASE))
    }

    @Test fun capitalLettersDontMatter() {
        // Phone keyboards often capitalise the first letter by themselves
        assertTrue(confirmationMatches("Delete all data", DELETE_ALL_PHRASE))
        assertTrue(confirmationMatches("DELETE ALL DATA", DELETE_ALL_PHRASE))
    }

    @Test fun extraAndUnusualSpacesAreIgnored() {
        assertTrue(confirmationMatches("  delete all data ", DELETE_ALL_PHRASE))   // keyboard adds a trailing space
        assertTrue(confirmationMatches("delete   all  data", DELETE_ALL_PHRASE))
        assertTrue(confirmationMatches("delete all data", DELETE_ALL_PHRASE)) // non-breaking space
    }

    @Test fun partOfThePhraseIsNotEnough() {
        assertFalse(confirmationMatches("", DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches("   ", DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches("delete", DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches("delete all", DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches("delete data", DELETE_ALL_PHRASE))
    }

    @Test fun differentTextDoesNotMatch() {
        assertFalse(confirmationMatches("delete all datas", DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches("deleteall data", DELETE_ALL_PHRASE))   // a missing space counts
        assertFalse(confirmationMatches("delete all data!", DELETE_ALL_PHRASE))
    }

    @Test fun eachScopeNeedsItsOwnPhrase() {
        // The phrase for one account must not confirm deleting everything, and the other way round
        assertNotEquals(DELETE_ALL_PHRASE, DELETE_ACCOUNT_PHRASE)
        assertFalse(confirmationMatches(DELETE_ACCOUNT_PHRASE, DELETE_ALL_PHRASE))
        assertFalse(confirmationMatches(DELETE_ALL_PHRASE, DELETE_ACCOUNT_PHRASE))
    }

    @Test fun phrasesAreTwoOrThreeWords() {
        for (phrase in listOf(DELETE_ALL_PHRASE, DELETE_ACCOUNT_PHRASE)) {
            val words = phrase.trim().split(" ").size
            assertTrue("\"$phrase\" has $words words", words in 2..3)
            assertEquals(phrase, phrase.lowercase())   // shown and typed in lower case
        }
    }
}