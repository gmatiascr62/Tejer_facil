package com.tejerfacil.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.data.db.DiagramEntity
import com.tejerfacil.app.data.db.PatternEntity
import com.tejerfacil.app.data.db.ProgressEntity
import com.tejerfacil.app.data.db.RowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProgressPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PatternRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemory(ctx)
        repo = PatternRepository(db, FileStore(ctx), SettingsRepository(ctx))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newPattern(): Long = db.patterns().insert(
        PatternEntity(name = "Jersey", fileName = "jersey.pdf", pdfPath = "/tmp/x.pdf", pageCount = 3, importedAt = 1L, status = "READY")
    )

    private suspend fun newDiagram(patternId: Long, page: Int): Long = db.diagrams().insert(
        DiagramEntity(patternId = patternId, pageIndex = page, indexInPage = 0, name = "D$page",
            cropLeft = 0.1, cropTop = 0.1, cropRight = 0.9, cropBottom = 0.5, rotation = 90)
    )

    @Test
    fun elProgresoSeGuardaYEsIndependientePorDiagrama() = runTest {
        val pid = newPattern()
        val d1 = newDiagram(pid, 0)
        val d2 = newDiagram(pid, 1)
        db.rows().replace(d1, (0 until 40).map { RowEntity(diagramId = d1, orderIndex = it, top = it / 40.0, bottom = (it + 1) / 40.0, left = 0.0, right = 1.0) })
        db.rows().replace(d2, (0 until 10).map { RowEntity(diagramId = d2, orderIndex = it, top = it / 10.0, bottom = (it + 1) / 10.0, left = 0.0, right = 1.0) })

        val p1 = repo.progressFor(d1)
        repo.saveProgress(p1.copy(currentRow = 17, zoom = 2.5f, centerX = 0.3f, centerY = 0.62f, highlightColor = 0xFF4CAF50.toInt(), highlightAlpha = 0.5f))
        val p2 = repo.progressFor(d2)
        repo.saveProgress(p2.copy(currentRow = 3))

        val r1 = db.progress().get(d1)!!
        assertEquals(17, r1.currentRow)
        assertEquals(2.5f, r1.zoom)
        assertEquals(0.3f, r1.centerX)
        assertEquals(0.62f, r1.centerY)
        assertEquals(0xFF4CAF50.toInt(), r1.highlightColor)
        assertEquals(0.5f, r1.highlightAlpha)
        assertEquals(3, db.progress().get(d2)!!.currentRow)

        // La galería ve el progreso de cada diagrama.
        val items = db.diagrams().observeForPattern(pid).first()
        assertEquals(listOf(17, 3), items.map { it.currentRow })
        assertEquals(listOf(40, 10), items.map { it.rowCount })
        assertEquals(90, items[0].diagram.rotation)
    }

    @Test
    fun alReducirLasFilasLaFilaActualSeAjusta() = runTest {
        val pid = newPattern()
        val d = newDiagram(pid, 0)
        db.progress().upsert(ProgressEntity(diagramId = d, currentRow = 30))
        repo.saveRows(d, (0 until 12).map { RowEntity(diagramId = d, orderIndex = it, top = 0.0, bottom = 0.1, left = 0.0, right = 1.0) }, "TOP_DOWN")
        assertEquals(11, db.progress().get(d)!!.currentRow)
        assertEquals("TOP_DOWN", db.diagrams().get(d)!!.readingOrder)
        assertEquals(12, db.rows().listForDiagram(d).size)
    }

    @Test
    fun crearCopiaDuplicaDiagramasFilasYArchivosSinTocarElOriginal() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val files = FileStore(ctx)
        val pid = newPattern()
        val pdf = files.pdfFile(pid).apply { writeText("%PDF-1.4 prueba") }
        db.patterns().update(db.patterns().get(pid)!!.copy(pdfPath = pdf.absolutePath))
        val d = newDiagram(pid, 2)
        val img = files.diagramImage(pid, d, 1).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val thumb = files.diagramThumb(pid, d, 1).apply { writeBytes(byteArrayOf(4, 5)) }
        db.diagrams().update(db.diagrams().get(d)!!.copy(imagePath = img.absolutePath, thumbPath = thumb.absolutePath))
        db.rows().replace(d, (0 until 5).map { RowEntity(diagramId = d, orderIndex = it, top = it / 5.0, bottom = (it + 1) / 5.0, left = 0.0, right = 1.0, label = it + 1) })
        repo.saveProgress(repo.progressFor(d).copy(currentRow = 3, highlightColor = 0xFF29B6F6.toInt()))

        val copyId = repo.duplicatePattern(pid)
        val copy = db.patterns().get(copyId)!!
        assertEquals("Jersey (copia)", copy.name)
        assertEquals(pid, copy.copyOf)
        assertNotEquals(pdf.absolutePath, copy.pdfPath)
        assertEquals("%PDF-1.4 prueba", java.io.File(copy.pdfPath).readText())

        val cd = db.diagrams().listForPattern(copyId).single()
        assertEquals(2, cd.pageIndex)
        assertNotEquals(d, cd.id)
        assertNotEquals(img.absolutePath, cd.imagePath)
        assertEquals(listOf<Byte>(1, 2, 3), java.io.File(cd.imagePath).readBytes().toList())
        assertEquals((1..5).toList(), db.rows().listForDiagram(cd.id).map { it.label })
        // Progreso nuevo, con el mismo color.
        val cp = db.progress().get(cd.id)!!
        assertEquals(0, cp.currentRow)
        assertEquals(0xFF29B6F6.toInt(), cp.highlightColor)
        // Borrar la copia no afecta al original.
        repo.deletePattern(copyId)
        assertEquals(3, db.progress().get(d)!!.currentRow)
        assertEquals(true, img.exists())
    }

    @Test
    fun borrarElPatronBorraDiagramasFilasYProgreso() = runTest {
        val pid = newPattern()
        val d = newDiagram(pid, 0)
        db.rows().replace(d, listOf(RowEntity(diagramId = d, orderIndex = 0, top = 0.0, bottom = 0.1, left = 0.0, right = 1.0)))
        repo.progressFor(d)
        repo.deletePattern(pid)
        assertNull(db.diagrams().get(d))
        assertNull(db.progress().get(d))
        assertEquals(0, db.rows().listForDiagram(d).size)
    }
}
