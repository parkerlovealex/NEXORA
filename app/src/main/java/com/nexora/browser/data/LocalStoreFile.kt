package com.nexora.browser.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

internal class LocalStoreReadException(message: String, cause: Exception? = null) : IOException(message, cause) {
    constructor(file: File, cause: Exception) : this("Could not read " + file.name + ". Your saved data was kept.", cause)
}

internal class LocalStoreFile(
    val path: File,
    private val openRead: () -> InputStream,
    private val replace: (ByteArray) -> Unit
) {
    fun <T> readRows(decode: (JSONObject) -> T): List<T> {
        val contents = try {
            openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: FileNotFoundException) {
            // AtomicFile may still have the last committed version in its legacy backup.
            if (listOf(path, File(path.path + ".bak"), File(path.path + ".new")).all {
                    Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
                }) return emptyList()
            throw LocalStoreReadException(path, error)
        } catch (error: Exception) {
            throw LocalStoreReadException(path, error)
        }
        return try {
            val parser = JSONTokener(contents)
            val rows = parser.nextValue() as JSONArray
            require(parser.nextClean() == '\u0000') { "Unexpected data after the last row" }
            List(rows.length()) { index -> decode(rows.getJSONObject(index)) }
        } catch (error: Exception) {
            throw LocalStoreReadException(path, error)
        }
    }

    fun write(rows: JSONArray): Boolean = try {
        replace(rows.toString().toByteArray(Charsets.UTF_8))
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        fun android(path: File): LocalStoreFile {
            val atomic = AtomicFile(path)
            return LocalStoreFile(path, atomic::openRead) { bytes ->
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }
    }
}
