package com.pawmiaw.business.money

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import kotlin.math.roundToInt

//
//  CashCatalogCSV.kt (iOS CashCatalogCSV.swift)
//
//  The Catalog as a spreadsheet, both ways (web: lib/catalog-csv.ts,
//  catalog-page/catalog-import-sheet.tsx): every item as a CSV row, and a CSV
//  of new services and products read back and checked row by row before
//  anything is added. A row already in the catalog (its SKU, or its name among
//  items of its kind) is skipped, never changed; packs are added by hand. The
//  file is written in the reader's language and read in either; Excel's
//  habits are allowed for (a byte-order mark, `;` or tabs, amounts with a comma).
//

object CashCatalogCSV {
    val columns = listOf(
        "kind", "name", "category", "sku", "barcode", "price", "tax", "taxRate", "description", "active",
        "productKind", "prescription", "trackStock", "onHand", "reorderPoint", "unitCost", "packOf", "packUnits",
    )

    /** Each column's header (en, es). */
    val headers: Map<String, Pair<String, String>> = linkedMapOf(
        "kind" to ("Kind" to "Tipo"), "name" to ("Name" to "Nombre"), "category" to ("Category" to "Categoría"), "sku" to ("SKU" to "SKU"),
        "barcode" to ("Barcode" to "Código de barras"), "price" to ("Price" to "Precio"), "tax" to ("Tax" to "Impuesto"), "taxRate" to ("Own tax rate %" to "Tasa propia %"),
        "description" to ("Description" to "Descripción"), "active" to ("Active" to "Activo"), "productKind" to ("Kind of product" to "Tipo de producto"),
        "prescription" to ("Prescription only" to "Solo con receta"), "trackStock" to ("Track stock" to "Llevar inventario"), "onHand" to ("Quantity on hand" to "Existencia"),
        "reorderPoint" to ("Reorder point" to "Punto de reorden"), "unitCost" to ("Unit cost" to "Costo unitario"), "packOf" to ("Pack of" to "Empaque de"), "packUnits" to ("Units per pack" to "Unidades por empaque"),
    )
    val kindWords: Map<String, Pair<String, String>> = linkedMapOf("service" to ("Service" to "Servicio"), "product" to ("Product" to "Producto"))
    val taxWords: Map<String, Pair<String, String>> = linkedMapOf("default" to ("Standard" to "Estándar"), "standard" to ("Own rate" to "Tasa propia"), "zero" to ("0%" to "0%"), "exempt" to ("Exempt" to "Exento"))
    val productKindWords: Map<String, Pair<String, String>> = linkedMapOf(
        "medication" to ("Medication" to "Medicamento"), "food" to ("Food" to "Alimento"), "supply" to ("Supply" to "Insumo"),
        "accessory" to ("Accessory" to "Accesorio"), "other" to ("Other" to "Otro"),
    )

    const val maxRows = 500

    val spanish: Boolean get() = LocalizationManager.currentLanguage == AppLanguage.spanish
    fun word(pair: Pair<String, String>): String = if (spanish) pair.second else pair.first

    /** Lower-case, accent-free, single-spaced: how headers, words and names are compared. */
    fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** One cell; a text a spreadsheet would run as a formula is written with a leading apostrophe. */
    fun cell(value: String): String {
        var text = value
        val first = text.firstOrNull()
        if (first != null && "=+-@\t\r".contains(first)) text = "'$text"
        if (text.any { "\",;\n\r".contains(it) }) return "\"" + text.replace("\"", "\"\"") + "\""
        return text
    }

    // MARK: Export

