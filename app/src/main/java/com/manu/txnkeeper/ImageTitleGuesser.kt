package com.manu.txnkeeper

/*
 * Works out what a photographed product is, to use as the transaction title. Three sources:
 *   1. The product name printed on the pack: the most prominent text line(s), with the small print
 *      (MRP, batch, dates, weight, ingredients, address, barcode digits ...) left out.
 *   2. A product-type word found in the text ("soap", "notebook", "perfume" ...). It names the product
 *      when no clear name was found, and completes a one-word name ("Dove" -> "Dove Soap").
 *   3. ML Kit image labels, for things with no printed text. Labels that describe the photo or the
 *      picture printed on a pack (toy, poster, cartoon ...) are skipped.
 * A sure result fills the title; unsure ones (and the other candidates) come back as suggestions.
 * Plain Kotlin only, so it is easy to unit test.
 */
object ImageTitleGuesser {

    /** [title] fills the title field (null when unsure); [suggestions] are shown as tappable chips. */
    class Guess(val title: String?, val suggestions: List<String>)

    /** A label this sure fills the title by itself; a less sure one is only suggested. */
    private const val LABEL_FILL_CONFIDENCE = 0.85f

    // ---------------------------------------------------------------- product type words

    /** Title to use, and the words on a product that point to it (first match in this order wins). */
    private val PRODUCT_WORDS: List<Pair<String, List<String>>> = listOf(
        "Notebook" to listOf("notebook", "note book", "exercise book", "long book", "ruled", "single line", "four line", "diary"),
        "Perfume" to listOf("perfume", "parfum", "eau de", "cologne", "fragrance", "body spray", "attar"),
        "Deodorant" to listOf("deodorant", "deo "),
        "Shampoo" to listOf("shampoo", "conditioner", "hair oil"),
        "Soap" to listOf("soap", "body wash", "handwash", "hand wash"),
        "Toothpaste" to listOf("toothpaste", "tooth paste", "toothbrush"),
        "Face cream" to listOf("face wash", "moisturi", "sunscreen", "lotion", "face cream", "serum"),
        "Medicine" to listOf("tablets", "capsules", "syrup", "ointment", "i.p.", "dosage", "pharma"),
        "Pen" to listOf("ball pen", "gel pen", "ballpoint", "refill"),
        "Pencil" to listOf("pencil", "eraser", "sharpener"),
        "Biscuits" to listOf("biscuit", "cookies"),
        "Snacks" to listOf("chips", "namkeen", "wafers", "bhujia", "kurkure"),
        "Chocolate" to listOf("chocolate", "cocoa"),
        "Cold drink" to listOf("soft drink", "carbonated", "cola", "soda"),
        "Water bottle" to listOf("mineral water", "drinking water", "packaged water"),
        "Detergent" to listOf("detergent", "washing powder", "dishwash", "dish wash", "floor cleaner"),
        "Batteries" to listOf("battery", "batteries", "alkaline"),
        "Charger" to listOf("charger", "usb cable", "adapter"),
        "Bulb" to listOf("led bulb", "bulb", "tube light")
    )

