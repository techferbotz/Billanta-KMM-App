package com.ferbotz.billanta.render.export

import com.ferbotz.billanta.render.BorderStyle
import com.ferbotz.billanta.render.layout.EdgeStyles
import com.ferbotz.billanta.render.layout.dashPatternFor
import com.ferbotz.billanta.render.layout.dashRuns
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.EdgeColors
import com.ferbotz.billanta.render.layout.EdgesPt
import com.ferbotz.billanta.render.layout.RectPt
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.text.FontRegistry
import com.ferbotz.billanta.render.text.RunStyle
import com.ferbotz.billanta.render.text.ShapedParagraph
import com.ferbotz.billanta.render.text.TrueTypeFont

/** A bundled face, already parsed, ready to embed. */
class PdfFace(val resourcePath: String, val font: TrueTypeFont, val postScriptName: String)

/** A decoded image plus its JPEG bytes, embedded as a `DCTDecode` XObject. */
class PdfImage(val jpegBytes: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * Writes a real vector PDF from the display list — no rasterisation anywhere.
 *
 * Text is emitted as text operators against embedded TrueType fonts, so the result is selectable,
 * searchable and sharp at any zoom. Fonts go in as `Type0`/`Identity-H` with a `CIDFontType2`
 * descendant rather than the simpler `WinAnsiEncoding`, because WinAnsi has no ₹ (U+20B9) and
 * every line of an Indian invoice needs one.
 */
class PdfWriter(
    private val faces: Map<String, PdfFace>,
    private val images: Map<String, PdfImage> = emptyMap(),
) {

    fun write(document: RenderedDocument): ByteArray {
        val usage = collectUsage(document)
        val builder = PdfObjects()

        // Font objects first so the page resources can reference them.
        val fontIds = LinkedHashMap<String, Int>()
        usage.glyphsByFace.forEach { (resourcePath, glyphs) ->
            val face = faces[resourcePath] ?: return@forEach
            fontIds[resourcePath] = writeFont(builder, face, glyphs)
        }
        val imageIds = LinkedHashMap<String, Int>()
        usage.imageUrls.forEach { url ->
            images[url]?.let { imageIds[url] = writeImage(builder, it) }
        }

        val pagesId = builder.reserve()
        val pageIds = document.pages.map { page ->
            val content = ContentStream(document.pageHeightPt, fontIds.keys.toList(), imageIds.keys.toList())
            content.emitPage(page)
            val contentId = builder.add(stream(dict = "<< /Length ${content.bytes.size} >>", data = content.bytes))

            builder.add(
                ascii(
                    buildString {
                        append("<< /Type /Page /Parent $pagesId 0 R ")
                        append("/MediaBox [0 0 ${num(document.pageWidthPt)} ${num(document.pageHeightPt)}] ")
                        append("/Resources << ")
                        if (fontIds.isNotEmpty()) {
                            append("/Font << ")
                            fontIds.keys.forEachIndexed { i, key -> append("/F$i ${fontIds[key]} 0 R ") }
                            append(">> ")
                        }
                        if (imageIds.isNotEmpty()) {
                            append("/XObject << ")
                            imageIds.keys.forEachIndexed { i, key -> append("/Im$i ${imageIds[key]} 0 R ") }
                            append(">> ")
                        }
                        if (content.usesAlpha) {
                            append("/ExtGState << ")
                            content.alphaValues.forEachIndexed { i, a -> append("/GS$i << /ca ${num(a)} /CA ${num(a)} >> ") }
                            append(">> ")
                        }
                        append(">> /Contents $contentId 0 R >>")
                    },
                ),
            )
        }

        builder.set(
            pagesId,
            ascii(
                "<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>",
            ),
        )
        val catalogId = builder.add(ascii("<< /Type /Catalog /Pages $pagesId 0 R >>"))
        return builder.serialize(catalogId)
    }

    // ---- fonts ---------------------------------------------------------------------------------

    /**
     * Emits the four objects a composite font needs: the Type0 wrapper, the CID descendant with
     * per-glyph widths, the descriptor carrying the embedded file, and a ToUnicode map so a reader
     * can turn glyph ids back into characters when the user copies text.
     */
    private fun writeFont(builder: PdfObjects, face: PdfFace, glyphs: Map<Int, Int>): Int {
        val font = face.font
        val fileId = builder.add(
            stream(
                dict = "<< /Length ${font.data.size} /Length1 ${font.data.size} >>",
                data = font.data,
            ),
        )

        val descriptorId = builder.add(
            ascii(
                buildString {
                    append("<< /Type /FontDescriptor /FontName /${face.postScriptName} ")
                    // 4 = symbolic is wrong for text faces; 32 marks it as ordinary Latin text.
                    append("/Flags 32 ")
                    append(
                        "/FontBBox [${font.toMilliEm(font.xMinFUnits)} ${font.toMilliEm(font.yMinFUnits)} " +
                            "${font.toMilliEm(font.xMaxFUnits)} ${font.toMilliEm(font.yMaxFUnits)}] ",
                    )
                    append("/ItalicAngle ${num(font.italicAngle)} ")
                    append("/Ascent ${font.toMilliEm(font.ascenderFUnits)} ")
                    append("/Descent ${font.toMilliEm(font.descenderFUnits)} ")
                    append("/CapHeight ${font.toMilliEm(font.capHeightFUnits)} ")
                    append("/StemV 80 /FontFile2 $fileId 0 R >>")
                },
            ),
        )

        val widths = glyphs.keys.sorted().joinToString(" ") { gid ->
            "$gid [${font.advanceMilliEm(gid)}]"
        }
        val descendantId = builder.add(
            ascii(
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /${face.postScriptName} " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/FontDescriptor $descriptorId 0 R /DW 1000 /W [$widths] /CIDToGIDMap /Identity >>",
            ),
        )

        val toUnicodeId = builder.add(
            stream(dict = null, data = toUnicodeCMap(glyphs)),
        )

        return builder.add(
            ascii(
                "<< /Type /Font /Subtype /Type0 /BaseFont /${face.postScriptName} " +
                    "/Encoding /Identity-H /DescendantFonts [$descendantId 0 R] /ToUnicode $toUnicodeId 0 R >>",
            ),
        )
    }

    private fun toUnicodeCMap(glyphs: Map<Int, Int>): ByteArray {
        val entries = glyphs.entries.sortedBy { it.key }
        return ascii(
            buildString {
                append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
                // A cmap section may hold at most 100 mappings, so they go out in chunks.
                entries.chunked(100).forEach { chunk ->
                    append("${chunk.size} beginbfchar\n")
                    chunk.forEach { (gid, codePoint) ->
                        append("<${hex4(gid)}> <${utf16Hex(codePoint)}>\n")
                    }
                    append("endbfchar\n")
                }
                append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend")
            },
        )
    }

    // ---- images --------------------------------------------------------------------------------

    private fun writeImage(builder: PdfObjects, image: PdfImage): Int = builder.add(
        stream(
            dict = "<< /Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                "/Length ${image.jpegBytes.size} >>",
            data = image.jpegBytes,
        ),
    )

    // ---- usage scan ----------------------------------------------------------------------------

    private class Usage(
        /** face resource path → (glyph id → codepoint), for widths and the ToUnicode map. */
        val glyphsByFace: MutableMap<String, MutableMap<Int, Int>> = LinkedHashMap(),
        val imageUrls: MutableSet<String> = LinkedHashSet(),
    )

    private fun collectUsage(document: RenderedDocument): Usage {
        val usage = Usage()
        fun visitParagraph(paragraph: ShapedParagraph) {
            paragraph.lines.forEach { line ->
                line.runs.forEach { run ->
                    val path = faceKeyOf(run.style)
                    val font = faces[path]?.font ?: return@forEach
                    val glyphs = usage.glyphsByFace.getOrPut(path) { LinkedHashMap() }
                    run.text.forEachCodePoint { codePoint ->
                        glyphs[font.glyphId(codePoint)] = codePoint
                    }
                }
            }
        }
        fun visit(commands: List<DrawCommand>) {
            commands.forEach { command ->
                when (command) {
                    is DrawCommand.Text -> visitParagraph(command.paragraph)
                    is DrawCommand.Image -> usage.imageUrls += command.url
                    is DrawCommand.Group -> visit(command.children)
                    else -> Unit
                }
            }
        }
        document.pages.forEach { visit(it.commands) }
        return usage
    }

    // ---- content stream --------------------------------------------------------------------------

    private inner class ContentStream(
        private val pageHeightPt: Float,
        private val fontOrder: List<String>,
        private val imageOrder: List<String>,
    ) {
        private val buffer = ByteBuf()
        val alphaValues = ArrayList<Float>()
        val usesAlpha: Boolean get() = alphaValues.isNotEmpty()
        val bytes: ByteArray get() = buffer.toByteArray()

        fun emitPage(page: com.ferbotz.billanta.render.layout.RenderedPage) {
            page.commands.forEach { emit(it) }
        }

        private fun emit(command: DrawCommand) {
            when (command) {
                is DrawCommand.Fill -> {
                    op(colorOp(command.colorArgb, stroke = false))
                    if (command.radiusPt > 0f) roundRectPath(command.rect, command.radiusPt) else rectPath(command.rect)
                    op("f")
                }
                is DrawCommand.Borders ->
                    emitBorders(command.rect, command.widths, command.colors, command.radiusPt, command.styles)
                is DrawCommand.Text -> emitText(command)
                is DrawCommand.Image -> emitImage(command)
                is DrawCommand.Group -> {
                    val index = alphaValues.size
                    alphaValues += command.alpha
                    op("q")
                    op("/GS$index gs")
                    command.children.forEach { emit(it) }
                    op("Q")
                }
            }
        }

        /** Borders are filled bands, exactly as the on-screen painter draws them. */
        private fun emitBorders(
            rect: RectPt,
            widths: EdgesPt,
            colors: EdgeColors,
            radiusPt: Float,
            styles: EdgeStyles,
        ) {
            val uniformWidth = widths.top
            val uniform = widths.right == uniformWidth && widths.bottom == uniformWidth &&
                widths.left == uniformWidth
            val distinctColors = listOfNotNull(colors.top, colors.right, colors.bottom, colors.left).distinct()
            // Absent means solid, matching the on-screen painter's rule exactly.
            val distinctStyles = listOf(styles.top, styles.right, styles.bottom, styles.left)
                .map { it ?: BorderStyle.Solid }.distinct()

            if (uniform && uniformWidth > 0f && distinctColors.size <= 1 && distinctStyles.size == 1 &&
                radiusPt > 0f
            ) {
                // Mirrors the on-screen painter's rounded case: one stroked path, so dashes carry
                // round the corners. Stroking (rather than filling bands) is also the only way to
                // follow a curve, and the inset by half the width matches Compose's stroke centre.
                val pattern = dashPatternFor(uniformWidth, distinctStyles.single())
                op("q")
                pattern?.let { op("[${num(it[0])} ${num(it[1])}] 0 d") }
                op(colorOp(distinctColors.firstOrNull() ?: 0xFF000000, stroke = true))
                op("${num(uniformWidth)} w")
                roundRectPath(
                    RectPt(
                        rect.x + uniformWidth / 2f,
                        rect.y + uniformWidth / 2f,
                        rect.width - uniformWidth,
                        rect.height - uniformWidth,
                    ),
                    radiusPt,
                )
                op("S")
                op("Q")
                return
            }

            fun band(
                width: Float,
                argb: Long?,
                style: BorderStyle?,
                x: Float,
                y: Float,
                w: Float,
                h: Float,
                horizontal: Boolean,
            ) {
                if (width <= 0f || w <= 0f || h <= 0f) return
                op(colorOp(argb ?: 0xFF000000, stroke = false))
                val pattern = dashPatternFor(width, style)
                if (pattern == null) {
                    rectPath(RectPt(x, y, w, h))
                    op("f")
                    return
                }
                dashRuns(if (horizontal) w else h, pattern).forEach { (start, len) ->
                    if (horizontal) rectPath(RectPt(x + start, y, len, h))
                    else rectPath(RectPt(x, y + start, w, len))
                    op("f")
                }
            }
            band(widths.top, colors.top, styles.top, rect.x, rect.y, rect.width, widths.top, true)
            band(
                widths.bottom, colors.bottom, styles.bottom,
                rect.x, rect.bottom - widths.bottom, rect.width, widths.bottom, true,
            )
            band(widths.left, colors.left, styles.left, rect.x, rect.y, widths.left, rect.height, false)
            band(
                widths.right, colors.right, styles.right,
                rect.right - widths.right, rect.y, widths.right, rect.height, false,
            )
        }

        private fun emitText(command: DrawCommand.Text) {
            command.paragraph.lines.forEach { line ->
                line.runs.forEach { run ->
                    if (run.text.isEmpty()) return@forEach
                    val path = faceKeyOf(run.style)
                    val face = faces[path] ?: return@forEach
                    val fontIndex = fontOrder.indexOf(path)
                    if (fontIndex < 0) return@forEach

                    val x = command.xPt + run.xPt
                    val baseline = command.yPt + line.baselinePt
                    op("BT")
                    op("/F$fontIndex ${num(run.style.fontSizePt)} Tf")
                    if (run.style.letterSpacingPt != 0f) op("${num(run.style.letterSpacingPt)} Tc")
                    op(colorOp(run.style.colorArgb, stroke = false))
                    // PDF's origin is the bottom-left corner, so every y is mirrored here rather
                    // than by flipping the whole coordinate system — which would mirror glyphs too.
                    op("1 0 0 1 ${num(x)} ${num(pageHeightPt - baseline)} Tm")
                    op("<${glyphHex(face.font, run.text)}> Tj")
                    if (run.style.letterSpacingPt != 0f) op("0 Tc")
                    op("ET")
                }
            }
        }

        private fun emitImage(command: DrawCommand.Image) {
            val index = imageOrder.indexOf(command.url)
            if (index < 0) return
            val rect = command.rect
            op("q")
            op(
                "${num(rect.width)} 0 0 ${num(rect.height)} ${num(rect.x)} " +
                    "${num(pageHeightPt - rect.bottom)} cm",
            )
            op("/Im$index Do")
            op("Q")
        }

        private fun rectPath(rect: RectPt) {
            op("${num(rect.x)} ${num(pageHeightPt - rect.bottom)} ${num(rect.width)} ${num(rect.height)} re")
        }

        /** Four Bézier arcs; 0.5523 is the standard circle approximation constant. */
        private fun roundRectPath(rect: RectPt, radius: Float) {
            val r = minOf(radius, rect.width / 2f, rect.height / 2f)
            val k = r * 0.5523f
            val left = rect.x
            val right = rect.right
            val top = pageHeightPt - rect.y
            val bottom = pageHeightPt - rect.bottom

            op("${num(left + r)} ${num(bottom)} m")
            op("${num(right - r)} ${num(bottom)} l")
            op("${num(right - r + k)} ${num(bottom)} ${num(right)} ${num(bottom + r - k)} ${num(right)} ${num(bottom + r)} c")
            op("${num(right)} ${num(top - r)} l")
            op("${num(right)} ${num(top - r + k)} ${num(right - r + k)} ${num(top)} ${num(right - r)} ${num(top)} c")
            op("${num(left + r)} ${num(top)} l")
            op("${num(left + r - k)} ${num(top)} ${num(left)} ${num(top - r + k)} ${num(left)} ${num(top - r)} c")
            op("${num(left)} ${num(bottom + r)} l")
            op("${num(left)} ${num(bottom + r - k)} ${num(left + r - k)} ${num(bottom)} ${num(left + r)} ${num(bottom)} c")
            op("h")
        }

        private fun op(text: String) {
            buffer.append(text)
            buffer.append("\n")
        }
    }

    private fun colorOp(argb: Long, stroke: Boolean): String {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return "${num(r)} ${num(g)} ${num(b)} ${if (stroke) "RG" else "rg"}"
    }

    private fun glyphHex(font: TrueTypeFont, text: String): String = buildString {
        text.forEachCodePoint { codePoint -> append(hex4(font.glyphId(codePoint))) }
    }

    private fun faceKeyOf(style: RunStyle): String =
        FontRegistry.resourcePathFor(style.fontWeight, style.italic)

    private companion object {
        fun hex4(value: Int): String {
            val hex = value.toString(16).uppercase()
            return "0".repeat((4 - hex.length).coerceAtLeast(0)) + hex.takeLast(4)
        }

        fun utf16Hex(codePoint: Int): String =
            if (codePoint <= 0xFFFF) {
                hex4(codePoint)
            } else {
                val v = codePoint - 0x10000
                hex4(0xD800 + (v shr 10)) + hex4(0xDC00 + (v and 0x3FF))
            }
    }
}

/** Iterates real Unicode code points so glyph lookup handles surrogate pairs correctly. */
internal inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00))
            i += 2
        } else {
            action(c.code)
            i++
        }
    }
}

