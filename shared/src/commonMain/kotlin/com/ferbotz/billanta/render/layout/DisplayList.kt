package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.text.ShapedParagraph

/**
 * What layout produces: flat, absolutely-positioned draw commands per page, in points.
 *
 * This is the contract every output shares. The on-screen painter, the PNG/JPEG rasteriser and
 * the PDF writer all consume exactly this, which is what guarantees the file a user shares is the
 * page they previewed — and makes a new output format a serialiser rather than a second renderer.
 */
data class RenderedDocument(
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val pages: List<RenderedPage>,
) {
    val pageCount: Int get() = pages.size

    companion object {
        fun empty(widthPt: Float, heightPt: Float) = RenderedDocument(widthPt, heightPt, emptyList())
    }
}

data class RenderedPage(
    val commands: List<DrawCommand>,
    /**
     * Where each of the template's sections ended up on this page. The editor uses it to put a
     * dashed placeholder over a section that has nothing in it yet, and to know what the user
     * tapped. Empty on pages that contain no tagged section.
     */
    val sections: List<SectionBounds> = emptyList(),
)

/** A section's footprint on one page. [isEmpty] means the section rendered no content. */
data class SectionBounds(
    val id: String,
    val rect: RectPt,
    val isEmpty: Boolean,
)

sealed interface DrawCommand {

    /** A filled rectangle; [radiusPt] > 0 rounds all four corners. */
    data class Fill(
        val rect: RectPt,
        val colorArgb: Long,
        val radiusPt: Float = 0f,
    ) : DrawCommand

    /**
     * Box borders. Sides are drawn independently because templates routinely set only one edge —
     * every table row rule in the `classic` template is a lone `border-bottom`.
     */
    data class Borders(
        val rect: RectPt,
        val widths: EdgesPt,
        val colors: EdgeColors,
        val radiusPt: Float = 0f,
    ) : DrawCommand

    /** A shaped paragraph placed at its top-left corner. */
    data class Text(
        val paragraph: ShapedParagraph,
        val xPt: Float,
        val yPt: Float,
    ) : DrawCommand

    data class Image(
        val rect: RectPt,
        val url: String,
        val cover: Boolean,
    ) : DrawCommand

    /** Applies [alpha] to everything inside; used for the `opacity` style property. */
    data class Group(
        val alpha: Float,
        val children: List<DrawCommand>,
    ) : DrawCommand
}

/** Walks every command, descending into groups. Used by tests and the export writers. */
fun List<DrawCommand>.flattenCommands(): List<DrawCommand> {
    val out = ArrayList<DrawCommand>(size)
    fun visit(commands: List<DrawCommand>) {
        commands.forEach { command ->
            if (command is DrawCommand.Group) visit(command.children) else out += command
        }
    }
    visit(this)
    return out
}
