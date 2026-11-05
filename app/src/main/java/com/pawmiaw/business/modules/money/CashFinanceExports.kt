package com.pawmiaw.business.money

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.time.ZoneId

//
//  CashFinanceExports.kt (iOS CashFinanceExports.swift)
//
//  Finance's spreadsheets (web: finance-collections.tsx `collectionsCsv`,
//  finance-payments.tsx `paymentsCsv`, finance-reports.tsx `salesCsv` and
//  `documentsCsv`), the same columns and totals row. Who owes is the front
//  desk's (level 2); payments and reports are business finances (level 3),
//  as their tabs are (docs/money-access-levels.md).
//

/** What an accountant asks for: one row per sale or bill (`/provider-finance/reports/documents`). */
@Serializable
data class CashDocumentsReport(
    val currency: String? = null,
    val rows: List<Row>,
    val totals: Totals,
) {
    @Serializable
    data class Row(
        val id: String,
        val day: String,
        val folio: String,
        val source: String,
        val status: String,
        val walkIn: Boolean? = null,
        val clientName: String? = null,
        val petName: String? = null,
        val subtotal: Int,
        val tax: Int,
        val total: Int,
        val returned: Int,
        val paid: Int,
        val balance: Int,
        val methods: List<String>? = null,
        val references: List<String>? = null,
        val createdBy: String? = null,
        val locationName: String? = null,
    )

    @Serializable
    data class Totals(val documents: Int, val subtotal: Int, val tax: Int, val total: Int, val returned: Int, val paid: Int, val balance: Int)
}

object CashFinanceCSV {
    fun agingBucket(daysLate: Int): String = when {
        daysLate <= 0 -> "current"
        daysLate <= 30 -> "d1_30"
        daysLate <= 60 -> "d31_60"
        else -> "d61_plus"
    }

    /** The receivables list: one row per client, the buckets adding up to what they owe. */
    fun collections(data: CashCollections): String {
        fun aging(item: CashCollectionItem): Map<String, Int> = item.aging ?: mapOf(agingBucket(item.daysOverdue) to item.owed)
        val totals = HashMap<String, Int>()
        for (item in data.items) {
            for ((bucket, amount) in aging(item)) totals[bucket] = (totals[bucket] ?: 0) + amount
        }
        val header = listOf(L("Client"), L("Phone"), L("Email"), L("Pets"), L("Open bills"), L("Owes")) +
            CashText.agingBuckets.map { CashText.aging(it) } +
            listOf(L("Oldest due"), L("Days late"), L("Credit on account"), L("Credit limit"), L("Last contact"))
        val rows: MutableList<List<String?>> = data.items.map { item ->
            val buckets = aging(item)
            listOf(item.name, item.phone, item.email, (item.petNames ?: emptyList()).joinToString(" / "), "${item.documents}", CashCSV.money(item.owed)) +
                CashText.agingBuckets.map { CashCSV.money(buckets[it] ?: 0) } +
                listOf(item.oldestDueDay, "${item.daysOverdue}", CashCSV.money(item.credit ?: 0), CashCSV.money(item.creditLimitAmount), item.lastContactAt?.take(10) ?: "")
        }.toMutableList()
        rows.add(
            listOf(L("Total"), "", "", "", "${data.items.sumOf { it.documents }}", CashCSV.money(data.items.sumOf { it.owed })) +
                CashText.agingBuckets.map { CashCSV.money(totals[it] ?: 0) } + listOf("", "", "", "", ""),
        )
        return CashCSV.make(header = header, rows = rows)
    }

    /** What a row adds to "collected": money in, money back as a minus, credit on account nothing. */
    fun ledgerAmount(row: CashLedgerItem): Int {
        if (row.method == "credit") return 0
        return if (row.kind == "refund") -row.amount else row.amount
    }