    /** The product type that words in [text] point to ("Soap", "Notebook" ...), or null. */
    fun typeFromText(text: String): String? {
        val flat = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}.]+"), " ") + " "
        return PRODUCT_WORDS.firstOrNull { (_, words) -> words.any { flat.contains(it) } }?.first
    }

    // ---------------------------------------------------------------- the name printed on the pack

    /** Words that mark small print or a payment screen: such a line is never the product name. */
    private val JUNK_WORDS = listOf(
        "mrp", "m.r.p", "batch", "b.no", "mfg", "mfd", "manufactured", "marketed", "packed", "exp", "expiry",
        "best before", "use by", "net ", "qty", "quantity", "weight", " wt", "ingredients", "contains",
        "address", "customer care", "toll free", "helpline", "www.", ".com", "@", "fssai", "lic.", "licence",
        "inclusive of", "taxes", "pack of", "regd", "made in", "tel", "phone", "email", "pin ", "nutrition",
        "per 100", "directions", "warning", "storage", "store in", "keep away", "barcode", "scan", "upi",
        "paid", "successful", "transaction", "debited", "credited", "a/c", "account", "bank", "ref no", "rs.", "₹"
    )

    private fun isJunk(line: String): Boolean {
        val lower = " " + line.lowercase() + " "
        if (JUNK_WORDS.any { lower.contains(it) }) return true
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        return letters < 3 || digits > letters / 2 || line.length > 40
    }

    /** "DOVE CREAM BEAUTY BAR" -> "Dove Cream Beauty Bar"; mixed-case text is left as it is. */
    private fun tidy(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ").trim('-', '.', ',', ':', '*', '|')
        val letters = clean.filter { it.isLetter() }
        return if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            clean.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        } else clean
    }

    /** The product name: the most prominent clean line, joined with a neighbour of similar size. */
    fun nameFromLines(lines: List<OcrLine>): String? {
        val clean = lines.filter { it.height > 0 && !isJunk(it.text) }
        val main = clean.maxByOrNull { it.height } ?: return null
        // Too small to be a name (fine print only)
        val tallest = lines.maxOfOrNull { it.height } ?: return null
        if (main.height < tallest * 0.5) return null
        // A line right above or below, nearly as big, is usually the rest of the name ("Dove" / "Cream Beauty Bar")
        val partner = clean
            .filter { it !== main && it.height >= main.height * 0.8 }
            .filter { kotlin.math.abs(it.top - main.top) <= main.height * 2.2 }
            .minByOrNull { kotlin.math.abs(it.top - main.top) }
        val parts = listOfNotNull(main, partner).sortedBy { it.top }.map { it.text }
        return tidy(parts.joinToString(" ")).takeIf { it.length >= 3 }
    }

    // ---------------------------------------------------------------- image labels

    /** Labels that describe the photo or the picture printed on a pack, not the thing bought. */
    private val TOO_GENERIC = setOf(
        // the photo itself
        "text", "font", "paper", "paper product", "material property", "product", "rectangle", "pattern",
        "line", "document", "handwriting", "writing", "number", "screenshot", "technology", "gadget",
        "electronic device", "room", "floor", "table", "wood", "hand", "finger", "skin", "textile",
        "darkness", "light", "sky", "metal", "plastic", "paper towel", "tints and shades", "black",
        "white", "red", "blue", "green", "yellow", "circle", "symmetry", "art", "design", "brand",
        // pictures printed on packs (cartoons, posters, characters)
        "toy", "poster", "cartoon", "animation", "illustration", "fictional character", "sticker",
        "mural", "painting", "comics", "graphics", "drawing", "visual arts", "fun", "doll", "mascot",
        "action figure", "stuffed toy", "clip art", "fiction", "advertising", "label", "logo",
        "packaging and labeling", "packaging", "box", "carton", "cardboard", "bag", "pink", "purple",
        "orange", "brown", "grey", "gray", "person", "face", "smile", "cool"
    )

    /** Useful labels with their confidence, best first. */
    private fun usefulLabels(labels: List<Pair<String, Float>>): List<Pair<String, Float>> =
        labels
            .map { it.first.trim() to it.second }
            .filter { it.first.isNotEmpty() && it.first.lowercase() !in TOO_GENERIC }
            .sortedByDescending { it.second }

    // ---------------------------------------------------------------- putting it together

    /**
     * The title to fill (when sure) and up to three other candidates for the suggestion chips.
     * [lines] are the OCR lines of the photo, [labels] the (label, confidence) pairs from image labeling.
     */
    fun guess(lines: List<OcrLine>, labels: List<Pair<String, Float>>): Guess {
        val name = nameFromLines(lines)
        val type = typeFromText(lines.joinToString(" ") { it.text })
        val useful = usefulLabels(labels)

        // A one-word name gets its type ("Dove" + Soap); a longer name already says what it is
        val fromText = when {
            name != null && type != null && name.trim().split(" ").size == 1 &&
                !name.contains(type, ignoreCase = true) -> "$name $type"
            name != null -> name
            else -> type
        }
        val sureLabel = useful.firstOrNull { it.second >= LABEL_FILL_CONFIDENCE }?.first

        val title = fromText ?: sureLabel
        val candidates = buildList {
            if (name != null) add(name)
            if (type != null) add(type)
            useful.take(3).forEach { add(it.first) }
        }
        val suggestions = candidates
            .distinctBy { it.lowercase() }
            .filter { !it.equals(title, ignoreCase = true) }
            .take(3)
        return Guess(title, suggestions)
    }
}
