package com.example.taller3

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import com.bumptech.glide.Glide
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ManejadorImagenes {

    fun mostrarImagenDesdeUrl(url: String, imageView: ImageView, context: Context) {
        Glide.with(context)
            .load(url)
            .placeholder(R.drawable.ic_profile) // Mientras carga
            .error(R.drawable.ic_profile)       // Si hay error
            .into(imageView)
    }

    fun subirImagen(
        context: Context,
        apiKey: String,
        imageUri: Uri,
        callback: (Boolean, String?) -> Unit
    ) {
        // 1. Crear cliente
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // 2. Leer bytes del Uri
        val bytes: ByteArray = try {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: run {
                    callback(false, "No se pudo abrir el Uri")
                    return
                }
        } catch (e: Exception) {
            callback(false, "Error leyendo el Uri: ${e.message}")
            return
        }

        // 3. Intentar obtener un nombre de archivo legible
        val fileName: String = runCatching {
            val cursor = context.contentResolver.query(imageUri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        }.getOrNull() ?: "image_${System.currentTimeMillis()}.jpg"

        // 4. Cuerpo de la imagen
        val imageBody = bytes.toRequestBody("image/*".toMediaTypeOrNull())

        // 5. Multipart
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", apiKey)
            .addFormDataPart("image", fileName, imageBody)
            .build()

        // 6. Petición
        val request = Request.Builder()
            .url("https://api.imgbb.com/1/upload")
            .post(requestBody)
            .build()

        // 7. Ejecución asíncrona
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(false, "Unexpected code $response")
                    } else {
                        callback(true, response.body?.string())
                    }
                }
            }
        })
    }
}