# Tejer Fácil

Aplicación Android nativa (Kotlin + Jetpack Compose) para **seguir patrones de tejido en PDF**:
separa automáticamente los diagramas de cada página, los endereza, reconoce sus filas y
resalta la fila activa manteniéndola siempre en el centro de la pantalla.

Funciona **100 % sin conexión**: no usa servidores, APIs ni permisos de red. Todo el análisis
se hace en el teléfono con OpenCV.

## Descargar el APK

Cada push al repositorio ejecuta el workflow **Android APK** (`.github/workflows/android.yml`):

1. Entrar a la pestaña **Actions** del repositorio.
2. Abrir la última ejecución en verde de *Android APK*.
3. Al final de la página, en **Artifacts**, descargar `TejerFacil-debug-apk` (es un .zip con el `.apk`).
4. Instalarlo en el teléfono (Android 10 o superior). Hay que permitir la instalación desde
   "orígenes desconocidos". Es un APK *debug* firmado con la clave de depuración estándar; no
   requiere secretos.

## Funciones

| Función | Estado |
|---|---|
| Importar PDF con el selector del sistema; el original se copia sin modificar | Implementado |
| Renderizado de todas las páginas (PdfRenderer/PDFium) y progreso de análisis por página | Implementado |
| Detección y separación de **varios diagramas por página** | Implementado (ver resultados) |
| Descarte de texto, tablas, leyendas y fotografías | Implementado |
| Conservación de números de fila/columna y marcas cercanas en el recorte | Implementado |
| Orientación automática (0/90/180/270°) | Implementado: texto del PDF → OCR de dígitos → disposición |
| Reconocimiento de filas (alturas y anchos distintos, formas irregulares) | Implementado |
| Lectura de la numeración original de las filas | Implementado (texto del PDF u OCR de dígitos) |
| Orden de lectura (abajo→arriba o arriba→abajo según la numeración) | Implementado |
| Lector vertical con resaltado semitransparente configurable (color y opacidad) | Implementado |
| Fila activa **siempre centrada**, con animación y respetando el zoom | Implementado |
| Zoom con dos dedos, desplazamiento, doble toque, ajustar al ancho, restablecer | Implementado |
| Tocar una fila para seleccionarla; botones grandes "Fila anterior / siguiente" | Implementado |
| Avanzar/retroceder con volumen +/− (opcional, solo en el lector) | Implementado |
| Crear una copia editable de un patrón (el original no se modifica) | Implementado |
| Editor de símbolos en copias: copiar, pegar (reemplaza) y borrar celdas; deshacer/rehacer; paleta de símbolos guardados | Implementado |
| Selección de varias celdas arrastrando el dedo; borrar la selección o copiarla como bloque y pegarla en otro lugar | Implementado |
| Editar columnas en copias: insertar una columna en blanco a la izquierda o derecha (agranda el gráfico) o borrar una columna | Implementado (deshacer: solo el último cambio de columnas) |
| Girar un diagrama 90° (menú ⋮ del lector o mantener apretado en la galería); se vuelven a reconocer las filas | Implementado |
| Botón para girar la pantalla en el lector (solo gira si se toca; al salir vuelve a vertical) | Implementado |
| Abrir un PDF desde el administrador de archivos ("Abrir con…") o "Compartir" | Implementado (si ya estaba importado, abre el existente) |
| Editor de recortes: mover bordes, girar, agregar, eliminar, dividir, unir | Implementado |
| Editor de filas: mover bordes, agregar, dividir en N, unir, eliminar, numerar, orden, re-detectar | Implementado |
| Guardado automático: fila, zoom, posición, color, orientación, filas y recortes (por diagrama) | Implementado (Room) |
| Interfaz en español, siempre vertical | Implementado |

## Cómo funciona la detección

Código en el módulo `core` (Kotlin/JVM puro + OpenCV, probado en la JVM):

1. **Máscara de tinta** con umbral adaptativo (capta líneas grises claras; los rellenos de color
   quedan como contornos).
2. **Líneas finas horizontales y verticales** por apertura morfológica, descartando zonas gruesas.
3. **Intersecciones** de ambas máscaras, agrupadas por proximidad (cada grupo es una retícula
   candidata). Se valida que: haya ≥ 4 líneas en cada sentido, separación local regular, celdas
   aproximadamente cuadradas, líneas realmente continuas entre cruces, varios cruces por línea,
   sin texto dentro de las celdas (tablas) y sin textura fotográfica.
4. Fragmentos de un mismo gráfico se unen; luego el recorte se **amplía** para incluir los
   números y marcas a menos de ~1,7 celdas (sin tomar párrafos de texto).
5. **Orientación**: si el PDF tiene texto vectorial se usa la dirección de los números; si no,
   un **OCR de dígitos por plantillas** prueba las cuatro rotaciones de cada número que rodea al
   gráfico y vota.