    fun export(items: List<CashCatalogItem>, all: List<CashCatalogItem>): String {
        val names = HashMap<String, String>()
        for (item in all) if (!names.containsKey(item.id)) names[item.id] = item.name
        val yes = if (spanish) "Sí" else "Yes"
        val no = "No"
        val rows = items.map { item ->
            val product = item.isProduct
            val pack = item.isPack
            val stock = if (product && !pack && item.trackInventory) item.inventory else null
            val values: Map<String, String> = mapOf(
                "kind" to word(kindWords[if (product) "product" else "service"]!!),
                "name" to item.name,
                "category" to (item.category?.name ?: ""),
                "sku" to (item.sku ?: ""),
                "barcode" to (item.inventory?.barcode ?: ""),
                "price" to (if (item.defaultUnitAmount > 0) CashCSV.money(item.defaultUnitAmount) else ""),
                "tax" to word(taxWords[item.taxTreatment] ?: taxWords["default"]!!),
                "taxRate" to (if (item.taxTreatment == "standard") CashCatalogRules.rateText(item.taxRateBps) else ""),
                "description" to (item.description ?: ""),
                "active" to (if (item.active) yes else no),
                "productKind" to (if (product) (item.productKind?.let { productKindWords[it] }?.let { word(it) } ?: "") else ""),
                "prescription" to (if (product) (if (item.requiresPrescription) yes else no) else ""),
                "trackStock" to (if (product) (if (item.trackInventory && !pack) yes else no) else ""),
                "onHand" to (stock?.let { "${it.quantityOnHand}" } ?: ""),
                "reorderPoint" to (stock?.let { "${it.reorderPoint}" } ?: ""),
                "unitCost" to (stock?.unitCostAmount?.let { CashCSV.money(it) } ?: ""),
                "packOf" to (if (pack) (item.stockCatalogItemId?.let { names[it] } ?: "") else ""),
                "packUnits" to (if (pack) "${item.stockUnits ?: 1}" else ""),
            )
            columns.joinToString(",") { cell(values[it] ?: "") }
        }
        return (listOf(columns.joinToString(",") { cell(word(headers[it]!!)) }) + rows).joinToString("\r\n")
    }

    fun exportName(): String = "${if (spanish) "catalogo" else "catalog"}-${DateText.dayKey()}.csv"

    /** A file to start an import from: the columns an import reads, with two rows to write over. */
    fun template(): String {
        val cols = listOf("kind", "name", "category", "sku", "barcode", "price", "tax", "taxRate", "description", "productKind", "trackStock", "onHand", "reorderPoint", "unitCost")
        val examples: List<Map<String, String>> = if (spanish) {
            listOf(
                mapOf("kind" to "Servicio", "name" to "Consulta general", "category" to "Consultas", "price" to "450.00", "tax" to "Estándar"),
                mapOf("kind" to "Producto", "name" to "Croquetas 2 kg", "category" to "Alimento", "sku" to "CR-2KG", "price" to "320.00", "tax" to "Estándar", "productKind" to "Alimento", "trackStock" to "Sí", "onHand" to "12", "reorderPoint" to "4", "unitCost" to "210.00"),
            )
        } else {
            listOf(
                mapOf("kind" to "Service", "name" to "Wellness exam", "category" to "Consults", "price" to "45.00", "tax" to "Standard"),
                mapOf("kind" to "Product", "name" to "Dog food 2 kg", "category" to "Food", "sku" to "DF-2KG", "price" to "32.00", "tax" to "Standard", "productKind" to "Food", "trackStock" to "Yes", "onHand" to "12", "reorderPoint" to "4", "unitCost" to "21.00"),
            )
        }
        return (listOf(cols.joinToString(",") { cell(word(headers[it]!!)) }) + examples.map { row -> cols.joinToString(",") { cell(row[it] ?: "") } }).joinToString("\r\n")
    }

    // MARK: Reading a file

    /** Swift `Character.isNewline` (a "\r\n" pair is one character there; see `rows`). */
    private fun isNewline(char: Char): Boolean =
        char == '\n' || char == '\r' || char == '\u000B' || char == '\u000C' || char == '\u0085' || char == ' ' || char == ' '

    /** Swift `.trimmingCharacters(in: .whitespaces)`: spaces and tabs, not line breaks. */
    private fun String.trimSpaces(): String = trim { it.isWhitespace() && !isNewline(it) }

    /** CSV text as rows of cells; the separator is the one that splits the first line into the most cells. */
    fun parse(raw: String): List<List<String>> {
        val text = if (raw.startsWith("﻿")) raw.drop(1) else raw
        val firstLine = text.split(Regex("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]+")).firstOrNull { it.isNotEmpty() } ?: ""
        var mark = ','
        var most = 0
        for (candidate in listOf(',', ';', '\t')) {
            val cells = rows(firstLine, candidate).firstOrNull()?.size ?: 0
            if (cells > most) {
                most = cells
                mark = candidate
            }
        }
        return rows(text, mark).filter { row -> row.any { it.trimSpaces().isNotEmpty() } }
    }

