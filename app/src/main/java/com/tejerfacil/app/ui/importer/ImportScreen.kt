package com.tejerfacil.app.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tejerfacil.app.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(container: AppContainer, onDone: (Long?) -> Unit, onBack: () -> Unit) {
    val st by container.importer.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(st?.finished, st?.error) {
        val s = st
        if (s != null && s.finished && s.error == null) {
            if (s.pageCount == 0) {
                android.widget.Toast.makeText(context, s.stage, android.widget.Toast.LENGTH_LONG).show()
            }
            val id = s.patternId
            container.importer.dismiss()
            onDone(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importación y análisis") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val s = st
            if (s == null) {
                Text("No hay ninguna importación en curso.")
                Button(onClick = onBack) { Text("Volver") }
                return@Column
            }
            Text(s.fileName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(s.stage, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
            if (s.pageCount > 0) Text("Página ${minOf(s.page + 1, s.pageCount)} de ${s.pageCount}")
            Text("Diagramas encontrados: ${s.diagramsFound}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Se separan los diagramas de cada página, se corrige su orientación y se reconocen las filas. " +
                    "El PDF original se guarda sin cambios. Podés salir de esta pantalla: el análisis sigue en segundo plano.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (s.error != null) {
                Text(s.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { container.importer.dismiss(); onDone(null) }) { Text("Aceptar") }
            } else if (s.running) {
                OutlinedButton(onClick = { container.importer.cancel() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Cancelar importación")
                }
            }
        }
    }
}
