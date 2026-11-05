package com.pawmiaw.business.stock

import java.text.Normalizer

//
//  StockAllergyMatch.kt (iOS StockAllergyMatch.swift)
//
//  The allergy cross-check of the prescription sheet (web lib/allergy-match.ts,
//  the same rules as PawMiaw's family app AllergyMatch.swift): a best-effort,
//  name-based heads-up when a medicine looks like a recorded allergy trigger
//  on the pet's chart. Not a clinical interaction checker: matching is on
//  names, with a small curated alias map for the common drug families and food
//  groups, in English and Spanish (Mexico). Its words say "check it", never
//  "safe" or "unsafe". Keep the lists identical to the web's.
//

/** An active allergy on the chart. */
data class StockAllergySource(
    val trigger: String,
    var reaction: String? = null,
    var petName: String? = null,
    var recordId: String? = null,
)

/** A trigger that looks like it matches the medicine. */
data class StockAllergyConflict(
    val trigger: String,
    val reaction: String?,
    /** Whether the match was literal (`name`) or expanded through the alias map. */
    val via: Via,
) {
    /** Swift `enum Via { case name, related }` (a sealed class here: an enum entry can't be called `name`). */
    sealed class Via {
        data object name : Via()
        data object related : Via()
    }
}

object StockAllergyMatch {
    // Dosage, form, and filler words that should never count as an allergen
    // match, in English and in Spanish (Mexico). Written as `normalize` leaves
    // them: lowercase, no accents. Keep this list and the groups below identical
    // to pawmiaw-web `src/lib/allergy-match.ts` (`scripts/check-allergy-match.sh`
    // compares them and runs the shared cases).
    val noiseTokens: Set<String> = setOf(
        "mg", "ml", "g", "kg", "mcg", "iu", "l",
        "tablet", "tablets", "tab", "tabs", "capsule", "capsules", "cap", "caps",
        "drop", "drops", "chew", "chews", "chewable", "pill", "pills",
        "oral", "otic", "ophthalmic", "injection", "inj", "topical", "spray",
        "solution", "suspension", "ointment", "cream", "gel", "powder", "liquid",
        "bottle", "tube", "bag", "can", "pouch", "sachet", "jar", "box",
        "dose", "doses", "daily", "formula", "flavor", "flavour", "flavored",
        "treat", "treats", "food", "kibble", "meal", "diet", "recipe", "blend",
        "sensitivity", "sensitivities", "allergy", "allergic", "intolerance",
        "the", "and", "for", "with", "plus", "of", "a", "an",
        // Spanish: the same forms, doses and fillers.
        "tableta", "tabletas", "comprimido", "comprimidos", "capsula", "capsulas",
        "gota", "gotas", "masticable", "masticables", "pastilla", "pastillas", "pildora", "pildoras",
        "otico", "otica", "oftalmico", "oftalmica", "inyeccion", "inyectable", "topico", "topica", "aerosol",
        "solucion", "unguento", "crema", "polvo", "liquido",
        "botella", "frasco", "tubo", "bolsa", "lata", "sobre", "caja",
        "dosis", "diario", "diaria", "diarios", "diarias", "sabor",
        "premio", "premios", "golosina", "golosinas", "alimento", "comida", "croqueta", "croquetas",
        "receta", "dieta", "mezcla", "carne",
        "sensibilidad", "sensibilidades", "alergia", "alergias", "alergico", "alergica", "intolerancia",
        "el", "la", "los", "las", "un", "una", "de", "del", "al", "y", "e", "o", "u", "con", "para", "en", "por", "sin", "mas",
    )

