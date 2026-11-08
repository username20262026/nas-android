@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.juan.nas

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val BASE = "http://192.168.100.38:8000"
const val TOKEN = "miNAS2026"

val Morado = Color(0xFF6C4BFF)
val Azul = Color(0xFF2D9CDB)
val Naranja = Color(0xFFFF8A3D)
val Verde = Color(0xFF27AE60)
val Fondo = Color(0xFFF3F0FF)
val Gris = Color(0xFF6B7280)

data class Item(val nombre: String, val esCarpeta: Boolean, val tamano: Long?)
data class Volumen(val id: String, val nombre: String, val conectado: Boolean, val activo: Boolean)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

fun formatoTamano(bytes: Long?): String {
    if (bytes == null) return ""
    if (bytes < 1024) return "$bytes B"
    val u = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

fun leerTexto(c: HttpURLConnection): String {
    if (c.responseCode !in 200..299) throw Exception("código ${c.responseCode}")
    return c.inputStream.bufferedReader().readText()
}

fun listarRemoto(carpeta: String): List<Item> {
    val c = URL("$BASE/api/listar?carpeta=${enc(carpeta)}").openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer $TOKEN")
    val arr = JSONObject(leerTexto(c)).getJSONArray("elementos")
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Item(
            o.getString("nombre"),
            o.getBoolean("es_carpeta"),
            if (o.isNull("tamano")) null else o.getLong("tamano")
        )
    }
}

fun volumenesRemotos(): List<Volumen> {
    val c = URL("$BASE/api/volumenes").openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer $TOKEN")
    val arr = JSONArray(leerTexto(c))
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Volumen(o.getString("id"), o.getString("nombre"), o.getBoolean("conectado"), o.getBoolean("activo"))
    }
}

fun activarVolumenRemoto(id: String) {
    val c = URL("$BASE/api/volumen-activo?id=${enc(id)}").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.setRequestProperty("Authorization", "Bearer $TOKEN")
    leerTexto(c)
}

fun subirRemoto(carpeta: String, nombre: String, bytes: ByteArray) {
    val limite = "----NasLimite" + System.currentTimeMillis()
    val c = URL("$BASE/api/subir?carpeta=${enc(carpeta)}").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Authorization", "Bearer $TOKEN")
    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
    DataOutputStream(c.outputStream).use { out ->
        out.writeBytes("--$limite\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"archivo\"; filename=\"$nombre\"\r\n")
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        out.write(bytes)
        out.writeBytes("\r\n--$limite--\r\n")
    }
    if (c.responseCode !in 200..299) throw Exception("código ${c.responseCode}")
}

fun descargarRemoto(ruta: String, destino: File) {
    val c = URL("$BASE/api/descargar?ruta=${enc(ruta)}").openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer $TOKEN")
    if (c.responseCode !in 200..299) throw Exception("código ${c.responseCode}")
    c.inputStream.use { input -> destino.outputStream().use { input.copyTo(it) } }
}

fun nombreArchivo(resolver: ContentResolver, uri: Uri): String {
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) return cursor.getString(i)
        }
    }
    return "archivo_${System.currentTimeMillis()}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Morado,
                    secondary = Azul,
                    background = Fondo,
                    surface = Color.White
                )
            ) {
                NasScreen()
            }
        }
    }
}