/** Formats a number the way PDF wants it: a plain decimal, never scientific notation. */
internal fun num(value: Float): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = kotlin.math.round(value * 1000f) / 1000f
    if (rounded == rounded.toLong().toFloat()) return rounded.toLong().toString()
    return rounded.toString()
}

internal fun ascii(text: String): ByteArray = text.encodeToByteArray()

/** Wraps binary payloads (font files, JPEG data) in a PDF stream object. */
internal fun stream(dict: String?, data: ByteArray): ByteArray {
    val header = ascii((dict ?: "<< /Length ${data.size} >>") + "\nstream\n")
    val footer = ascii("\nendstream")
    val out = ByteArray(header.size + data.size + footer.size)
    header.copyInto(out)
    data.copyInto(out, header.size)
    footer.copyInto(out, header.size + data.size)
    return out
}

/** Collects numbered objects and writes the file with a correct cross-reference table. */
internal class PdfObjects {
    private val bodies = ArrayList<ByteArray?>()

    fun add(body: ByteArray): Int {
        bodies += body
        return bodies.size
    }

    /** Reserves an id for an object whose contents depend on objects written later. */
    fun reserve(): Int {
        bodies += null
        return bodies.size
    }

    fun set(id: Int, body: ByteArray) {
        bodies[id - 1] = body
    }