6. **Filas**: en la imagen ya derecha se buscan líneas horizontales con cruces y trazo continuo;
   cada par de líneas consecutivas que se superponen forma una fila, con el ancho dado por las
   verticales que la atraviesan. No se inventan filas: una fila alta solo se divide si hay una
   línea visible. Si no hay cuadrícula, se usa el perfil de tinta y se marca "Revisar".
7. Los números leídos se asignan a la fila que tienen al lado y se descartan los incoherentes.

Las filas se guardan **normalizadas a la imagen del diagrama**. El lector dibuja la imagen y el
resaltado con la misma transformación, así que el resaltado queda alineado en cualquier zoom.

## Resultados con los PDF de prueba

Se validó localmente con los dos PDF reales (no se incluyen en el repositorio porque tienen
derechos de autor), renderizados con PDFium igual que en Android:

- **Pelele Jasmine (18 págs.)**: 12 páginas con diagrama → 12 diagramas, 0 falsos positivos en las
  6 páginas de texto. Los gráficos de delantera/trasera (95–150 filas, forma octogonal, cruces de
  ochos, marca de agua) salen como un solo diagrama cada uno. Las dos páginas con el gráfico
  acostado (págs. 16 y 17) se enderezan con OCR (270°). Numeración original leída en el 65–97 % de
  las filas según el gráfico.
- **Jersey Tulip (20 págs.)**: 34 diagramas en 14 páginas, con 2 o 3 diagramas por página
  (págs. 4, 5, 7, 8, 12, 16, 20 con tres). Los 13 gráficos acostados quedan derechos (270°) y el
  resto a 0°. Filas con altura uniforme en todos los gráficos. Numeración leída en el 45–100 % de
  las filas de los gráficos derechos y en el 25–80 % de los acostados (sus números son más chicos).

En las pruebas automáticas (`core/src/test`) se generan PDF sintéticos con: un diagrama, varios
diagramas con tabla/foto/leyenda, diagramas girados (con y sin texto, por OCR), diagramas
triangulares/escalonados y filas de alturas distintas. La app tiene pruebas de persistencia del
progreso (Robolectric + Room) y del posicionamiento del resaltado con zoom (`ViewportMathTest`).

## Limitaciones conocidas (requieren corrección manual)

- **Editor de símbolos**: trabaja sobre la imagen del diagrama (no reescribe el PDF). Las celdas se
  ubican con las líneas de la cuadrícula; si una celda no tiene líneas visibles se usa una celda
  aproximada. El borrado deja la celda en blanco (no recupera fondos de color). Deshacer/rehacer
  vale mientras el editor está abierto. Si después se cambia el recorte de ese diagrama en el
  editor de recortes, se vuelve a generar desde el PDF y las ediciones se pierden (la app avisa).

- **Filas de más o de menos en casos puntuales**: tiras de números de columna dibujadas *dentro*
  de la cuadrícula (p. ej. a mitad del gráfico) pueden contarse como una fila (Jasmine trasera
  9–12: 151 filas detectadas para 137 reales), y en algunos gráficos falta alguna fila
  (Jasmine delantera 0–1 mes: 97 de 99). Se corrige en el editor de filas.
- **Gráficos sin líneas de cuadrícula** (solo símbolos o celdas de color sin bordes): no se
  detectan automáticamente; hay que marcarlos en el editor de recortes. Sus filas se estiman por
  el perfil de tinta y se marcan "Revisar".
- **Orientación 180°** sin texto en el PDF: se decide por OCR; si los números son ilegibles se
  asume 0° (se corrige con "Girar 90°").
- **OCR de dígitos**: diseñado para números de imprenta; no lee manuscritos, y algunos números
  pueden quedar sin leer (la fila sigue funcionando, solo sin "nº").
- **Diagramas partidos entre dos páginas** no se unen en uno solo.
- **Dos gráficos muy pegados** (menos de ~1 celda de separación y alineados) pueden salir unidos;
  se separan con "Dividir".
- El recorte es rectangular: un gráfico con forma irregular conserva el espacio en blanco a su
  alrededor (no se modifica el dibujo).
- Rendimiento: unos 0,1–0,2 s de análisis por página y 0,2–1 s por diagrama en una PC; en el
  teléfono puede ser varias veces más lento.

## Estructura

```
core/   Visión artificial (OpenCV): detect/, rows/, ocr/, viewer/, geometry/, text/
app/    Android: data/ (Room, DataStore, archivos), pdf/ (PdfRenderer, PdfBox-Android),
        processing/ (importación y procesamiento), ui/ (Compose: biblioteca, importación,
        galería, lector, editores, configuración)
tools/  gen_digit_templates.py (plantillas del OCR de dígitos)
```

## Compilar localmente

Requiere JDK 17 y Android SDK 35:

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Para validar con PDF propios (sin Android): renderizar páginas a PNG (200 ppp) en una carpeta y
ejecutar `./gradlew :core:test --tests '*RealPagesProbe*' -DrealPages=/carpeta -DdebugOut=/salida`.
