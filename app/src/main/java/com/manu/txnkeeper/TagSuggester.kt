package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionDao

/*
 * Picks the most fitting tag(s) for a new transaction from its title (and note), so the tag chips
 * can be switched on for you. Two steps, both on the phone:
 *   1. History: transactions with the same (or a very similar) title that you tagged before.
 *      The tag wins when most of them used it.
 *   2. Keywords: a built-in word list (petrol -> Fuel, swiggy -> Food ...) matched against the
 *      names of the tags you actually have, plus the tag's own name appearing in the text.
 * Only tags that exist are ever returned. No match gives an empty set (nothing is switched on).
 */

/** Words that point at a kind of tag; the first group is matched against the words of your tag names. */
private val TAG_KEYWORDS: List<Pair<List<String>, List<String>>> = listOf(
    listOf("fuel", "petrol", "diesel", "cng") to listOf("petrol", "diesel", "fuel", "filling", "pump", "cng", "hpcl", "bpcl", "iocl", "indianoil"),
    listOf("grocer", "vegetable", "kirana", "mart") to listOf("grocery", "groceries", "vegetable", "vegetables", "fruit", "fruits", "kirana", "bigbasket", "blinkit", "zepto", "dmart", "instamart", "milk"),
    listOf("food", "eating", "restaurant", "dining") to listOf("swiggy", "zomato", "restaurant", "dhaba", "pizza", "burger", "biryani", "cafe", "hotel", "dominos", "mcdonalds", "kfc", "meal", "lunch", "dinner", "breakfast"),
    listOf("coffee", "tea", "chai") to listOf("coffee", "chai", "tea", "starbucks", "cafe"),
    listOf("recharge", "mobile", "phone") to listOf("recharge", "airtel", "jio", "vi", "vodafone", "bsnl", "prepaid", "postpaid"),
    listOf("internet", "wifi", "broadband") to listOf("broadband", "wifi", "fiber", "internet", "act"),
    listOf("electric", "power") to listOf("electricity", "bijli", "power", "bses", "pspcl", "discom"),
    listOf("bill", "bills") to listOf("bill", "bills", "electricity", "water", "gas", "dth", "tatasky", "postpaid"),
    listOf("transport", "travel", "commute", "taxi", "cab") to listOf("uber", "ola", "rapido", "metro", "bus", "auto", "cab", "taxi", "train", "irctc", "redbus", "toll", "fastag"),
    listOf("travel", "trip") to listOf("flight", "irctc", "makemytrip", "goibibo", "booking", "oyo", "airbnb", "ticket"),
    listOf("shop", "shopping", "clothes") to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "shopping", "clothes", "shirt", "shoes"),
    listOf("entertain", "movie", "fun") to listOf("netflix", "hotstar", "prime", "spotify", "youtube", "movie", "cinema", "pvr", "inox", "bookmyshow", "game", "games"),
    listOf("health", "medical", "medicine", "pharmacy") to listOf("pharmacy", "medical", "medicine", "chemist", "apollo", "doctor", "hospital", "clinic", "lab", "tablet", "tablets"),
    listOf("education", "study", "books", "school") to listOf("school", "college", "fees", "tuition", "course", "udemy", "book", "books", "notebook", "stationery", "pen"),
    listOf("rent") to listOf("rent", "landlord", "pg"),
    listOf("salary", "income") to listOf("salary", "payroll", "stipend", "wages"),
    listOf("loan", "emi") to listOf("emi", "loan", "installment", "instalment"),
    listOf("insurance") to listOf("insurance", "lic", "premium", "policy"),
    listOf("invest", "savings", "stock", "mutual") to listOf("zerodha", "groww", "sip", "mutual", "stocks", "fd", "ppf", "invest"),
    listOf("gift", "donation") to listOf("gift", "gifts", "donation", "temple", "charity"),
    listOf("repair", "service") to listOf("repair", "repairs", "mechanic", "service", "plumber", "electrician")
)

/** Lower-case words of a text, letters and digits only. */
private fun wordsOf(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** Escapes % and _ so a title can be used inside a LIKE pattern (the DAO query uses '\\' as escape). */
private fun likeEscape(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** The tags to switch on for [title] / [note]; empty when nothing fits. */
suspend fun suggestTagIds(
    dao: TransactionDao,
    title: String,
    note: String,
    tags: List<TagEntity>
): Set<Long> {
    val cleanTitle = title.trim()
    if (cleanTitle.length < 3 || tags.isEmpty()) return emptySet()
    val liveIds = tags.map { it.id }.toSet()

    // 1. History: the same title, then the same first words ("Jaku Filling Station ksk3" ~ "Jaku Filling Station")
    val titleWords = cleanTitle.split(Regex("\\s+"))
    val patterns = buildList {
        add(likeEscape(cleanTitle))
        if (titleWords.size >= 2) add(likeEscape(titleWords.take(2).joinToString(" ")) + "%")
        else if (titleWords[0].length >= 4) add(likeEscape(titleWords[0]) + "%")
    }
    for (pattern in patterns) {
        val total = dao.countTitleLike(pattern)
        if (total == 0) continue
        val top = dao.tagUsageForTitleLike(pattern).firstOrNull { it.tagId in liveIds } ?: continue
        // Wins when at least 60% of the matching transactions used it
        if (top.uses * 10 >= total * 6) return setOf(top.tagId)
    }

    // 2. Keywords and the tag's own name
    val words = wordsOf("$cleanTitle $note")
    if (words.isEmpty()) return emptySet()
    val wordSet = words.toSet()
    fun hit(keyword: String) = keyword in wordSet || (keyword.length >= 5 && words.any { it.startsWith(keyword) })

    val scored = tags.mapNotNull { tag ->
        val nameWords = wordsOf(tag.name).filter { it.length >= 2 }
        var score = 0
        // The tag's own name (or its stem) is in the text
        if (nameWords.any { nw -> nw.length >= 3 && hit(nw.removeSuffix("s")) }) score += 3
        // A keyword group that points at this tag
        for ((nameHints, keywords) in TAG_KEYWORDS) {
            val pointsHere = nameWords.any { nw -> nameHints.any { h -> nw == h || (h.length >= 4 && nw.startsWith(h)) } }
            if (pointsHere && keywords.any { hit(it) }) score += 2
        }
        if (score > 0) tag.id to score else null
    }
    val best = scored.maxOfOrNull { it.second } ?: return emptySet()
    return scored.filter { it.second == best }.map { it.first }.take(2).toSet()
}
