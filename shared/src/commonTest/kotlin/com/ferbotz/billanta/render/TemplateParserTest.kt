package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateParserTest {

    /** The worked example from TEMPLATE_JSON.md, verbatim (abridged fields included). */
    private val workedExample = """
    {
      "schemaVersion": 1,
      "compilerVersion": 1,
      "page": { "size": "A4", "margin": { "top": 30, "right": 30, "bottom": 30, "left": 30 },
                "fontFamily": "Inter", "baseFontSize": 11 },
      "root": {
        "type": "box",
        "style": {},
        "children": [
          { "type": "text", "style": { "color": "#123456", "fontSize": 15 },
            "spans": [ { "value": { "kind": "bind", "path": "invoice.number", "format": "text", "fallback": "" } } ] },
          { "type": "table", "style": {}, "columns": [ { "width": "auto" }, { "width": "auto" } ],
            "header": [],
            "body": {
              "repeat": { "path": "items", "as": "item" },
              "row": { "type": "row", "style": {}, "cells": [
                { "type": "cell", "style": {}, "colSpan": 1, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "item.description", "format": "text", "fallback": "" } } ] } ] },
                { "type": "cell", "style": {}, "colSpan": 1, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "item.amount", "format": "currency", "fallback": "" } } ] } ] }
              ] }
            },
            "footer": []
          },
          { "type": "conditional", "path": "payment.upi", "child": {
              "type": "text", "style": {}, "spans": [
                { "value": { "kind": "literal", "text": "Pay: " } },
                { "value": { "kind": "bind", "path": "payment.upi", "format": "text", "fallback": "" } } ] } }
        ]
      }
    }
    """

    @Test
    fun parses_the_worked_example() {
        val doc = TemplateParser.parse(workedExample)
        assertNotNull(doc)
        assertEquals(1, doc.schemaVersion)
        assertEquals(30f, doc.page.marginTopPt)
        assertEquals(11f, doc.page.baseFontSizePt)

        val root = assertIs<TBox>(doc.root)
        assertEquals(3, root.children.size)

        val heading = assertIs<TText>(root.children[0])
        assertEquals(15f, heading.style.fontSizePt)
        assertEquals(0xFF123456L, heading.style.color)
        val bind = assertIs<TValue.Bind>(heading.spans.single().value)
        assertEquals("invoice.number", bind.path)

        val table = assertIs<TTable>(root.children[1])
        assertEquals(2, table.columns.size)
        assertTrue(table.columns.all { it.isAuto })
        val body = assertIs<TTableBody.Repeat>(table.body)
        assertEquals("items", body.path)
        assertEquals("item", body.alias)
        assertEquals(2, body.row.cells.size)
        assertEquals("currency", (assertIs<TText>(body.row.cells[1].children.single()).spans.single().value as TValue.Bind).format)

        val gate = assertIs<TConditional>(root.children[2])
        assertEquals("payment.upi", gate.path)
        val gated = assertIs<TText>(gate.child)
        assertEquals(2, gated.spans.size)
    }

    @Test
    fun unknown_node_types_are_skipped_not_fatal() {
        val json = """
        { "schemaVersion": 9, "compilerVersion": 9,
          "page": { "size": "A4", "margin": { "top": 36, "right": 36, "bottom": 36, "left": 36 }, "fontFamily": "Inter", "baseFontSize": 11 },
          "root": { "type": "box", "style": {}, "children": [
            { "type": "hologram", "style": {}, "spins": true },
            { "type": "text", "style": {}, "spans": [ { "value": { "kind": "literal", "text": "hi" } } ] }
          ] } }
        """
        val doc = TemplateParser.parse(json)
        assertNotNull(doc)
        val root = assertIs<TBox>(doc.root)
        // The unknown node renders nothing; the known one survives.
        assertEquals(1, root.children.size)
        assertIs<TText>(root.children.single())
    }

    @Test
    fun unknown_style_keys_are_ignored() {
        val json = """
        { "root": { "type": "box",
            "style": { "display": "flex", "blendMode": "multiply", "paddingTop": 10, "gap": 6 },
            "children": [] } }
        """
        val doc = TemplateParser.parse(json)
        assertNotNull(doc)
        val style = doc.root.style
        assertEquals("flex", style.display)
        assertEquals(10f, style.paddingTopPt)
        assertEquals(6f, style.gapPt)
    }

    @Test
    fun style_dimensions_percentages_and_auto() {
        val style = TemplateParser.parseStyle(
            com.ferbotz.billanta.core.BillantaJson.parseToJsonElement(
                """{ "width": "50%", "height": 120, "marginLeft": "auto", "fontWeight": 700, "opacity": 0.5 }""",
            ).let { it as kotlinx.serialization.json.JsonObject },
        )
        assertEquals(Dim.Percent(50f), style.width)
        assertEquals(Dim.Pt(120f), style.height)
        assertEquals(Dim.Auto, style.marginLeft)
        assertEquals(700, style.fontWeight)
        assertEquals(0.5f, style.opacity)
    }

    @Test
    fun hex_colors_parse_with_and_without_alpha() {
        assertEquals(0xFF123456L, TemplateParser.parseHexColor("#123456"))
        assertEquals(0x80FF0000L, TemplateParser.parseHexColor("#ff000080"))
        assertNull(TemplateParser.parseHexColor("red"))
    }

    @Test
    fun broken_document_returns_null() {
        assertNull(TemplateParser.parse("not json"))
        assertNull(TemplateParser.parse("""{ "page": {} }""")) // no root
    }

    // ---- bindings ------------------------------------------------------------------------------

    private fun sampleRecord() = InvoiceRecord(
        id = "i1",
        invoiceNumber = "INV-7",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 5),
        currency = "INR",
        companySnapshot = CompanySnapshot(name = "Studio Nine", upiId = "studio@upi", stateCode = "27"),
        customerSnapshot = CustomerSnapshot(name = "Kavya", stateCode = "27"),
        items = listOf(
            InvoiceItemRecord(description = "Design", quantity = "2.50", unitPricePaise = 1000, taxRatePercent = "18", lineTotalPaise = 2500),
        ),
        subtotalPaise = 2500,
        taxTotalPaise = 450,
        grandTotalPaise = 2950,
        updatedAtMillis = 1,
    )

    @Test
    fun bindings_resolve_paths_aliases_and_formats() {
        val ctx = BindingContext(bindingDataFor(sampleRecord()), "INR")

        assertEquals("INV-7", ctx.resolveText(TValue.Bind("invoice.number", "text", "")))
        assertEquals("₹29.50", ctx.resolveText(TValue.Bind("invoice.total", "currency", "")))
        assertEquals("5 Aug 2026", ctx.resolveText(TValue.Bind("invoice.date", "date", "")))
        assertEquals("Studio Nine", ctx.resolveText(TValue.Bind("company.name", "text", "")))

        val items = ctx.resolveRaw("items") as List<*>
        val alias = mapOf("item" to items.single())
        assertEquals("Design", ctx.resolveText(TValue.Bind("item.description", "text", ""), alias))
        assertEquals("₹25.00", ctx.resolveText(TValue.Bind("item.amount", "currency", ""), alias))
        assertEquals("2.5", ctx.resolveText(TValue.Bind("item.quantity", "number", ""), alias))
    }

    @Test
    fun bindings_fall_back_when_empty_and_gate_truthiness() {
        val ctx = BindingContext(bindingDataFor(sampleRecord()), "INR")

        assertEquals("N/A", ctx.resolveText(TValue.Bind("customer.gstin", "text", "N/A")))
        assertTrue(ctx.isTruthy("payment.upi"))
        assertFalse(ctx.isTruthy("customer.gstin"))
        assertFalse(ctx.isTruthy("invoice.discount")) // zero money is falsy — hides discount rows
        assertTrue(ctx.isTruthy("invoice.total"))
        assertFalse(ctx.isTruthy("no.such.path"))
    }
}
