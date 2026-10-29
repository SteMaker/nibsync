package com.stemaker.nibsync.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.stemaker.nibsync.model.*
import com.stemaker.nibsync.text.TextTags
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** Not a test: renders the PC window with sample content for a design review (NIBSYNC_TOUR_OUT). */
class DesignTourPc {
    /** Handwriting-like words: one looping stroke per word along a line. */
    private fun writing(x0: Float, y: Float, words: List<Float>, brush: BrushSpec): List<ElementDraft> {
        var x = x0
        var t = 0L
        return words.mapIndexed { w, len ->
            val pts = ArrayList<StrokePoint>()
            val steps = (len * 10).toInt()
            for (i in 0..steps) {
                val a = i * 0.21f + w
                // Forward along the line with small loops back, like joined-up letters.
                val px = x + len * i / steps - 0.9f * sin(a + 1.2f)
                val py = y + 1.7f * sin(a) * (0.75f + 0.25f * sin(i * 0.05f + w))
                pts += StrokePoint(px, py, t, 0.45f + 0.25f * sin(i * 0.11f))
                t += 6
            }
            x += len + 3.5f
            StrokeDraft(brush, InputTool.STYLUS, pts)
        }
    }

    /** A pen line through [points], with a sample every 0.3 mm like real input. */
    private fun polyline(points: List<Pair<Float, Float>>, brush: BrushSpec): StrokeDraft {
        val pts = ArrayList<StrokePoint>()
        var t = 0L
        for ((a, b) in points.zipWithNext()) {
            val n = maxOf(1, (kotlin.math.hypot(b.first - a.first, b.second - a.second) / 0.3f).toInt())
            for (i in 0 until n) {
                pts += StrokePoint(a.first + (b.first - a.first) * i / n, a.second + (b.second - a.second) * i / n, t, 0.6f)
                t += 6
            }
        }
        pts += StrokePoint(points.last().first, points.last().second, t, 0.6f)
        return StrokeDraft(brush, InputTool.STYLUS, pts)
    }


