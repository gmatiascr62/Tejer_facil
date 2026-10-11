package com.tejerfacil.app

import android.app.Application
import android.util.Log
import com.tejerfacil.app.data.FileStore
import com.tejerfacil.app.data.PatternRepository
import com.tejerfacil.app.data.SettingsRepository
import com.tejerfacil.app.data.db.AppDatabase
import com.tejerfacil.app.processing.DiagramProcessor
import com.tejerfacil.app.processing.ImportManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Dependencias de la aplicación (inyección manual, sin frameworks). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob())
    val db = AppDatabase.create(app)
    val files = FileStore(app)
    val settings = SettingsRepository(app)
    val repo = PatternRepository(db, files, settings)
    val processor = DiagramProcessor(db, files)
    val importer = ImportManager(app, db, files, repo, processor, scope)
}

class TejerFacilApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("opencv_java4")
        } catch (t: Throwable) {
            Log.e("TejerFacil", "No se pudo cargar OpenCV", t)
        }
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
    }
}