    /** Every payment and refund of the period; credit spent on a bill has its own column. */
    fun payments(rows: List<CashLedgerItem>, zone: ZoneId?): String {
        val header = listOf(L("Date"), L("Type"), L("Folio"), L("Client"), L("Pet"), L("Source"), L("Method"), L("Reference"), L("Amount"), L("Credit on account applied"), L("Collected by"))
        val lines: MutableList<List<String?>> = rows.map { row ->
            val credit = row.method == "credit"
            listOf(
                CashCSV.dateTime(row.paidAt, zone = zone),
                if (credit) L("Credit on account applied") else if (row.kind == "refund") L("Refund") else L("Payment"),
                row.invoiceNumber, row.clientName, row.petName, CashText.source(row.source), CashText.method(row.method), row.reference,
                if (credit) "" else CashCSV.money(ledgerAmount(row)),
                if (credit) CashCSV.money(if (row.kind == "refund") -row.amount else row.amount) else "",
                row.collectedBy?.name ?: "",
            )
        }.toMutableList()
        val creditTotal = rows.sumOf { if (it.method == "credit") (if (it.kind == "refund") -it.amount else it.amount) else 0 }
        lines.add(listOf(L("Total"), "", "", "", "", "", "", "", CashCSV.money(rows.sumOf { ledgerAmount(it) }), CashCSV.money(creditTotal), ""))
        return CashCSV.make(header = header, rows = lines)
    }

    /** The sales report as grouped on screen; cost and margin only for who may see them. */
    fun sales(report: CashSalesReport, group: String, label: (CashSalesReport.Row) -> String, margin: Boolean): String {
        val costed = report.totals.costed ?: report.rows.any { it.cost != null }
        val header = listOf(group, L("Sales and bills"), L("Quantity"), L("Before tax"), L("Tax"), L("Sold")) + (if (margin) listOf(L("Cost"), L("Margin")) else emptyList())
        val rows: MutableList<List<String?>> = report.rows.map { row ->
            listOf(
                if (report.groupBy == "day") row.key else label(row), "${row.documents}", "${row.quantity}",
                CashCSV.money(row.revenue - (row.tax ?: 0)), CashCSV.money(row.tax ?: 0), CashCSV.money(row.revenue),
            ) + (if (margin) listOf(CashCSV.money(row.cost), CashCSV.money(row.margin)) else emptyList())
        }.toMutableList()
        rows.add(
            listOf(
                L("Total"), "${report.totals.documents}", "${report.totals.quantity}", CashCSV.money(report.totals.revenue - (report.totals.tax ?: 0)),
                CashCSV.money(report.totals.tax ?: 0), CashCSV.money(report.totals.revenue),
            ) + (if (margin) listOf(if (costed) CashCSV.money(report.totals.cost) else "", if (costed) CashCSV.money(report.totals.margin) else "") else emptyList()),
        )
        return CashCSV.make(header = header, rows = rows)
    }

    private fun status(status: String): String = when (status) {
        "paid" -> L("Paid")
        "partially_paid" -> L("Partly paid")
        "overdue" -> L("Overdue")
        else -> L("Unpaid")
    }

    /** One row per sale or bill: day, folio, client, amounts, how it was paid, what is owed. */
    fun documents(report: CashDocumentsReport): String {
        val header = listOf(
            L("Date"), L("Folio"), L("Source"), L("Client"), L("Pet"), L("Subtotal"), L("Tax"), L("Total"), L("Returned or credited"), L("Paid"), L("Balance"),
            L("Status"), L("Method"), L("Reference"), L("Made by"), L("Location"),
        )
        val rows: MutableList<List<String?>> = report.rows.map { row ->
            listOf(
                row.day, row.folio, CashText.source(row.source), row.clientName ?: (if (row.walkIn == true) L("Walk-in sales") else ""), row.petName,
                CashCSV.money(row.subtotal), CashCSV.money(row.tax), CashCSV.money(row.total), CashCSV.money(row.returned), CashCSV.money(row.paid), CashCSV.money(row.balance),
                status(row.status), (row.methods ?: emptyList()).joinToString(" + ") { CashText.method(it) }, (row.references ?: emptyList()).joinToString(" / "), row.createdBy, row.locationName,
            )
        }.toMutableList()
        val t = report.totals
        rows.add(
            listOf(
                L("Total"), "", "", "", "", CashCSV.money(t.subtotal), CashCSV.money(t.tax), CashCSV.money(t.total), CashCSV.money(t.returned),
                CashCSV.money(t.paid), CashCSV.money(t.balance), "", "", "", "", "",
            ),
        )
        return CashCSV.make(header = header, rows = rows)
    }
}
