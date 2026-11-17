package com.manu.txnkeeper

/*
 * Every tag starts with an emoji ("🛒 Groceries"). These helpers make sure of that: they find the emoji
 * a name starts with, add a fitting one when it has none, and keep the emoji from being cut in half.
 * Plain Kotlin only (no Android or Compose), so it is easy to unit test.
 */

/** Used when a tag name gives no hint of what it is about. */
const val DEFAULT_TAG_EMOJI = "🏷️"

/**
 * Emoji that many fonts draw as a flat black symbol unless they are followed by the "emoji style"
 * character (U+FE0F). Names that start with one of these get it added if it is missing.
 */
private val NEEDS_EMOJI_STYLE = setOf(
    0x2708,   // ✈ plane
    0x1F6CD,  // 🛍 shopping bags
    0x1F3CB,  // 🏋 weight lifter
    0x1F6E1,  // 🛡 shield
    0x1F3F7,  // 🏷 label
    0x1F6E0,  // 🛠 tools
    0x2764,   // ❤ heart
    0x2699,   // ⚙ gear
    0x2702,   // ✂ scissors
    0x260E,   // ☎ phone
    0x2600,   // ☀ sun
    0x2601    // ☁ cloud
)

/**
 * Which emoji goes with which words, checked in this order (the first match wins). A word matches a
 * tag name word when they are equal, or when the name word starts with it and it has 4 or more letters
 * ("grocer" matches "groceries"; "pet" must match "pet" exactly so it is not found inside "petrol").
 */
private val EMOJI_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "🛒" to listOf("grocer", "grocery", "vegetable", "fruit", "milk", "kirana", "supermarket", "mart"),
    "🐶" to listOf("pet", "pets", "dog", "cat", "vet"),
    "⛽" to listOf("fuel", "petrol", "diesel", "gas", "cng"),
    "☕" to listOf("coffee", "tea", "cafe", "chai"),
    "🍕" to listOf("dining", "restaurant", "pizza", "dinner", "lunch", "takeaway"),
    "🍔" to listOf("food", "breakfast", "meal", "snack", "swiggy", "zomato", "burger", "eat", "eating"),
    "💡" to listOf("electric", "electricity", "power", "light", "water"),
    "📶" to listOf("internet", "wifi", "broadband", "fibre", "fiber"),
    "📱" to listOf("recharge", "mobile", "phone", "prepaid", "postpaid", "data"),
    "🧾" to listOf("bill", "utility", "utilities", "tax", "taxes", "receipt", "invoice"),
    "🏠" to listOf("rent", "house", "home", "maintenance", "society"),
    "🔧" to listOf("repair", "service", "plumber", "mechanic"),
    "🚗" to listOf("transport", "taxi", "cab", "uber", "ola", "bus", "metro", "auto", "parking", "toll", "car"),
    "✈️" to listOf("travel", "trip", "flight", "holiday", "vacation", "tour"),
    "🏨" to listOf("hotel", "stay", "resort", "hostel"),
    "🎬" to listOf("movie", "entertainment", "cinema", "netflix", "music", "show", "subscription"),
    "🎮" to listOf("game", "gaming", "playstation", "xbox"),
    "🛍️" to listOf("shopping", "shop", "mall", "amazon", "flipkart", "online"),
    "👕" to listOf("cloth", "clothes", "fashion", "shoe", "shoes", "dress"),
    "🎁" to listOf("gift", "present"),
    "💄" to listOf("beauty", "salon", "makeup", "spa", "haircut"),
    "💊" to listOf("health", "medicine", "medical", "doctor", "pharmacy", "hospital", "dentist", "clinic"),
    "🏋️" to listOf("gym", "fitness", "sport", "sports", "yoga", "workout"),
    "📚" to listOf("education", "school", "college", "course", "book", "books", "tuition", "fees", "study"),
    "👶" to listOf("kid", "kids", "child", "children", "baby", "toys"),
    "💸" to listOf("loan", "emi", "debt", "credit", "interest"),
    "📈" to listOf("investment", "invest", "stock", "stocks", "mutual", "sip", "shares", "crypto"),
    "💰" to listOf("saving", "savings", "deposit", "fund"),
    "💼" to listOf("salary", "income", "wage", "wages", "bonus", "freelance", "business", "office", "work"),
    "🛡️" to listOf("insurance", "premium", "policy"),
    "🙏" to listOf("donation", "donate", "charity", "temple", "church", "gurdwara", "mosque"),
    "🎉" to listOf("party", "birthday", "celebration", "wedding", "festival")
)

