package com.tejerfacil.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** PDF importado. El archivo original se copia sin modificar a [pdfPath]. */
@Entity(tableName = "patterns")
data class PatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fileName: String,
    val pdfPath: String,
    val pageCount: Int,
    val importedAt: Long,
    /** PROCESSING, READY, ERROR */
    val status: String,
    val message: String? = null,
    /** Si es una copia editable, id del patrón original. */
    val copyOf: Long? = null,
)

/**
 * Diagrama separado de una página. El recorte se guarda en coordenadas
 * normalizadas de la página (0..1) para poder re-renderizarlo a cualquier
 * resolución; [imagePath] es la imagen ya recortada y girada.
 */
@Entity(
    tableName = "diagrams",
    foreignKeys = [ForeignKey(entity = PatternEntity::class, parentColumns = ["id"], childColumns = ["patternId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("patternId")],
)
data class DiagramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patternId: Long,
    val pageIndex: Int,
    val indexInPage: Int,
    val name: String,
    val cropLeft: Double,
    val cropTop: Double,
    val cropRight: Double,
    val cropBottom: Double,
    /** Tamaño de celda estimado relativo al ancho/alto de la página (0 = desconocido). */
    val cellWidthNorm: Double = 0.0,
    val cellHeightNorm: Double = 0.0,
    /** Rotación horaria aplicada (0, 90, 180, 270). */
    val rotation: Int = 0,
    /** PDF_TEXT, OCR, LAYOUT, DEFAULT, MANUAL */
    val rotationSource: String = "DEFAULT",
    val rotationConfidence: Double = 0.0,
    val rotationLocked: Boolean = false,
    val imagePath: String = "",
    val thumbPath: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val detectionConfidence: Double = 0.0,
    val manual: Boolean = false,
    /** BOTTOM_UP o TOP_DOWN */
    val readingOrder: String = "BOTTOM_UP",
    /** GRID_LINES, INK_PROFILE, NONE, MANUAL */
    val rowMethod: String = "NONE",
    val rowConfidence: Double = 0.0,
    val notes: String = "",
    /** La imagen tiene símbolos editados por el usuario (no se re-renderiza desde el PDF). */
    @ColumnInfo(defaultValue = "0") val edited: Boolean = false,
)

/** Fila del diagrama en coordenadas normalizadas de la imagen del diagrama. */
@Entity(
    tableName = "diagram_rows",
    foreignKeys = [ForeignKey(entity = DiagramEntity::class, parentColumns = ["id"], childColumns = ["diagramId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("diagramId")],
)
data class RowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diagramId: Long,
    /** Orden de lectura: 0 = primera fila a tejer. */
    val orderIndex: Int,
    val top: Double,
    val bottom: Double,
    val left: Double,
    val right: Double,
    /** Número impreso en el gráfico (si se pudo leer o el usuario lo indicó). */
    val label: Int? = null,
    val manual: Boolean = false,
)

/** Progreso independiente de cada diagrama. */
@Entity(
    tableName = "progress",
    foreignKeys = [ForeignKey(entity = DiagramEntity::class, parentColumns = ["id"], childColumns = ["diagramId"], onDelete = ForeignKey.CASCADE)],
)
data class ProgressEntity(
    @PrimaryKey val diagramId: Long,
    val currentRow: Int = 0,
    /** Zoom relativo a "ajustar al ancho". */
    val zoom: Float = 1f,
    /** Punto de la imagen (normalizado) que estaba en el centro de la pantalla. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Color ARGB del resaltado (sin alfa) y su opacidad. */
    val highlightColor: Int = 0xFFFFEB3B.toInt(),
    val highlightAlpha: Float = 0.35f,
    val updatedAt: Long = 0,
)

data class PatternSummary(
    @Embedded val pattern: PatternEntity,
    val diagramCount: Int,
)

data class DiagramListItem(
    @Embedded val diagram: DiagramEntity,
    val currentRow: Int?,
    val rowCount: Int,
    val patternName: String,
    @ColumnInfo(name = "progressUpdatedAt") val progressUpdatedAt: Long?,
)