    // Conservative, extensible groups. A trigger in any group flags candidates
    // that contain a sibling term. Drug families tie generics to the class. Each
    // group has its Spanish (Mexico) words, so an allergy written "pollo" flags
    // "Chicken kibble" and the other way round ("carne", like "meal", is a
    // filler: "carne de res" is "res"). No "suero": in a clinic it is an IV
    // fluid far more often than whey.
    val aliasGroups: List<List<String>> = listOf(
        // Foods
        listOf("chicken", "poultry", "hen", "fowl", "pollo", "gallina", "ave", "aves"),
        listOf("beef", "cattle", "cow", "bovine", "res", "vaca", "vacuno", "bovino", "ternera", "ternero"),
        listOf("pork", "pig", "porcine", "ham", "bacon", "cerdo", "puerco", "porcino", "jamon", "tocino"),
        listOf("lamb", "mutton", "sheep", "cordero", "borrego", "carnero", "oveja", "ovino"),
        listOf("fish", "salmon", "tuna", "whitefish", "trout", "cod", "sardine", "pescado", "pescados", "pez", "atun", "trucha", "bacalao", "sardina", "sardinas"),
        listOf("seafood", "shellfish", "shrimp", "prawn", "crab", "lobster", "marisco", "mariscos", "camaron", "camarones", "cangrejo", "langosta", "langostino", "langostinos"),
        listOf("dairy", "milk", "lactose", "cheese", "whey", "casein", "lacteo", "lacteos", "leche", "lactosa", "queso", "quesos", "caseina", "yogur", "yogurt"),
        listOf("egg", "eggs", "albumin", "huevo", "huevos", "albumina"),
        listOf("wheat", "gluten", "grain", "grains", "trigo", "grano", "granos"),
        listOf("soy", "soya", "soybean", "soja"),
        listOf("corn", "maize", "maiz", "elote"),
        listOf("peanut", "peanuts", "cacahuate", "cacahuates", "cacahuete", "cacahuetes", "mani"),
        listOf("nut", "nuts", "almond", "cashew", "walnut", "hazelnut", "nuez", "nueces", "almendra", "almendras", "anacardo", "anacardos", "avellana", "avellanas"),
        // Drug families (generic ↔ class)
        listOf("penicillin", "penicillins", "amoxicillin", "ampicillin", "cloxacillin", "clavulanate", "clavamox", "amoxiclav", "penicilina", "penicilinas", "amoxicilina", "ampicilina", "cloxacilina", "clavulanato", "clavulanico"),
        listOf("cephalosporin", "cephalosporins", "cephalexin", "cefovecin", "cefpodoxime", "convenia", "cefalosporina", "cefalosporinas", "cefalexina", "cefovecina", "cefpodoxima"),
        listOf("sulfa", "sulfonamide", "sulfamethoxazole", "sulfadimethoxine", "trimethoprim", "tmp", "smz", "sulfas", "sulfonamida", "sulfonamidas", "sulfametoxazol", "sulfadimetoxina", "trimetoprima", "trimetoprim"),
        listOf("nsaid", "nsaids", "carprofen", "rimadyl", "meloxicam", "metacam", "firocoxib", "previcox", "deracoxib", "ibuprofen", "aspirin", "robenacoxib", "aine", "aines", "carprofeno", "ibuprofeno", "aspirina", "acetilsalicilico"),
        listOf("tetracycline", "tetracyclines", "doxycycline", "minocycline", "tetraciclina", "tetraciclinas", "doxiciclina", "minociclina"),
        listOf("fluoroquinolone", "fluoroquinolones", "enrofloxacin", "baytril", "marbofloxacin", "ciprofloxacin", "fluoroquinolona", "fluoroquinolonas", "enrofloxacino", "enrofloxacina", "marbofloxacino", "marbofloxacina", "ciprofloxacino", "ciprofloxacina"),
    )

    private val aliasByToken: Map<String, Set<String>> by lazy {
        val map = HashMap<String, Set<String>>()
        for (group in aliasGroups) {
            val set = group.toSet()
            for (term in group) map[term] = set
        }
        map
    }

    /**
     * Lowercase ASCII letters and digits separated by single spaces, the same
     * as the web's `normalizeAllergyText`: compatibility-decomposed (NFKD),
     * accents dropped ("Salmón" → "salmon", "piña" → "pina"), anything else a space.
     */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        val scalars = StringBuilder()
        var index = 0
        while (index < decomposed.length) {
            val point = decomposed.codePointAt(index)
            if (point !in 0x0300..0x036F) scalars.appendCodePoint(point)
            index += Character.charCount(point)
        }
        val lowered = scalars.toString().lowercase()
        val cleaned = StringBuilder()
        index = 0
        while (index < lowered.length) {
            val point = lowered.codePointAt(index)
            cleaned.append(if (point in 0x61..0x7A || point in 0x30..0x39) point.toChar() else ' ')
            index += Character.charCount(point)
        }
        return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Meaningful, normalized tokens (dosage/form/filler words and bare numbers dropped). */
    fun tokenize(value: String): List<String> {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(' ').filter { token ->
            token.isNotEmpty() && token !in noiseTokens && !token.all { it.isDigit() }
        }
    }

    private fun expand(token: String): Set<String> = aliasByToken[token] ?: setOf(token)

    // A trigger token is present in the candidate if the candidate has the token
    // itself or any of its aliases. Single-character tokens require an exact match.
    private fun candidateHasToken(candidateTokens: Set<String>, token: String): Pair<Boolean, Boolean> {
        if (token in candidateTokens) return Pair(true, false)
        if (token.length <= 1) return Pair(false, false)
        for (variant in expand(token)) {
            if (variant != token && variant in candidateTokens) return Pair(true, true)
        }
        return Pair(false, false)
    }

    /**
     * Allergies whose trigger looks like it matches `candidateName`. A multi-word
     * trigger must have all of its meaningful tokens present; a single-word
     * trigger may match through the alias map. De-duplicated by record/trigger.
     */
    fun findConflicts(candidateName: String?, allergies: List<StockAllergySource>): List<StockAllergyConflict> {
        val name = (candidateName ?: "").trim()
        if (name.isEmpty() || allergies.isEmpty()) return emptyList()

        val candidateTokens = tokenize(name).toSet()
        if (candidateTokens.isEmpty()) return emptyList()

        val conflicts = mutableListOf<StockAllergyConflict>()
        val seen = HashSet<String>()

        for (allergy in allergies) {
            val triggerTokens = tokenize(allergy.trigger)
            if (triggerTokens.isEmpty()) continue

            var allPresent = true
            var viaAlias = false
            for (token in triggerTokens) {
                val (hit, alias) = candidateHasToken(candidateTokens, token)
                if (!hit) { allPresent = false; break }
                if (alias) viaAlias = true
            }
            if (!allPresent) continue

            val key = allergy.recordId ?: "${allergy.petName ?: ""}:${normalize(allergy.trigger)}"
            if (key in seen) continue
            seen.add(key)

            conflicts.add(
                StockAllergyConflict(
                    trigger = allergy.trigger,
                    reaction = allergy.reaction,
                    via = if (viaAlias) StockAllergyConflict.Via.related else StockAllergyConflict.Via.name,
                ),
            )
        }

        return conflicts
    }
}
