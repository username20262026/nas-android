package com.juan.nas

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    // Cambia TU_USUARIO por tu usuario de GitHub y nas-android por el nombre del repositorio
    const val REPO = "username20262026/nas-android"

    data class Release(val version: String, val apkUrl: String)

    fun ultimaVersion(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty/("User-Agent", "NasApp")
        if (c.responseCode !in 200..299) throw Exception("GitHub respondió ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().readText())
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return Release(version, a.getString("browser_download_url"))
            }
        }
        return null
    }

    fun versionActual(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    fun esMasNueva(nueva: String, actual: String): Boolean {
        val a = nueva.split(".").map { it.toIntOrNull() ?: 0 }
        val b = actual.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun descargarApk(url: String, destino: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "NasApp")
        if (c.responseCode !in 200..299) throw Exception("código ${c.responseCode}")
        c.inputStream.use { input -> destino.outputStream().use { input.copyTo(it) } }
    }

    fun instalar(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}