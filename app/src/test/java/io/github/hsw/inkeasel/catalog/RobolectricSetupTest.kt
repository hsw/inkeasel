package io.github.hsw.inkeasel.catalog

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Robolectric setup the catalog database tests stand on: the target SDK (`robolectric.properties`), an
 * application context, and a working framework SQLite (Room runs on it). Robolectric's SQLite is newer than
 * the one on minSdk 26 (3.18), so a passing test does not prove a newer SQL feature exists on old devices.
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricSetupTest {
    @Test
    fun runsAtTheTargetSdk() {
        assertEquals(36, Build.VERSION.SDK_INT)
        assertNotNull(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun frameworkSqliteWorksInMemory() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE t (id TEXT PRIMARY KEY, rank INTEGER NOT NULL)")
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO t VALUES ('wd:Q12418', 10), ('wd:Q45585', 20)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.rawQuery("SELECT id FROM t ORDER BY rank DESC", null).use { c ->
                val ids = buildList { while (c.moveToNext()) add(c.getString(0)) }
                assertEquals(listOf("wd:Q45585", "wd:Q12418"), ids)
            }
        } finally {
            db.close()
        }
    }
}