private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

/** True for a symbol that is normally drawn as an emoji (pictographs, flags, dingbats, stars...). */
private fun isEmojiBase(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            cp in 0x231A..0x23FF ||
            cp == 0x24C2 || cp == 0x2934 || cp == 0x2935

/**
 * How many characters of [s] belong to the emoji it starts with (0 when it does not start with
 * one). A whole emoji counts, even when it is several characters: a skin tone, the "emoji style"
 * character, a joined family or profession emoji, a pair of flag letters, a keycap.
 */
fun leadingEmojiLength(s: String): Int {
    if (s.isEmpty()) return 0
    val first = s.codePointAt(0)

    if (isRegionalIndicator(first)) {
        var end = Character.charCount(first)
        if (end < s.length && isRegionalIndicator(s.codePointAt(end))) end += Character.charCount(s.codePointAt(end))
        return end
    }

    // Keycap: 1️⃣  (digit, optional emoji-style character, then U+20E3)
    if (first in '0'.code..'9'.code || first == '#'.code || first == '*'.code) {
        var end = 1
        if (end < s.length && s[end] == '️') end++
        return if (end < s.length && s[end] == '⃣') end + 1 else 0
    }

    if (!isEmojiBase(first)) return 0
    var end = Character.charCount(first)
    while (end < s.length) {
        val cp = s.codePointAt(end)
        if (cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF) {
            end += Character.charCount(cp)
        } else if (cp == 0x200D && end + 1 < s.length && isEmojiBase(s.codePointAt(end + 1))) {
            end += 1 + Character.charCount(s.codePointAt(end + 1))
        } else {
            break
        }
    }
    return end
}

fun hasLeadingEmoji(name: String): Boolean = leadingEmojiLength(name) > 0

/** The name without the emoji at its start: "🛒 Groceries" -> "Groceries". */
fun stripLeadingEmoji(name: String): String = name.substring(leadingEmojiLength(name)).trim()

/** Adds the "emoji style" character to an emoji that needs it and does not have it yet. */
private fun withEmojiStyle(emoji: String): String {
    if (emoji.isEmpty() || '️' in emoji) return emoji
    val first = emoji.codePointAt(0)
    if (first !in NEEDS_EMOJI_STYLE) return emoji
    val n = Character.charCount(first)
    return emoji.substring(0, n) + "️" + emoji.substring(n)
}

/** The emoji that fits [text] best, or [DEFAULT_TAG_EMOJI] when no word is recognised. */
fun emojiForTagName(text: String): String {
    val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return DEFAULT_TAG_EMOJI
    for ((emoji, keys) in EMOJI_KEYWORDS) {
        for (key in keys) {
            if (words.any { w -> w == key || (key.length >= 4 && w.startsWith(key)) }) return emoji
        }
    }
    return DEFAULT_TAG_EMOJI
}

/** Cuts [text] to [limit] characters without leaving half of a two-character symbol at the end. */
private fun cutText(text: String, limit: Int): String {
    if (text.length <= limit) return text
    var end = limit.coerceAtLeast(1)
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end).trim()
}

/**
 * The tag name as it is saved: tidy spaces, the emoji first, then a space, then the words, no longer
 * than [maxLength] characters in all. If the name has no emoji, one is picked from its words (see
 * [emojiForTagName]). A blank name stays blank. A name that is only an emoji comes back as that emoji.
 * Doing it twice changes nothing.
 */
fun withTagEmoji(raw: String, maxLength: Int): String {
    val spaced = raw.trim().replace(Regex("\\s+"), " ")
    if (spaced.isEmpty()) return ""
    val emojiLen = leadingEmojiLength(spaced)
    val emoji: String
    val text: String
    if (emojiLen > 0) {
        emoji = withEmojiStyle(spaced.substring(0, emojiLen))
        text = spaced.substring(emojiLen).trim()
    } else {
        text = spaced
        emoji = emojiForTagName(text)
    }
    if (text.isEmpty()) return emoji
    val room = maxLength - emoji.length - 1
    return "$emoji ${cutText(text, room)}".trim()
}
