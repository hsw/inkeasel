package io.github.hsw.inkeasel.catalog

import java.io.File

/**
 * A release file the sync checks: its [name] in the release, the language of a labels file ([lang]; `null`
 * for `core`) and the ETag to send as `If-None-Match` ([etag]; only for a file whose content was imported).
 */
data class SyncCheck(val name: String, val lang: String?, val etag: String? = null)

/** What the check of one file found. */
sealed interface SyncOutcome {
    val check: SyncCheck

    /** 304: the file is as last imported. */
    data class NotModified(override val check: SyncCheck) : SyncOutcome

    /** 200: the file, downloaded to [file], with its [etag], [sha256] (lowercase hex) and [size] in bytes. */
    data class Downloaded(
        override val check: SyncCheck,
        val etag: String?,
        val sha256: String,
        val size: Long,
        val file: File,
    ) : SyncOutcome

    /** 404: the release has no such file (a language Wikidata has no labels in, or a file not uploaded). */
    data class Missing(override val check: SyncCheck) : SyncOutcome

    /** Anything else (no network, a timeout, another HTTP code, a body cut short): checked again next start. */
    data class Failed(override val check: SyncCheck, val error: Throwable) : SyncOutcome
}

/**
 * Which release files the sync checks and what it records about them (pure). The chain needs `core` and the
 * `labels-<lang>` file of each language it stores ([CatalogImport.languages]: the chain, English, `mul`).
 * Each file is checked when it never was, or [CHECK_EVERY_MS] after its last check (a 404 included), so a
 * language that joins the chain is fetched at once. A file's ETag and SHA-256 are recorded only together with
 * the import of its content ([imported], inside the swap); a 304, an unchanged hash or a 404 records only the
 * check ([checked]); a failed request records nothing, so the file is checked again at the next start; a
 * download whose import failed is checked again [RETRY_FAILED_IMPORT_MS] later ([importFailed]), so a broken
 * upload or a full disk is not downloaded again at every start.
 */
object CatalogSyncPlan {
    const val CORE = "core.jsonl.gz"

    /** A week: how long a file's check holds. */
    const val CHECK_EVERY_MS = 7L * 24 * 60 * 60 * 1000

    /** A day: how long a file whose download could not be imported waits before it is checked again. */
    const val RETRY_FAILED_IMPORT_MS = 24L * 60 * 60 * 1000

    private const val LABELS_PREFIX = "labels-"
    private const val SUFFIX = ".jsonl.gz"

    fun labels(lang: String): String = "$LABELS_PREFIX$lang$SUFFIX"

    /** The language of a release `labels-<lang>` file name, `null` for any other file (`labels-all` too). */
    fun langOf(name: String): String? = labelsLang(name, SUFFIX)

    /**
     * The language of a `labels-<lang>[suffix]` file name (the release's or the starter's), `null` for any
     * other file or a code that is not a language's ([CatalogRows.isLang]; `all` is not).
     */
    fun labelsLang(name: String, suffix: String): String? =
        name.takeIf { it.startsWith(LABELS_PREFIX) && it.endsWith(suffix) }
            ?.substring(LABELS_PREFIX.length, name.length - suffix.length)
            ?.takeIf { CatalogRows.isLang(it) && it != ALL }

    /** The tool's file of every language at once; the sync never fetches it. */
    private const val ALL = "all"

    /** The files the [chain] needs: `core` first, then the labels of each stored language in chain order. */
    fun files(chain: List<String>): List<SyncCheck> =
        listOf(SyncCheck(CORE, null)) + CatalogImport.languages(chain).map { SyncCheck(labels(it), it) }

    /** The files to check [now] (milliseconds) given the [stored] state, in [files] order, each with its ETag. */
    fun due(chain: List<String>, stored: Map<String, CatalogFileEntity>, now: Long): List<SyncCheck> =
        files(chain).mapNotNull { check ->
            val state = stored[check.name]
            if (!isDue(state, now)) null
            else check.copy(etag = state?.takeIf { it.sha256 != null }?.etag)
        }

    /**
     * Never checked, checked a week or more ago (a day after a failed import, [importFailed]), or "checked"
     * in the future (the clock was set back).
     */
    fun isDue(state: CatalogFileEntity?, now: Long): Boolean {
        val at = state?.checkedAt ?: return true
        val every = if (state.importFailed) RETRY_FAILED_IMPORT_MS else CHECK_EVERY_MS
        return at > now || now - at >= every
    }

    /** Whether a downloaded file must be imported: its content differs from the one imported last. */
    fun changed(file: SyncOutcome.Downloaded, stored: CatalogFileEntity?): Boolean = stored?.sha256 != file.sha256

    /**
     * The state to store after [outcome] when no import is involved; `null` leaves the state as it is (a
     * failure, or a changed file, whose state only its import writes: [imported]). An unchanged file keeps its
     * hash and import time and takes the new ETag: GitHub gives an uploaded file a new one even when its
     * bytes are the same.
     */
    fun checked(stored: CatalogFileEntity?, outcome: SyncOutcome, now: Long): CatalogFileEntity? {
        val base = stored ?: CatalogFileEntity(outcome.check.name)
        val checked = base.copy(checkedAt = now, missing = false, importFailed = false)
        return when (outcome) {
            is SyncOutcome.NotModified -> checked
            is SyncOutcome.Downloaded ->
                if (changed(outcome, stored)) null else checked.copy(etag = outcome.etag, size = outcome.size)
            is SyncOutcome.Missing -> checked.copy(missing = true)
            is SyncOutcome.Failed -> null
        }
    }

    /**
     * The state of a downloaded, changed file whose import failed: checked again [RETRY_FAILED_IMPORT_MS]
     * later. It keeps the ETag and hash of the content imported before, so the next check downloads the new
     * content again (a 304 would leave it never imported).
     */
    fun importFailed(stored: CatalogFileEntity?, file: SyncOutcome.Downloaded, now: Long): CatalogFileEntity =
        (stored ?: CatalogFileEntity(file.check.name)).copy(checkedAt = now, missing = false, importFailed = true)

    /** The state of a downloaded file whose content was just imported. */
    fun imported(file: SyncOutcome.Downloaded, now: Long): CatalogFileEntity =
        CatalogFileEntity(file.check.name, file.etag, file.sha256, file.size, checkedAt = now, importedAt = now)

    /**
     * The stored labels files of languages the [chain] does not store. A swap drops those labels, so their
     * state goes with them: when the language comes back, its file is downloaded and imported again rather
     * than answered 304 with nothing left to show.
     */
    fun outside(chain: List<String>, names: Collection<String>): List<String> {
        val langs = CatalogImport.languages(chain).toSet()
        return names.filter { name -> langOf(name)?.let { it !in langs } ?: false }
    }
}
