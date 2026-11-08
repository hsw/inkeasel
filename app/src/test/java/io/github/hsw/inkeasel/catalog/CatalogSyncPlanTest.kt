package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CHECK_EVERY_MS
import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class CatalogSyncPlanTest {
    private val ru = LanguageChain.of("ru-RU")
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun imported(name: String, at: Long = now, etag: String = "\"e-$name\"") =
        CatalogFileEntity(name, etag, "sha-$name", 10, checkedAt = at, importedAt = at)

    private fun names(checks: List<SyncCheck>) = checks.map { it.name }

    private fun downloaded(name: String, sha: String, etag: String? = "\"new\"") =
        SyncOutcome.Downloaded(SyncCheck(name, CatalogSyncPlan.langOf(name)), etag, sha, 42, File("x"))

    @Test
    fun theChainNeedsCoreThenTheLabelsOfEachStoredLanguage() {
        assertEquals(
            listOf(
                SyncCheck(CORE, null), SyncCheck("labels-ru.jsonl.gz", "ru"), SyncCheck("labels-en.jsonl.gz", "en"),
                SyncCheck("labels-mul.jsonl.gz", "mul"),
            ),
            CatalogSyncPlan.files(ru),
        )
        assertEquals(
            listOf(CORE, "labels-pt-br.jsonl.gz", "labels-pt.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz"),
            names(CatalogSyncPlan.files(LanguageChain.of("pt-BR"))),
        )
        // English adds no second English file
        assertEquals(
            listOf(CORE, "labels-en.jsonl.gz", "labels-mul.jsonl.gz"),
            names(CatalogSyncPlan.files(LanguageChain.of("en"))),
        )
    }

    @Test
    fun theFirstRunChecksEveryFileWithoutAnEtag() {
        val due = CatalogSyncPlan.due(ru, emptyMap(), now)
        assertEquals(names(CatalogSyncPlan.files(ru)), names(due))
        assertTrue(due.all { it.etag == null })
    }

    @Test
    fun aFileIsCheckedAgainAWeekAfterItsOwnCheck() {
        val stored = mapOf(
            CORE to imported(CORE, at = now - CHECK_EVERY_MS), // exactly a week: due
            "labels-ru.jsonl.gz" to imported("labels-ru.jsonl.gz", at = now - CHECK_EVERY_MS + 1), // not yet
            "labels-en.jsonl.gz" to imported("labels-en.jsonl.gz", at = now - 30 * day),
            "labels-mul.jsonl.gz" to imported("labels-mul.jsonl.gz", at = now - day),
        )
        val due = CatalogSyncPlan.due(ru, stored, now)
        assertEquals(listOf(CORE, "labels-en.jsonl.gz"), names(due))
        assertEquals(listOf("\"e-core.jsonl.gz\"", "\"e-labels-en.jsonl.gz\""), due.map { it.etag })
        assertTrue(CatalogSyncPlan.due(ru, stored.mapValues { imported(it.key) }, now).isEmpty())
    }

    @Test
    fun aCheckDatedInTheFutureCountsAsDue() {
        assertTrue(CatalogSyncPlan.isDue(imported(CORE, at = now + day), now))
        assertFalse(CatalogSyncPlan.isDue(imported(CORE, at = now), now))
        assertTrue(CatalogSyncPlan.isDue(CatalogFileEntity(CORE), now))
        assertTrue(CatalogSyncPlan.isDue(null, now))
    }

    @Test
    fun aNewChainLanguageIsFetchedAtOnceWhileTheOthersWait() {
        val stored = CatalogSyncPlan.files(ru).associate { it.name to imported(it.name, at = now - day) }
        val due = CatalogSyncPlan.due(LanguageChain.of("de-DE"), stored, now)
        assertEquals(listOf(SyncCheck("labels-de.jsonl.gz", "de")), due)
    }

    @Test
    fun aMissingFileIsCheckedAgainWeeklyAndAnEtagIsSentOnlyForImportedContent() {
        val missing = CatalogFileEntity("labels-mul.jsonl.gz", etag = "\"x\"", checkedAt = now - day, missing = true)
        val stored = CatalogSyncPlan.files(ru).associate { it.name to imported(it.name) } +
            ("labels-mul.jsonl.gz" to missing)
        assertTrue(CatalogSyncPlan.due(ru, stored, now).isEmpty())
        val later = CatalogSyncPlan.due(ru, stored, now - day + CHECK_EVERY_MS)
        assertEquals(listOf(SyncCheck("labels-mul.jsonl.gz", "mul", etag = null)), later)
    }

    @Test
    fun aDownloadIsImportedOnlyWhenItsHashDiffers() {
        val stored = imported(CORE)
        assertFalse(CatalogSyncPlan.changed(downloaded(CORE, "sha-core.jsonl.gz"), stored))
        assertTrue(CatalogSyncPlan.changed(downloaded(CORE, "other"), stored))
        assertTrue(CatalogSyncPlan.changed(downloaded(CORE, "other"), null))
        assertTrue(CatalogSyncPlan.changed(downloaded(CORE, "x"), CatalogFileEntity(CORE, missing = true)))
    }

    @Test
    fun aCheckWithoutAnImportRecordsOnlyTheCheck() {
        val stored = imported(CORE, at = now - 8 * day)
        val check = SyncCheck(CORE, null, stored.etag)

        assertEquals(stored.copy(checkedAt = now), CatalogSyncPlan.checked(stored, SyncOutcome.NotModified(check), now))
        // the same bytes under a new ETag: the ETag and the check change, the hash and import time stay
        assertEquals(
            stored.copy(etag = "\"new\"", size = 42, checkedAt = now),
            CatalogSyncPlan.checked(stored, downloaded(CORE, "sha-core.jsonl.gz"), now),
        )
        // a changed file is written by its import, not here
        assertNull(CatalogSyncPlan.checked(stored, downloaded(CORE, "other"), now))
        assertNull(CatalogSyncPlan.checked(stored, SyncOutcome.Failed(check, IOException("down")), now))
        assertEquals(
            stored.copy(checkedAt = now, missing = true),
            CatalogSyncPlan.checked(stored, SyncOutcome.Missing(check), now),
        )
        assertEquals(
            CatalogFileEntity("labels-mul.jsonl.gz", checkedAt = now, missing = true),
            CatalogSyncPlan.checked(null, SyncOutcome.Missing(SyncCheck("labels-mul.jsonl.gz", "mul")), now),
        )
        // back after a 404
        assertEquals(
            stored.copy(checkedAt = now, missing = false),
            CatalogSyncPlan.checked(stored.copy(missing = true), SyncOutcome.NotModified(check), now),
        )
    }

    @Test
    fun anImportRecordsTheEtagHashSizeAndTimes() {
        assertEquals(
            CatalogFileEntity(CORE, "\"new\"", "abc", 42, checkedAt = now, importedAt = now, missing = false),
            CatalogSyncPlan.imported(downloaded(CORE, "abc"), now),
        )
    }

    @Test
    fun theStateOfLabelsOutsideTheChainIsForgotten() {
        val names = listOf(
            CORE, "labels-ru.jsonl.gz", "labels-de.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz",
            "labels-pt-br.jsonl.gz", "labels-all.jsonl.gz", "other.txt",
        )
        assertEquals(listOf("labels-de.jsonl.gz", "labels-pt-br.jsonl.gz"), CatalogSyncPlan.outside(ru, names))
        assertEquals(listOf("labels-ru.jsonl.gz", "labels-pt-br.jsonl.gz"), CatalogSyncPlan.outside(LanguageChain.of("de"), names))
    }

    @Test
    fun labelsFileNames() {
        assertEquals("labels-zh-hant.jsonl.gz", CatalogSyncPlan.labels("zh-hant"))
        assertEquals("zh-hant", CatalogSyncPlan.langOf("labels-zh-hant.jsonl.gz"))
        assertNull(CatalogSyncPlan.langOf(CORE))
        assertNull(CatalogSyncPlan.langOf("labels-all.jsonl.gz"))
        assertNull(CatalogSyncPlan.langOf("labels-.jsonl.gz"))
        assertNull(CatalogSyncPlan.langOf("labels-ru.jsonl"))
    }
}
