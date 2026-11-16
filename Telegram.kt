package com.example.denemtakip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Telegram {

    suspend fun sendMessage(token: String, chatId: String, text: String): String =
        withContext(Dispatchers.IO) {
            val conn = (URL("https://api.telegram.org/bot$token/sendMessage")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }

            val govde = "chat_id=${enc(chatId)}&text=${enc(text)}&disable_web_page_preview=true"
            conn.outputStream.use { it.write(govde.toByteArray(Charsets.UTF_8)) }

            val kod = conn.responseCode
            val yanit = (if (kod in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (kod !in 200..299) throw IOException("HTTP $kod $yanit")
            yanit
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