    private fun rows(text: String, mark: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (quoted) {
                if (char == '"') {
                    if (at + 1 < text.length && text[at + 1] == '"') {
                        value.append('"')
                        at += 1
                    } else {
                        quoted = false
                    }
                } else {
                    value.append(char)
                }
            } else if (char == '"' && value.isEmpty()) {
                quoted = true
            } else if (char == mark) {
                row.add(value.toString())
                value.setLength(0)
            } else if (isNewline(char)) {
                // "\r\n" is one line break, as one Swift character.
                if (char == '\r' && at + 1 < text.length && text[at + 1] == '\n') at += 1
                row.add(value.toString())
                value.setLength(0)
                rows.add(row)
                row = mutableListOf()
            } else {
                value.append(char)
            }
            at += 1
        }
        row.add(value.toString())
        rows.add(row)
        return rows
    }

    /** An amount as written in a spreadsheet: "1,500.00", "1.500,00", "$450", "450 MXN". */
    fun cents(text: String): Int? {
        var body = text.trimSpaces()
        if (body.isEmpty() || !body.any { it.isDigit() }) return null
        for (code in listOf("MXN", "USD", "COP", "EUR", "ARS", "CLP", "PEN", "GTQ", "CRC", "MX$", "US$", "$", "€", "£")) {
            if (body.uppercase().startsWith(code)) body = body.drop(code.length).trimSpaces()
            if (body.uppercase().endsWith(code)) body = body.dropLast(code.length).trimSpaces()
        }
        body = body.replace(" ", "").replace(" ", "").replace("'", "")
        if (body.isEmpty() || !body.all { it.isDigit() || it == '.' || it == ',' }) return null
        val points = body.count { it == '.' }
        val commas = body.count { it == ',' }
        fun grouped(s: String, m: Char): Boolean {
            val parts = s.split(m)
            val head = parts.firstOrNull() ?: return false
            if (head.length !in 1..3) return false
            return parts.drop(1).all { it.length == 3 }
        }
        var whole = body
        var decimals = ""
        if (points > 0 && commas > 0) {
            val decimal = if (body.lastIndexOf('.') > body.lastIndexOf(',')) '.' else ','
            val group = if (decimal == '.') ',' else '.'
            if (body.count { it == decimal } != 1) return null
            val parts = body.split(decimal)
            if (!grouped(parts[0], group)) return null
            whole = parts[0].replace(group.toString(), "")
            decimals = parts[1]
        } else if (points + commas > 1) {
            val m = if (commas > 0) ',' else '.'
            if (!grouped(body, m)) return null
            whole = body.replace(m.toString(), "")
        } else if (points + commas == 1) {
            val m = if (commas > 0) ',' else '.'
            val parts = body.split(m)
            val head = parts[0]
            val tail = parts[1]
            if (m == ',' && tail.length == 3 && head.length in 1..3 && head.first() != '0') {
                whole = head + tail
            } else {
                whole = head
                decimals = tail
            }
        }
        if (whole.isEmpty() && decimals.isEmpty()) return null
        if (decimals.length > 2 || whole.length > 13) return null
        val w = (if (whole.isEmpty()) "0" else whole).toLongOrNull() ?: -1L
        val d = decimals.padEnd(2, '0').toLongOrNull() ?: -1L
        if (w < 0 || d < 0) return null
        // Swift's Int is 64-bit; an amount past Kotlin's Int is not an amount here.
        val total = w * 100 + d
        return if (total > Int.MAX_VALUE) null else total.toInt()
    }

    class Row(
        val line: Int,
        val name: String,
        val itemType: String?,
        var status: Status,
        var problems: MutableList<String> = mutableListOf(),
        var skip: String? = null,
        var notes: MutableList<String> = mutableListOf(),
        var body: Map<String, Any?>? = null,
        var categoryName: String? = null,
        var categoryId: String? = null,
        var price: Int? = null,
    ) {
        enum class Status { add, skip, error }

        val id: Int get() = line
    }

    class Reading(
        var problem: String? = null,
        val rows: MutableList<Row> = mutableListOf(),
        /** (itemType, name) */
        var newCategories: List<Pair<String, String>> = emptyList(),
        var fingerprint: String = "",
    )

    fun categoryKey(itemType: String, name: String): String = "$itemType:${fold(name)}"

    private fun lookup(words: Map<String, Pair<String, String>>, value: String, extra: Map<String, String> = emptyMap()): String? {
        val folded = fold(value)
        if (folded.isEmpty()) return null
        for ((key, pair) in words) {
            if (folded == fold(key) || folded == fold(pair.first) || folded == fold(pair.second)) return key
        }
        return extra[folded]
    }

    private fun yes(value: String, fallback: Boolean): Boolean {
        val folded = fold(value)
        if (folded.isEmpty()) return fallback
        if (folded in listOf("yes", "y", "si", "s", "true", "1", "x")) return true
        if (folded in listOf("no", "n", "false", "0")) return false
        return fallback
    }

    /** (value, valid) */
    private fun whole(value: String): Pair<Int?, Boolean> {
        val text = value.filter { !it.isWhitespace() }
        if (text.isEmpty()) return null to true
        if (text.length > 7 || !text.all { it.isDigit() }) return null to false
        val number = text.toIntOrNull() ?: return null to false
        return number to true
    }

    /** FNV-1a over the UTF-16 units, then the length in hex. */
    private fun fingerprint(text: String): String {
        var hash: UInt = 0x811c9dc5u
        for (unit in text) {
            hash = hash xor unit.code.toUInt()
            hash *= 0x01000193u
        }
        return hash.toString(16).padStart(8, '0') + text.length.toString(16)
    }

    /** The file checked against the catalog; nothing is sent from here. */
    fun read(text: String, items: List<CashCatalogItem>, categories: List<CashCategory>, canEditInventory: Boolean): Reading {
        val table = parse(text)
        val reading = Reading(fingerprint = fingerprint(text.trim()))
        val header = table.firstOrNull()?.map { fold(it) }
        if (header == null) {
            reading.problem = L("That file has no rows to import.")
            return reading
        }
        val at = HashMap<String, Int>()
        for (column in columns) {
            val pair = headers[column]!!
            val index = header.indexOfFirst { it == fold(pair.first) || it == fold(pair.second) || it == fold(column) }
            if (index >= 0) at[column] = index
        }
        val missing = listOf("kind", "name", "price").filter { at[it] == null }
        if (missing.isNotEmpty()) {
            reading.problem = LF("That file is missing these columns: %@. Download the template to see how it is laid out.", missing.joinToString(", ") { word(headers[it]!!) })
            return reading
        }
        if (table.size <= 1) {
            reading.problem = L("That file has no rows to import.")
            return reading
        }
        if (table.size - 1 > maxRows) {
            reading.problem = LF("That file has more than %d rows. Split it and import each part.", maxRows)
            return reading
        }

        val skuOwners = HashMap<String, String>()
        val nameOwners = HashMap<String, String>()
        for (item in items) {
            item.sku.nonEmpty?.let { skuOwners[fold(it)] = item.id }
            nameOwners["${item.itemType}:${fold(item.name)}"] = item.id
        }
        val categoryByKey = HashMap<String, CashCategory>()
        for (category in categories) categoryByKey[categoryKey(category.itemType, category.name)] = category
        val seenSkus = HashSet<String>()
        val seenNames = HashSet<String>()
        val newCategories = HashMap<String, Pair<String, String>>()
        val order = mutableListOf<String>()

        for ((index, cells) in table.drop(1).withIndex()) {
            fun read(column: String): String = at[column]?.let { if (it < cells.size) cells[it] else null }?.trimSpaces() ?: ""
            val name = read("name")
            val itemType = lookup(kindWords, read("kind"), extra = mapOf("servicios" to "service", "productos" to "product", "services" to "service", "products" to "product"))
            val row = Row(line = index + 2, name = name, itemType = itemType, status = Row.Status.add)
            if (name.isEmpty()) row.problems.add(L("It has no name.")) else if (name.codePointCount(0, name.length) > 140) row.problems.add(L("The name is longer than 140 characters."))
            if (itemType == null) row.problems.add(L("The kind must be Service or Product."))
            if (read("packOf").isNotEmpty()) {
                row.status = if (row.problems.isEmpty()) Row.Status.skip else Row.Status.error
                if (row.problems.isEmpty()) row.skip = L("Packs are added by hand: they point at a product.")
                reading.rows.add(row)
                continue
            }
            val sku = read("sku")
            val skuKey = if (sku.isEmpty()) null else fold(sku)
            val nameKey = if (itemType != null && name.isNotEmpty()) "$itemType:${fold(name)}" else null
            if (row.problems.isEmpty()) {
                if (skuKey != null && skuOwners[skuKey] != null) {
                    row.status = Row.Status.skip
                    row.skip = L("Already in the catalog: an item has this SKU.")
                    reading.rows.add(row)
                    continue
                }
                if (nameKey != null && nameOwners[nameKey] != null) {
                    row.status = Row.Status.skip
                    row.skip = L("Already in the catalog: an item of this kind has this name.")
                    reading.rows.add(row)
                    continue
                }
            }
            val priceText = read("price")
            val price: Int? = if (priceText.isEmpty()) 0 else cents(priceText)
            if (price == null) row.problems.add(L("The price is not an amount. Leave it blank or 0 for no price yet."))
            if (sku.codePointCount(0, sku.length) > 80) row.problems.add(L("The SKU is longer than 80 characters."))
            val description = read("description")
            if (description.codePointCount(0, description.length) > 500) row.problems.add(L("The description is longer than 500 characters."))
            val taxText = read("tax")
            val rateText = read("taxRate").replace("%", "").replace(",", ".").trimSpaces()
            var tax = lookup(
                taxWords, taxText,
                extra = mapOf("tasa 0%" to "zero", "zero-rated" to "zero", "zero-rated (0%)" to "zero", "tasa 0" to "zero", "iva" to "default", "estandar" to "default", "own rate" to "standard"),
            )
            if (taxText.isNotEmpty() && tax == null) row.problems.add(L("The tax must be Standard, Own rate, 0% or Exempt."))
            var rateBps: Int? = null
            if (rateText.isNotEmpty()) {
                val percent = rateText.toDoubleOrNull()
                if (percent != null && percent >= 0 && percent <= 100) {
                    if (taxText.isEmpty() || tax == "standard") {
                        tax = "standard"
                        rateBps = (percent * 100).roundToInt()
                    }
                } else {
                    row.problems.add(L("The own tax rate must be a percentage from 0 to 100."))
                }
            } else if (tax == "standard") {
                row.problems.add(L("The own tax rate must be a percentage from 0 to 100."))
            }
            val kindText = read("productKind")
            val productKind = lookup(productKindWords, kindText, extra = mapOf("medicine" to "medication", "medicina" to "medication"))
            if (itemType == "product" && kindText.isNotEmpty() && productKind == null) row.problems.add(L("The kind of product must be Medication, Food, Supply, Accessory or Other."))
            val onHand = whole(read("onHand"))
            val reorder = whole(read("reorderPoint"))
            val costText = read("unitCost")
            val unitCost = if (costText.isEmpty()) null else cents(costText)
            val asksForStock = itemType == "product" && (onHand.first != null || reorder.first != null || costText.isNotEmpty() || read("barcode").isNotEmpty())
            val trackStock = itemType == "product" && yes(read("trackStock"), asksForStock)
            if (itemType == "product" && trackStock) {
                if (!onHand.second) row.problems.add(L("The quantity on hand must be a whole number."))
                if (!reorder.second) row.problems.add(L("The reorder point must be a whole number."))
                if (costText.isNotEmpty() && unitCost == null) row.problems.add(L("The unit cost is not an amount."))
            }
            if (skuKey != null && skuKey in seenSkus) row.problems.add(L("Another row of the file has the same SKU."))
            if (skuKey != null) seenSkus.add(skuKey)
            if (row.problems.isNotEmpty()) {
                row.status = Row.Status.error
                reading.rows.add(row)
                continue
            }
            if (nameKey != null && nameKey in seenNames) {
                row.status = Row.Status.skip
                row.skip = L("Already in the catalog: an item of this kind has this name.")
                reading.rows.add(row)
                continue
            }
            if (nameKey != null) seenNames.add(nameKey)
            if (price == 0) row.notes.add(L("Added with no price yet: it is asked for when charging."))
            val active = yes(read("active"), true)
            if (!active) row.notes.add(L("Added as archived."))
            val categoryName = read("category").take(80).nonEmpty
            if (categoryName != null && itemType != null) {
                val key = categoryKey(itemType, categoryName)
                val existing = categoryByKey[key]
                if (existing != null) {
                    row.categoryId = existing.id
                } else if (newCategories[key] == null) {
                    newCategories[key] = itemType to categoryName
                    order.add(key)
                }
            }
            row.categoryName = categoryName
            row.price = price
            val medication = itemType == "product" && productKind == "medication"
            val body = linkedMapOf<String, Any?>(
                "itemType" to itemType, "name" to name, "sku" to sku.nonEmpty, "defaultUnitAmount" to (price ?: 0), "description" to description.nonEmpty,
                "active" to active, "trackInventory" to trackStock, "productKind" to (if (itemType == "product") productKind else null),
                "taxTreatment" to (tax ?: "default"), "taxRateBps" to (if (tax == "standard") rateBps else null),
                "requiresPrescription" to (if (medication) yes(read("prescription"), false) else false),
            )
            if (trackStock) {
                if (canEditInventory) {
                    body["inventory"] = linkedMapOf<String, Any?>(
                        "barcode" to read("barcode").take(120).nonEmpty,
                        "quantityOnHand" to (onHand.first ?: 0),
                        "reorderPoint" to (reorder.first ?: 0),
                        "unitCostAmount" to unitCost,
                    )
                } else if (asksForStock) {
                    row.notes.add(L("Its stock is left at 0: your role cannot set stock."))
                }
            }
            row.body = body
            reading.rows.add(row)
        }
        reading.newCategories = order.mapNotNull { newCategories[it] }
        return reading
    }
}

