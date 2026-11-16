package com.example.denemtakip

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "denemeler.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE deneme (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "tur TEXT NOT NULL," +
                "tarih TEXT NOT NULL," +
                "dogru INTEGER NOT NULL," +
                "yanlis INTEGER NOT NULL," +
                "bos INTEGER NOT NULL," +
                "not_ TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}
