package com.manu.txnkeeper

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Put in app/src/test/java/com/example/txnkeeper/ . Run: right-click > Run 'SpokenParserTest'. */
class SpokenParserTest {
    // Thursday 29 Oct 2026, noon
    private val now = Calendar.getInstance().apply { clear(); set(2026, 9, 29, 12, 0, 0) }.timeInMillis
    private fun all(s: String) = SpokenTransactionParser.parseAll(s, now)
    private fun one(s: String) = all(s).single()

    @Test fun single_paidToPersonForPurpose() {
        val t = one("Paid five hundred rupees to Rahul for groceries.")
        assertEquals("Rahul", t.title); assertEquals(500.0, t.amount!!, 0.0)
        assertEquals("EXPENSE", t.type); assertEquals("Groceries", t.description)
    }

    @Test fun single_income_withNumberWordsAndDate() {
        val t = one("Received 1.5 lakh from Amit yesterday.")
        assertEquals(150000.0, t.amount!!, 0.0); assertEquals("INCOME", t.type); assertEquals("Amit", t.title)
        assertTrue(t.dateMillis != null)
    }

    @Test fun single_paymentMethods() {
        assertEquals("UPI", one("Spent 300 on lunch at Dominos using UPI").paymentMethod)
        assertEquals("CARD", one("Paid 1800 to the electricity board by card").paymentMethod)
        assertEquals("CASH", one("Petrol 400 rupees cash").paymentMethod)
        assertEquals("BANK TRANSFER", one("Transferred 5000 to Amit via IMPS").paymentMethod)
    }

    @Test fun single_senderSaysTheyPaidMe_isIncome() {
        val t = one("Rahul sent me 200 rupees on GPay")
        assertEquals("INCOME", t.type); assertEquals("Rahul", t.title); assertEquals("UPI", t.paymentMethod)
    }

    @Test fun amounts_colloquialAndMixed() {
        assertEquals(250.0, one("paid two fifty to Rahul").amount!!, 0.0)
        assertEquals(1500.0, one("paid fifteen hundred rupees for rent").amount!!, 0.0)
        assertEquals(100.0, one("paid hundred rupees to Mom").amount!!, 0.0)
        assertEquals(5000.0, one("gave 5k to Mom").amount!!, 0.0)
        assertEquals(2300.0, one("Got two thousand three hundred from salary").amount!!, 0.0)
        assertEquals(3500.0, one("Paid 3 thousand 500 for school fees").amount!!, 0.0)
    }

    @Test fun amounts_dateIsNotMoney() {
        assertEquals(500.0, one("Paid 500 to Rahul on 12th October").amount!!, 0.0)
        assertEquals(500.0, one("Paid 500 to Rahul two days ago").amount!!, 0.0)
        assertEquals(500.0, one("Paid 500 to Rahul day before yesterday").amount!!, 0.0)
    }

    @Test fun fillersAndWhisperTags_areIgnored() {
        val t = one("Okay so, uh, paid five hundred and twenty rupees to, um, Suresh. [BLANK_AUDIO]")
        assertEquals(520.0, t.amount!!, 0.0); assertEquals("Suresh", t.title)
    }

    @Test fun nothingToSave_returnsEmptyList() {
        assertTrue(all("Hello, how are you?").isEmpty())
        assertTrue(all("I walked 5000 steps today").isEmpty())
        assertTrue(all("Set an alarm for 6 am").isEmpty())
        assertTrue(all("Thank you.").isEmpty())
        assertTrue(all("[BLANK_AUDIO]").isEmpty())
    }

    // ---- several transactions in one recording

    @Test fun multi_mixedExpenseAndIncome() {
        val r = all("I paid 450 to the auto driver and 120 for chai, then my brother sent me 2000.")
        assertEquals(listOf(450.0, 120.0, 2000.0), r.map { it.amount })
        assertEquals(listOf("EXPENSE", "EXPENSE", "INCOME"), r.map { it.type })
        assertEquals("Chai", r[1].title)
    }

    @Test fun multi_listWithSharedVerb() {
        val r = all("Spent 2,000 on groceries, 500 on petrol and 300 on lunch.")
        assertEquals(listOf("Groceries", "Petrol", "Lunch"), r.map { it.title })
        assertEquals(listOf(2000.0, 500.0, 300.0), r.map { it.amount })
        assertTrue(r.all { it.type == "EXPENSE" })
    }

    @Test fun multi_elidedVerbs() {
        val r = all("Paid 350 to Swiggy and 18500 to Sunita.")
        assertEquals(listOf("Swiggy", "Sunita"), r.map { it.title })
        val r2 = all("Received 300 from Neha, 200 from Amit and 100 from Anil")
        assertEquals(listOf("INCOME", "INCOME", "INCOME"), r2.map { it.type })
    }

    @Test fun multi_noPunctuationBetweenClauses() {
        val r = all("bought flowers for 75 rupees paid Neha 500 for lunch received 2000 from Amit")
        assertEquals(3, r.size)
    }

    @Test fun multi_itemWithAndInside_isOneTransaction() {
        val r = all("bought bread and eggs for 120 and milk for 60")
        assertEquals(listOf("Bread and eggs", "Milk").map { it.lowercase() }, r.map { it.title!!.lowercase() })
    }

    @Test fun multi_dateAtStartOrEnd_appliesToAll_butMiddleDateIsLocal() {
        val shared = all("Yesterday I paid 500 to Rahul and 300 to Amit")
        assertTrue(shared.all { it.dateMillis != null })
        assertEquals(shared[0].dateMillis, shared[1].dateMillis)
        val local = all("Paid 500 to Rahul yesterday and then paid 300 to Amit")
        assertTrue(local[0].dateMillis != null); assertNull(local[1].dateMillis)
    }

    @Test fun multi_paymentMethod_onlySharedWhenSaidAsBothOrAll() {
        val local = all("Paid 500 to Rahul and 300 to Amit using UPI")
        assertNull(local[0].paymentMethod); assertEquals("UPI", local[1].paymentMethod)
        val shared = all("Paid 500 to Rahul and 300 to Amit both using UPI")
        assertEquals(listOf("UPI", "UPI"), shared.map { it.paymentMethod })
    }

    @Test fun multi_shortNotes() {
        val r = all("Lunch 200, dinner 300 and auto fare 80")
        assertEquals(listOf("Lunch", "Dinner", "Auto Fare"), r.map { it.title })
        assertEquals(listOf(200.0, 300.0, 80.0), r.map { it.amount })
    }
}