// MARK: - The import sheet

/** What an import added, and what it could not (line, name, message). */
private class CashImportOutcome(
    val added: Int,
    val failed: List<Triple<Int, String, String>>,
    val lostCategories: List<String>,
)

/** "Importar artículos": a CSV file chosen, its rows checked, then the new ones added. */
@Composable
fun CashCatalogImportSheet(
    items: List<CashCatalogItem>,
    categories: List<CashCategory>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<CashCatalogCSV.Reading?>(null) }
    val filter = remember { mutableStateOf("all") }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<CashImportOutcome?>(null) }
    var template by remember { mutableStateOf<CashSharedFile?>(null) }

    val rows = reading?.rows ?: emptyList()
    val toAdd = rows.filter { it.status == CashCatalogCSV.Row.Status.add && it.body != null }
    val shown = when (filter.value) {
        "add" -> rows.filter { it.status == CashCatalogCSV.Row.Status.add }
        "skip" -> rows.filter { it.status == CashCatalogCSV.Row.Status.skip }
        "error" -> rows.filter { it.status == CashCatalogCSV.Row.Status.error }
        else -> rows
    }
    /** The web's three steps: the file, the check, what was added. */
    val stepIndex = if (outcome != null) 2 else if (reading != null) 1 else 0

    fun load(uri: Uri) {
        fileError = null
        scope.launch {
            val tooLarge = 2 * 1024 * 1024
            val data: ByteArray? = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            // Past the limit is enough to know.
                            if (out.size() > tooLarge) break
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (data == null) {
                fileError = L("That file could not be read. Save it as CSV and try again.")
                return@launch
            }
            if (data.size > tooLarge) {
                fileError = LF("That file is larger than 2 MB. Import up to %d rows at a time.", CashCatalogCSV.maxRows)
                return@launch
            }
            val text = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString()
            }.getOrNull() ?: String(data, Charsets.ISO_8859_1)
            val next = CashCatalogCSV.read(text, items = items, categories = categories, canEditInventory = workspace.can(Permission.inventoryEdit))
            next.problem?.let {
                fileError = it
                return@launch
            }
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
            }
            fileName = name ?: uri.lastPathSegment ?: ""
            filter.value = if (next.rows.any { it.status == CashCatalogCSV.Row.Status.error }) "error" else "all"
            reading = next
        }
    }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }

    fun add() {
        val current = reading ?: return
        val adding = toAdd
        progress = 0 to adding.size
        scope.launch {
            // The categories the rows name and the catalog lacks come first.
            val made = HashMap<String, String>()
            val lost = mutableListOf<String>()
            for ((itemType, name) in current.newCategories) {
                val created: CashCategory? = try {
                    API.post("/provider-catalog/categories", mapOf("itemType" to itemType, "name" to name, "active" to true))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                if (created != null) made[CashCatalogCSV.categoryKey(itemType, name)] = created.id else lost.add(name)
            }
            var added = 0
            val failed = mutableListOf<Triple<Int, String, String>>()
            for ((index, row) in adding.withIndex()) {
                val body = row.body?.toMutableMap() ?: continue
                body["providerCatalogCategoryId"] = row.categoryId ?: row.categoryName?.let { name -> row.itemType?.let { made[CashCatalogCSV.categoryKey(it, name)] } }
                // The same file sent again finds what it already added.
                body["clientRequestId"] = "import-${current.fingerprint}-${row.line}"
                try {
                    API.post<EmptyResponse>("/provider-catalog", body)
                    added += 1
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failed.add(Triple(row.line, row.name, errorText(error, fallback = "")))
                    val api = error as? APIError
                    if (api != null && api.isOffline) {
                        for (rest in adding.drop(index + 1)) failed.add(Triple(rest.line, rest.name, api.message))
                        break
                    }
                }
                progress = (index + 1) to adding.size
            }
            progress = null
            outcome = CashImportOutcome(added, failed, lost)
            if (added > 0) {
                announceDataChanged()
                onDone()
            }
        }
    }

    /** The check adds, the last step is Done; the file step has only Cancel (as the web). */
    val primaryTitle: String? = when {
        outcome != null -> L("Done@import")
        stepIndex != 1 -> null
        progress != null -> LF("Adding %d of %d…", progress!!.first, progress!!.second)
        else -> LP(toAdd.size, "Add %d item", "Add %d items")
    }

    ProviderModal(
        title = L("Import items"),
        subtitle = L("Add services and products from a spreadsheet saved as CSV. Nothing already in the catalog is changed."),
        systemImage = "file-spreadsheet",
        accent = ModalAccent.mango,
        isDirty = reading != null && outcome == null,
        primaryTitle = primaryTitle,
        isWorking = progress != null,
        steps = ModalSteps(listOf(L("File") to "file-up", L("Check") to "list-checks", L("Added") to "circle-check"), current = stepIndex),
        onBack = if (reading != null && outcome == null) ({ reading = null }) else null,
        primaryIcon = if (stepIndex == 1 && progress == null) "plus" else null,
        primaryDisabled = stepIndex == 1 && toAdd.isEmpty(),
        // Added: the footer is only "Done", as the web's.
        showsCancel = outcome == null,
        onPrimary = if (stepIndex == 1) ({ add() }) else if (outcome != null) ({ dismiss() }) else null,
        modifier = modifier,
    ) {
        val done = outcome
        val checked = reading
        if (done != null) {
            CashImportDone(done, rows)
        } else if (checked != null) {
            CashImportCheck(checked, rows, toAdd, shown, fileName, filter)
        } else {
            CashImportChoose(
                fileError = fileError,
                choose = { chooser.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*", "application/csv", "application/vnd.ms-excel")) },
                downloadTemplate = {
                    runCatching { CashCSV.write(CashCatalogCSV.template(), name = if (CashCatalogCSV.spanish) "plantilla-catalogo.csv" else "catalog-template.csv") }
                        .getOrNull()?.let { template = CashSharedFile(it) }
                },
            )
        }
    }
    template?.let { file -> key(file.id) { ShareSheet(listOf(file.url), onDismiss = { template = null }) } }
}

@Composable
private fun CashImportChoose(fileError: String?, choose: () -> Unit, downloadTemplate: () -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    // Web: the dashed drop box that opens the file picker.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(box)
            .background(Theme.mix(Theme.surfaceMuted, 40.0, Theme.surface), box)
            .dashedBorder(2.dp, Theme.borderStrong, Theme.boxRadius, on = 6.dp, off = 5.dp)
            .plainButton(label = L("Choose a CSV file"), onClick = choose)
            .padding(horizontal = 20.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(Theme.wash(Theme.mango, 26.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph("file-up", 20.dp, tint = Theme.inkStrong)
        }
        Text(L("Choose a CSV file"), style = Theme.heading(18), color = Theme.inkStrong, textAlign = TextAlign.Center)
        Text(LF("Up to %d rows.", CashCatalogCSV.maxRows), style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
    }
    if (fileError != null) NoticeCard(tone = NoticeCard.Tone.problem, title = fileError)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, Theme.borderSoft, box)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(L("How the file is read"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        listOf(
            L("One row per item, with the columns Kind, Name and Price. Category, SKU, tax and stock are optional."),
            L("A row whose SKU or name is already in the catalog is skipped: nothing you have is changed."),
            L("A category the catalog does not have yet is created."),
            L("Packs are added by hand: they point at a product."),
        ).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
                Text(line, style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
            }
        }
        Row(
            Modifier.heightIn(min = 44.dp).plainButton(label = L("Download the template"), onClick = downloadTemplate),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("download", 16.dp, tint = Theme.blue)
            Text(L("Download the template"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        }
    }
}

@Composable
private fun CashImportCheck(
    reading: CashCatalogCSV.Reading,
    rows: List<CashCatalogCSV.Row>,
    toAdd: List<CashCatalogCSV.Row>,
    shown: List<CashCatalogCSV.Row>,
    fileName: String,
    filter: androidx.compose.runtime.MutableState<String>,
) {
    val all = rows.size
    val add = rows.count { it.status == CashCatalogCSV.Row.Status.add }
    val skip = rows.count { it.status == CashCatalogCSV.Row.Status.skip }
    val error = rows.count { it.status == CashCatalogCSV.Row.Status.error }
    FlowLayout(spacing = 6.dp) {
        Chip(text = fileName, tone = Chip.Tone.muted, icon = "file-spreadsheet")
        if (reading.newCategories.isNotEmpty()) Chip(text = LP(reading.newCategories.size, "%d new category", "%d new categories"), tone = Chip.Tone.muted, icon = "tags")
    }
    if (error > 0) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            title = LP(error, "%d row has a problem and is left out. Fix it in the file and import it again: what was already added is skipped.", "%d rows have problems and are left out. Fix them in the file and import it again: what was already added is skipped."),
        )
    }
    if (toAdd.isEmpty()) NoticeCard(tone = NoticeCard.Tone.info, title = L("There is nothing to add from this file."))
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Segmented(
            options = listOf(
                Segmented.Option("all", L("All"), count = all), Segmented.Option("add", L("To add"), count = add),
                Segmented.Option("skip", L("Skipped"), count = skip), Segmented.Option("error", L("Problems"), count = error),
            ),
            selection = filter,
            look = Segmented.Look.raised,
            label = L("Rows shown"),
        )
    }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth().clip(box).border(1.5.dp, Theme.borderSoft, box)) {
        if (shown.isEmpty()) {
            Text(L("No rows here."), style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        }
        shown.forEachIndexed { index, row ->
            key(row.id) {
                if (index > 0) RowDivider()
                CashImportRow(row)
            }
        }
    }
    Text(LF("%d to add · %d skipped · %d with problems", add, skip, error), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun CashImportRow(row: CashCatalogCSV.Row) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("${row.line}", style = Theme.text(14).monospacedDigit(), color = Theme.inkSoft, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    row.name.nonEmpty ?: "—", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                val price = row.price
                if (row.body != null && price != null) {
                    Text(cashMoney(price), style = Theme.text(14).monospacedDigit(), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
                }
            }
            val detail = listOfNotNull(
                if (row.itemType == "product") L("Product") else if (row.itemType == "service") L("Service") else null,
                if (row.body != null) (row.categoryName ?: L("Uncategorized")) else null,
            )
            if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = when (row.status) {
                    CashCatalogCSV.Row.Status.add -> ProviderState.done.color
                    CashCatalogCSV.Row.Status.skip -> Theme.inkSoft
                    CashCatalogCSV.Row.Status.error -> Theme.destructive
                }
                Box(Modifier.size(6.dp).background(dot, CircleShape))
                Text(
                    when (row.status) {
                        CashCatalogCSV.Row.Status.add -> L("Will be added")
                        CashCatalogCSV.Row.Status.skip -> L("Skipped@row")
                        CashCatalogCSV.Row.Status.error -> L("Not added")
                    },
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
            }
            (row.problems + listOfNotNull(row.skip) + row.notes).forEach { line ->
                Text(line, style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun CashImportDone(outcome: CashImportOutcome, rows: List<CashCatalogCSV.Row>) {
    val skipped = rows.count { it.status == CashCatalogCSV.Row.Status.skip }
    val problems = rows.count { it.status == CashCatalogCSV.Row.Status.error }
    NoticeCard(
        tone = if (outcome.added > 0) NoticeCard.Tone.success else NoticeCard.Tone.warning,
        title = LP(outcome.added, "%d item was added to the catalog.", "%d items were added to the catalog."),
        message = if (skipped + problems > 0) LF("%d skipped · %d with problems", skipped, problems) else null,
    )
    if (outcome.lostCategories.isNotEmpty()) {
        NoticeCard(tone = NoticeCard.Tone.warning, title = LF("These categories could not be created, so their items were added without one: %@.", outcome.lostCategories.joinToString(", ")))
    }
    if (outcome.failed.isNotEmpty()) {
        val lines = outcome.failed.take(8).map { (line, name, message) -> "${LF("Row %d", line)} · $name${if (message.isEmpty()) "" else " — $message"}" } +
            (if (outcome.failed.size > 8) listOf(LF("…and %d more", outcome.failed.size - 8)) else emptyList())
        NoticeCard(
            tone = NoticeCard.Tone.problem,
            title = LP(outcome.failed.size, "%d row could not be added. Import the same file again to retry it: what was added is skipped.", "%d rows could not be added. Import the same file again to retry them: what was added is skipped."),
            message = lines.joinToString("\n"),
        )
    }
}
