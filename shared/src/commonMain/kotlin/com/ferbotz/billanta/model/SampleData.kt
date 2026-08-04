package com.ferbotz.billanta.model

/**
 * Canned data for the prototype — a Mumbai freelance designer, "Studio Nine". Every customer is in
 * Maharashtra (state 27) so GST renders as CGST+SGST. Subtotals are round with 18% GST, which makes
 * the totals fall on the exact figures in the design (e.g. ₹92,000 + 18% = ₹1,08,560).
 */
object SampleData {

    val business = BusinessProfile(
        name = "Studio Nine",
        tagline = "Independent design studio",
        ownerName = "Ananya Desai",
        email = "hello@studionine.in",
        phone = "+91 98765 43210",
        gstin = "27ABCDE1234F1Z5",
        address = "A-901, Oberoi Springs, Andheri West, Mumbai 400053",
        stateCode = "27",
        upiId = "studionine@okhdfcbank",
        bankName = "HDFC Bank",
        accountLast4 = "4821",
    )

    val kavya = Customer("c1", "Kavya Iyer", "Bloom & Co.", "kavya@bloomco.in", "+91 90045 11220", "27AAJCB1111C1Z2", "12 Carter Road, Bandra West, Mumbai 400050")
    val prateek = Customer("c2", "Prateek Sharma", "Northstar Labs", "prateek@northstar.io", "+91 98200 66112", "27AAECN2222D1Z8", "5th Flr, Peninsula, Lower Parel, Mumbai 400013")
    val nisha = Customer("c3", "Nisha Rao", "Rao Interiors", "nisha@raointeriors.in", "+91 91670 55031", "27AAFCR3333E1Z1", "301 Hiranandani, Powai, Mumbai 400076")
    val mehul = Customer("c4", "Mehul Joshi", "Joshi Textiles", "mehul@joshitextiles.in", "+91 99303 40021", "27AAGCJ4444F1Z4", "Kalbadevi Road, Mumbai 400002")
    val zoya = Customer("c5", "Zoya Khan", "Zoya Khan Photography", "zoya@zkphoto.in", "+91 90290 71145", null, "7 Pali Naka, Bandra West, Mumbai 400050")
    val aarav = Customer("c6", "Aarav Menon", "Menon & Partners", "aarav@menonpartners.in", "+91 98920 12234", "27AAHCM5555G1Z7", "Nariman Point, Mumbai 400021")
    val diya = Customer("c7", "Diya Shah", "Diya Shah Ceramics", "diya@diyashah.in", "+91 90820 88190", null, "Khar West, Mumbai 400052")

    val customers = listOf(kavya, prateek, nisha, mehul, zoya, aarav, diya)

    private fun item(id: String, name: String, desc: String, qty: Int, rateWhole: Long) =
        LineItem(id, name, desc, qty, rupees(rateWhole))

    val invoices = listOf(
        Invoice(
            id = "inv42", number = "INV-2026-0042", customer = kavya,
            issueDate = "28 Jul 2026", dueDate = "11 Aug 2026", status = InvoiceStatus.PENDING,
            items = listOf(
                item("l1", "Brand identity", "Logo, palette, type system", 1, 68000),
                item("l2", "Brand guidelines", "24-page PDF", 1, 24000),
            ), // 92,000 → ₹1,08,560.00
        ),
        Invoice(
            id = "inv41", number = "INV-2026-0041", customer = prateek,
            issueDate = "24 Jul 2026", dueDate = "07 Aug 2026", status = InvoiceStatus.PAID,
            items = listOf(item("l1", "Landing page design", "Desktop + mobile", 1, 40000)), // → ₹47,200.00
        ),
        Invoice(
            id = "inv40", number = "INV-2026-0040", customer = nisha,
            issueDate = "21 Jul 2026", dueDate = "04 Aug 2026", status = InvoiceStatus.DRAFT,
            items = listOf(item("l1", "Moodboard & concepts", "Interior brand direction", 1, 16000)), // → ₹18,880.00
        ),
        Invoice(
            id = "inv39", number = "INV-2026-0039", customer = mehul,
            issueDate = "16 Jul 2026", dueDate = "30 Jul 2026", status = InvoiceStatus.PAID,
            items = listOf(
                item("l1", "Packaging design", "Full range, 8 SKUs", 1, 150000),
                item("l2", "Print supervision", "On-site, 2 days", 1, 50000),
            ), // 200,000 → ₹2,36,000.00
        ),
        Invoice(
            id = "inv38", number = "INV-2026-0038", customer = zoya,
            issueDate = "11 Jul 2026", dueDate = "25 Jul 2026", status = InvoiceStatus.PENDING,
            items = listOf(item("l1", "Portfolio website", "Photography portfolio build", 1, 30000)), // → ₹35,400.00
        ),
        Invoice(
            id = "inv37", number = "INV-2026-0037", customer = aarav,
            issueDate = "07 Jul 2026", dueDate = "21 Jul 2026", status = InvoiceStatus.PAID,
            items = listOf(item("l1", "Pitch deck design", "40 slides + template", 1, 60000)), // → ₹70,800.00
        ),
        Invoice(
            id = "inv36", number = "INV-2026-0036", customer = diya,
            issueDate = "03 Jul 2026", dueDate = "17 Jul 2026", status = InvoiceStatus.DRAFT,
            items = listOf(item("l1", "Product photography art direction", "Ceramics catalogue", 1, 20000)), // → ₹23,600.00
        ),
    )

    /**
     * The invoice being assembled in the Create flow. Totals to ₹1,60,303.00 exactly
     * (subtotal ₹1,35,850 + 18% GST ₹24,453), matching the design's create → totals → A4 chain.
     */
    val draftInvoice = Invoice(
        id = "draft", number = "INV-2026-0043", customer = kavya,
        issueDate = "04 Aug 2026", dueDate = "18 Aug 2026", status = InvoiceStatus.DRAFT,
        items = listOf(
            item("d1", "Brand identity system", "Logo suite, palette, type", 1, 85000),
            item("d2", "Website UI design", "Marketing site, 6 screens", 1, 42000),
            item("d3", "Social media kit", "Templates + guidelines", 1, 8850),
        ),
        notes = "Thanks for your business. Payable within 14 days via UPI or bank transfer.",
    )

    val templates = listOf(
        InvoiceTemplate("modern", "Modern", "Clean, colour-accented header. Used in Preview.", TemplateTier.FREE, built = true),
        InvoiceTemplate("classic", "Classic", "Traditional ledger look with ruled rows.", TemplateTier.FREE, built = false),
        InvoiceTemplate("minimal", "Minimal", "Whitespace-forward, no dividers.", TemplateTier.FREE, built = false),
        InvoiceTemplate("noir", "Noir Mono", "Premium monochrome, letterpress feel.", TemplateTier.PREMIUM, built = true),
    )

    // Canned month figures. Unpaid == the two PENDING invoices; This month == all invoice totals.
    val thisMonthLabel = "This month"
    val monthDeltaLabel = "+18% vs June"
}
