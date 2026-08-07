package com.ferbotz.billanta.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The edit sheet is driven by whatever the template declares, so these pin down the contract: the
 * order is the template's, unknown control types are skipped, and a template that declares nothing
 * still gets a usable sheet.
 */
class CustomisationControlTest {

    private fun doc(customisation: String, theme: String = DEFAULT_THEME, sections: String = DEFAULT_SECTIONS) =
        assertNotNull(
            TemplateParser.parse(
                """
                { "schemaVersion": 1, "compilerVersion": 1,
                  "page": { "size": "A4", "margin": { "top": 36, "right": 36, "bottom": 36, "left": 36 },
                            "fontFamily": "Inter", "baseFontSize": 11 },
                  $theme
                  $sections
                  $customisation
                  "root": { "type": "box", "style": {}, "children": [] } }
                """,
            ),
        )

    @Test
    fun controls_follow_the_order_the_template_declares() {
        val parsed = doc(
            """
            "customisation": [
              { "type": "color",    "title": "Secondary colour", "token": "secondary" },
              { "type": "template", "title": "Design" },
              { "type": "section",  "title": "Payment block",    "section": "payment" },
              { "type": "color",    "title": "Primary colour",   "token": "accent" }
            ],
            """,
        )

        assertEquals(4, parsed.controls.size)
        assertEquals(
            listOf("Secondary colour", "Design", "Payment block", "Primary colour"),
            parsed.controls.map { it.title },
            "the sheet must render controls in the template's own order",
        )
        assertEquals("secondary", assertIs<CustomisationControl.Color>(parsed.controls[0]).token)
        assertIs<CustomisationControl.TemplatePicker>(parsed.controls[1])
        assertEquals("payment", assertIs<CustomisationControl.SectionToggle>(parsed.controls[2]).section)
    }

    /** Two colour controls, which is the case the current single-accent UI could not express. */
    @Test
    fun a_template_can_ask_for_several_colours() {
        val parsed = doc(
            """
            "customisation": [
              { "type": "color", "title": "Primary colour",   "token": "accent" },
              { "type": "color", "title": "Secondary colour", "token": "secondary" }
            ],
            """,
        )
        val colours = parsed.controls.filterIsInstance<CustomisationControl.Color>()
        assertEquals(2, colours.size)
        assertEquals(listOf("accent", "secondary"), colours.map { it.token })
        assertEquals(listOf("Primary colour", "Secondary colour"), colours.map { it.title })
    }

    @Test
    fun an_unknown_control_type_is_skipped_not_fatal() {
        val parsed = doc(
            """
            "customisation": [
              { "type": "color",     "title": "Primary", "token": "accent" },
              { "type": "hologram",  "title": "Spin",    "axis": "z" },
              { "type": "section",   "title": "Payment", "section": "payment" }
            ],
            """,
        )
        assertEquals(2, parsed.controls.size, "the unknown control should vanish, the rest survive")
        assertEquals(listOf("Primary", "Payment"), parsed.controls.map { it.title })
    }

    @Test
    fun a_control_missing_its_binding_is_dropped() {
        val parsed = doc(
            """
            "customisation": [
              { "type": "color",   "title": "No token here" },
              { "type": "section", "title": "No section here" },
              { "type": "color",   "title": "Fine", "token": "accent" }
            ],
            """,
        )
        assertEquals(1, parsed.controls.size)
        assertEquals("Fine", parsed.controls.single().title)
    }

    @Test
    fun a_control_with_no_title_falls_back_to_its_binding() {
        val parsed = doc("""  "customisation": [ { "type": "color", "token": "accent" } ], """)
        assertEquals("accent", parsed.controls.single().title)
    }

    /**
     * Templates already published (v2) declare tokens and sections but no `customisation` array,
     * so the sheet must still offer everything they support.
     */
    @Test
    fun a_template_without_a_control_list_gets_a_sensible_default() {
        val parsed = doc(customisation = "")
        val titles = parsed.controls.map { it.title }

        assertIs<CustomisationControl.TemplatePicker>(parsed.controls.first())
        assertTrue(titles.contains("Accent"), "every declared token should get a control: $titles")
        assertTrue(titles.contains("Payment details"), "every hidable section should get one: $titles")
        assertTrue(
            parsed.controls.none { it is CustomisationControl.SectionToggle && it.section == "items" },
            "a section that is not hidable must not get a toggle",
        )
    }

    @Test
    fun a_template_with_no_theming_at_all_still_offers_the_switcher() {
        val parsed = doc(customisation = "", theme = "", sections = "")
        assertEquals(1, parsed.controls.size)
        assertIs<CustomisationControl.TemplatePicker>(parsed.controls.single())
        assertTrue(!parsed.isCustomisable)
    }

    @Test
    fun declaring_controls_lets_a_template_expose_only_some_of_its_tokens() {
        val parsed = doc(
            theme = """
            "theme": { "tokens": {
              "accent":    { "default": "#2b3648", "label": "Accent" },
              "secondary": { "default": "#6a7180", "label": "Secondary" }
            } },
            """,
            customisation = """ "customisation": [ { "type": "color", "title": "Accent", "token": "accent" } ], """,
        )
        assertEquals(2, parsed.themeTokens.size, "both tokens still exist for rendering")
        assertEquals(1, parsed.controls.size, "but only one is offered to the user")
        assertEquals("accent", assertIs<CustomisationControl.Color>(parsed.controls.single()).token)
    }

    private companion object {
        const val DEFAULT_THEME = """ "theme": { "tokens": { "accent": { "default": "#2b3648", "label": "Accent" } } }, """
        const val DEFAULT_SECTIONS = """
            "sections": [
              { "id": "items",   "label": "Item table",      "hidable": false },
              { "id": "payment", "label": "Payment details", "hidable": true  }
            ],
        """
    }
}