    fun serialize(rootId: Int): ByteArray {
        val out = ByteBuf()
        out.append("%PDF-1.7\n")
        // A binary comment marks the file as binary for transfer tools.
        out.append(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        val offsets = IntArray(bodies.size + 1)
        bodies.forEachIndexed { index, body ->
            val id = index + 1
            offsets[id] = out.size
            out.append("$id 0 obj\n")
            out.append(body ?: ascii("<< >>"))
            out.append("\nendobj\n")
        }

        val xrefOffset = out.size
        out.append("xref\n0 ${bodies.size + 1}\n")
        out.append("0000000000 65535 f \n")
        for (id in 1..bodies.size) {
            out.append(offsets[id].toString().padStart(10, '0') + " 00000 n \n")
        }
        out.append("trailer\n<< /Size ${bodies.size + 1} /Root $rootId 0 R >>\n")
        out.append("startxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }
}

/** Minimal growable byte buffer; PDF mixes ASCII syntax with raw binary payloads. */
internal class ByteBuf {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    val size: Int get() = length

    fun append(text: String) = append(text.encodeToByteArray())

    fun append(data: ByteArray) {
        ensure(length + data.size)
        data.copyInto(buffer, length)
        length += data.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(length)

    private fun ensure(capacity: Int) {
        if (capacity <= buffer.size) return
        var next = buffer.size
        while (next < capacity) next *= 2
        buffer = buffer.copyOf(next)
    }

    private companion object {
        const val INITIAL_CAPACITY = 8192
    }
}