    @Test
    fun tour() {
        val outDir = System.getenv("NIBSYNC_TOUR_OUT")
        assumeTrue(outDir != null)
        val root = kotlin.io.path.createTempDirectory("tour").toFile()
        val container = DesktopContainer(okio.Path.Companion.run { root.absolutePath.toPath() })
        val settings = Settings(container.fs, okio.Path.Companion.run { (root.absolutePath + "/settings.properties").toPath() })
        val lib = container.library
        val ink = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff1a1a2e.toInt(), 0.6f)
        val blue = BrushSpec(BrushFamily.BALLPOINT, 0xff1a3fa8.toInt(), 0.5f)
        val marker = BrushSpec(BrushFamily.HIGHLIGHTER, 0x66ffeb3b, 5f)
        var main = DocumentId("tour-placeholder")
        var planId = DocumentId("tour-placeholder")
        runBlocking {
            lib.load("Notizen", "Notizen")
            val work = lib.createBook("Arbeit")
            val projects = lib.createFolder(work.id, "Projekte")
            val meetings = lib.createFolder(work.id, "Besprechungen")
            val lined = BackgroundSpec(BackgroundKind.LINED, 7f)
            val atlas = lib.createDocument(projects.id, "Projekt Atlas", PageFormat(), lined)
            val ideas = lib.createDocument(projects.id, "Ideensammlung", PageFormat(), lined)
            lib.createDocument(meetings.id, "Jour fixe 14.10.", PageFormat(), lined)
            lib.createDocument(meetings.id, "Jour fixe 21.10.", PageFormat(), lined)
            val home = lib.createBook("Privat")
            lib.createDocument(home.id, "Einkaufsliste", PageFormat(), lined)
            lib.createDocument(home.id, "Urlaub 2027", PageFormat(), BackgroundSpec(BackgroundKind.DOTTED, 5f))
            main = atlas.id.asDocumentId

            val ideasSession = container.openSession(ideas.id.asDocumentId)
            ideasSession.replaceElements(emptySet(), listOf(AnchorDraft(20f, 40f, "Offene Fragen")))
            val s = container.openSession(main)
            val heading = RichText(listOf(TextParagraph("Kickoff Projekt Atlas", marks = listOf(TextMark(0, 21, MarkStyle.Bold), TextMark(0, 21, MarkStyle.Size(24f))))))
            val list = RichText(
                listOf(
                    TextParagraph("Ziele", marks = listOf(TextMark(0, 5, MarkStyle.Bold), TextMark(0, 5, MarkStyle.Size(14f)))),
                    TextParagraph("Prototyp bis Ende November", ListKind.BULLET),
                    TextParagraph("Budget mit Einkauf klären", ListKind.BULLET),
                    TextParagraph("Rahmenvertrag liegt noch nicht vor", ListKind.BULLET, continued = true),
                    TextParagraph("Testgruppe aus drei Teams", ListKind.BULLET),
                ),
            )
            val drafts = ArrayList<ElementDraft>()
            drafts += TextBoxDraft(20f, 26f, 170f, 12f, heading)
            drafts += writing(22f, 53f, listOf(18f, 9f, 24f, 14f, 20f, 11f), ink)
            drafts += writing(22f, 60f, listOf(12f, 26f, 8f, 17f, 22f), ink)
            drafts += writing(22f, 67f, listOf(21f, 15f, 30f, 10f), ink)
            drafts += polyline(listOf(20f to 60f, 68f to 60f), marker)
            drafts += TextBoxDraft(20f, 78f, 110f, 34f, list)
            // A sketch: two boxes and an arrow.
            drafts += polyline(listOf(140f to 82f, 185f to 82f, 185f to 100f, 140f to 100f, 140f to 82f), blue)
            drafts += polyline(listOf(140f to 120f, 185f to 120f, 185f to 138f, 140f to 138f, 140f to 120f), blue)
            drafts += polyline(listOf(162f to 101f, 162f to 118f), blue)
            drafts += polyline(listOf(158f to 113f, 162f to 118f, 166f to 113f), blue)
            drafts += writing(146f, 91f, listOf(10f, 14f), blue)
            drafts += writing(146f, 129f, listOf(16f, 8f), blue)
            drafts += AnchorDraft(20f, 148f, "Entscheidungen")
            drafts += writing(30f, 152f, listOf(14f, 22f, 9f, 18f), ink)
            drafts += TodoDraft(20f, 162f, 110f, TodoItem.MIN_HEIGHT_MM, TodoContent.Text(RichText.plain("Angebot an Lieferanten schicken")))
            drafts += TodoDraft(20f, 180f, 110f, TodoItem.MIN_HEIGHT_MM, TodoContent.Text(RichText.plain("Termin Testgruppe festlegen")))
            drafts += NoteLinkDraft(20f, 202f, ideas.id.asDocumentId, null, "Ideensammlung", null)
            // A table with a header row and two joined cells.
            run {
                fun id(n: Int) = StrokeId("tour-table-$n")
                val cols = listOf(TableColumn(id(1), 60f), TableColumn(id(2), 35f), TableColumn(id(3), 35f))
                val rows = listOf(TableRow(id(4)), TableRow(id(5)), TableRow(id(6)), TableRow(id(7)))
                fun cell(r: Int, c: Int, t: String) = CellKey(rows[r].rowId, cols[c].colId) to TableCell(RichText.plain(t), 5f)
                drafts += TableDraft(
                    20f, 214f, cols, rows, header = true,
                    cells = mapOf(
                        cell(0, 0, "Arbeitspaket"), cell(0, 1, "Wer"), cell(0, 2, "Bis"),
                        cell(1, 0, "Prototyp"), cell(1, 1, "Anna"), cell(1, 2, "30.11."),
                        cell(2, 0, "Testgruppe"), cell(2, 1, "Ben und Carla (gemeinsam)"),
                        cell(3, 0, "Budget"), cell(3, 1, "Dora"), cell(3, 2, "offen"),
                    ),
                    merges = listOf(CellMerge(rows[2].rowId, cols[1].colId, rows[2].rowId, cols[2].colId)),
                )
            }
            s.replaceElements(emptySet(), drafts)
            // The table again, alone at the top of its own document, for a close look.
            val plan = lib.createDocument(projects.id, "Planung", PageFormat(), lined)
            container.openSession(plan.id.asDocumentId).replaceElements(emptySet(), listOf((drafts.last() as TableDraft).copy(y = 40f)))
            planId = plan.id.asDocumentId
            val done = s.state.value.todos.first { (it.content as TodoContent.Text).text.plainText.startsWith("Termin") }
            s.setTodoDone(done.taskId, true)
            container.flushOpenSessions()
        }
        settings.lastDocument = main
        // Every colour scheme, light, and the chosen ones dark (NIBSYNC_TOUR_PALETTES=1).
        val variants = if (System.getenv("NIBSYNC_TOUR_PALETTES") == "1") {
            com.stemaker.nibsync.theme.NibSyncPalette.entries.map { it to false } + com.stemaker.nibsync.theme.NibSyncPalette.entries.map { it to true }
        } else {
            listOf(com.stemaker.nibsync.theme.NibSyncPalette.GREY to false)
        }
        for ((palette, dark) in variants) {
            val scene = ImageComposeScene(1600, 1000, Density(1f)) { App(container, settings, Strings(german = true), palette = palette, dark = dark) }
            var time = 0L
            repeat(80) { scene.render(time); time += 50_000_000L; Thread.sleep(30) }
            val name = if (variants.size == 1) "pc-01-note" else "pc-${palette.name.lowercase()}${if (dark) "-dark" else ""}"
            File(outDir, "$name.png").writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        settings.lastDocument = planId
        val tableScene = ImageComposeScene(1600, 1000, Density(1f)) { App(container, settings, Strings(german = true), palette = com.stemaker.nibsync.theme.NibSyncPalette.GREY, dark = false) }
        var t2 = 0L
        repeat(80) { tableScene.render(t2); t2 += 50_000_000L; Thread.sleep(30) }
        File(outDir, "pc-02-table.png").writeBytes(tableScene.render(t2).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        tableScene.close()
        runBlocking { container.closeAll() }
    }
}