@Composable
fun NasScreen() {
    val context: Context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    var volumenes by remember { mutableStateOf<List<Volumen>>(emptyList()) }
    var carpeta by remember { mutableStateOf("") }
    var elementos by remember { mutableStateOf<List<Item>>(emptyList()) }
    var mensaje by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(false) }
    var recarga by remember { mutableStateOf(0) }
    var detalle by remember { mutableStateOf<Item?>(null) }
    var actualizacion by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(recarga) {
        try {
            volumenes = withContext(Dispatchers.IO) { volumenesRemotos() }
        } catch (e: Exception) {
            mensaje = "No se pudieron leer los volúmenes: ${e.message}"
        }
    }

    LaunchedEffect(carpeta, recarga) {
        cargando = true
        try {
            elementos = withContext(Dispatchers.IO) { listarRemoto(carpeta) }
            mensaje = ""
        } catch (e: Exception) {
            mensaje = "No se pudo listar: ${e.message}"
        }
        cargando = false
    }

    fun rutaDe(item: Item): String = if (carpeta.isEmpty()) item.nombre else "$carpeta/${item.nombre}"

    fun descargar(item: Item) {
        scope.launch {
            cargando = true
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val archivo = File(dir, item.nombre)
                withContext(Dispatchers.IO) { descargarRemoto(rutaDe(item), archivo) }
                mensaje = "✅ Guardado en el teléfono: ${item.nombre}"
            } catch (e: Exception) {
                mensaje = "❌ Error al descargar: ${e.message}"
            }
            cargando = false
        }
    }

    fun buscarActualizacion() {
        scope.launch {
            cargando = true
            try {
                val ultima = withContext(Dispatchers.IO) { Updater.ultimaVersion() }
                val actual = Updater.versionActual(context)
                when {
                    ultima == null -> mensaje = "No hay ninguna versión publicada en GitHub"
                    Updater.esMasNueva(ultima.version, actual) -> actualizacion = ultima
                    else -> mensaje = "✅ Ya tienes la última versión ($actual)"
                }
            } catch (e: Exception) {
                mensaje = "❌ No se pudo buscar actualización: ${e.message}"
            }
            cargando = false
        }
    }

    fun actualizar(release: Updater.Release) {
        actualizacion = null
        scope.launch {
            cargando = true
            try {
                val apk = File(context.getExternalFilesDir(null) ?: context.filesDir, "update.apk")
                withContext(Dispatchers.IO) { Updater.descargarApk(release.apkUrl, apk) }
                Updater.instalar(context, apk)
            } catch (e: Exception) {
                mensaje = "❌ Error al actualizar: ${e.message}"
            }
            cargando = false
        }
    }

    val selector = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            cargando = true
            try {
                val nombre = nombreArchivo(resolver, uri)
                withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                    subirRemoto(carpeta, nombre, bytes)
                }
                mensaje = "✅ Subido: $nombre"
                recarga++
            } catch (e: Exception) {
                mensaje = "❌ Error al subir: ${e.message}"
            }
            cargando = false
        }
    }

    Scaffold(
        containerColor = Fondo,
        topBar = {
            TopAppBar(
                title = { Text("Mi NAS", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Morado),
                actions = {
                    TextButton(onClick = { buscarActualizacion() }) {
                        Text("Actualizar", color = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { selector.launch("*/*") },
                containerColor = Naranja,
                contentColor = Color.White,
                icon = { Text("⬆") },
                text = { Text("Subir") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                volumenes.forEach { v ->
                    val color = when {
                        v.activo -> Verde
                        v.conectado -> Azul
                        else -> Gris
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { activarVolumenRemoto(v.id) }
                                    carpeta = ""
                                    recarga++
                                } catch (e: Exception) {
                                    mensaje = "No se pudo cambiar: ${e.message}"
                                }
                            }
                        },
                        enabled = v.conectado && !v.activo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color,
                            disabledContainerColor = color,
                            disabledContentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(v.nombre + if (v.activo) "  ✔" else "")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (carpeta.isNotEmpty()) {
                        TextButton(onClick = { carpeta = carpeta.substringBeforeLast('/', "") }) {
                            Text("⬅ Atrás", color = Morado, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "/$carpeta",
                        color = Gris,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (cargando) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = Naranja
                )
            }
            if (mensaje.isNotEmpty()) {
                Text(
                    mensaje,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Morado,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
            ) {
                items(elementos) { item ->
                    FilaArchivo(
                        item = item,
                        onAbrir = {
                            carpeta = if (carpeta.isEmpty()) item.nombre else "$carpeta/${item.nombre}"
                        },
                        onDescargar = { descargar(item) },
                        onDetalles = { detalle = item }
                    )
                }
            }
        }
    }

    detalle?.let { item ->
        AlertDialog(
            onDismissRequest = { detalle = null },
            title = { Text(item.nombre, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Tipo: ${if (item.esCarpeta) "Carpeta" else "Archivo"}\n" +
                    "Tamaño: ${formatoTamano(item.tamano).ifEmpty { "—" }}\n" +
                    "Ubicación: /$carpeta"
                )
            },
            confirmButton = {
                TextButton(onClick = { detalle = null }) { Text("Cerrar") }
            }
        )
    }

    actualizacion?.let { rel ->
        AlertDialog(
            onDismissRequest = { actualizacion = null },
            title = { Text("Nueva versión ${rel.version}", fontWeight = FontWeight.Bold) },
            text = { Text("Hay una actualización disponible. ¿Quieres descargarla e instalarla?") },
            confirmButton = {
                Button(
                    onClick = { actualizar(rel) },
                    colors = ButtonDefaults.buttonColors(containerColor = Verde)
                ) { Text("Actualizar") }
            },
            dismissButton = {
                TextButton(onClick = { actualizacion = null }) { Text("Después") }
            }
        )
    }
}

@Composable
fun FilaArchivo(
    item: Item,
    onAbrir: () -> Unit,
    onDescargar: () -> Unit,
    onDetalles: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (item.esCarpeta) onAbrir() else onDetalles() },
                    onLongClick = { menu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (item.esCarpeta) Naranja else Azul),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (item.esCarpeta) "📁" else "📄", fontSize = 20.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.nombre,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (item.esCarpeta) "Carpeta · toca para abrir" else formatoTamano(item.tamano),
                        color = Gris,
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = { menu = true }) {
                    Text("⋮", fontSize = 22.sp, color = Gris)
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.esCarpeta) {
                DropdownMenuItem(
                    text = { Text("Abrir") },
                    onClick = { menu = false; onAbrir() }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Descargar al teléfono") },
                    onClick = { menu = false; onDescargar() }
                )
            }
            DropdownMenuItem(
                text = { Text("Ver detalles") },
                onClick = { menu = false; onDetalles() }
            )
        }
    }
}