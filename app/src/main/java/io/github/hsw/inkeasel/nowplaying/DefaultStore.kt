package io.github.hsw.inkeasel.nowplaying

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * What a manual upload sent to the frame, as given to `UploadService`: [type] is the extra that was used
 * (`photo` = a name in the pictures folder, `path` = an absolute file, `url` = a remote image), [value]
 * is its value. The frame returns to the last manual source when playback ends.
 */
data class Source(val type: Type, val value: String, val title: String = "") {
    enum class Type { PHOTO, PATH, URL }

    /** The `onFrame` key of this source once it is on the frame. */
    fun manualKey(): String = FrameController.MANUAL_PREFIX + type.name.lowercase() + ":" + value

    /**
     * The picture this source points at now: a `photo` is looked up through [resolvePhoto] (the pictures
     * folder), a `path` must still be a file. `null` if it no longer resolves.
     */
    fun toArt(resolvePhoto: (String) -> File?, isFile: (String) -> Boolean = { File(it).isFile }): Art? =
        when (type) {
            Type.URL -> Art.Url(value)
            Type.PATH -> value.takeIf(isFile)?.let { Art.File(it) }
            Type.PHOTO -> resolvePhoto(value)?.let { Art.File(it.path) }
        }

    /** The pref entries of this source (see [decode]). */
    fun encode(): Map<String, String> = mapOf(TYPE to type.name, VALUE to value, TITLE to title)

    companion object {
        const val TYPE = "type"
        const val VALUE = "value"
        const val TITLE = "title"

        /** Read a source back from pref entries written by [encode]; `null` if they are missing or corrupt. */
        fun decode(prefs: Map<String, *>): Source? {
            val type = (prefs[TYPE] as? String)?.let { t -> Type.entries.firstOrNull { it.name == t } } ?: return null
            val value = (prefs[VALUE] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return Source(type, value, prefs[TITLE] as? String ?: "")
        }

        /**
         * The source named by `UploadService` extras, with the picture it resolved to; the first usable one
         * wins, in the order `path` (must be a file), `photo` (must resolve in the pictures folder), `url`.
         * Blank extras count as absent. The title is [title] if given, else the file name without extension
         * (`path`, `photo`) or empty (`url`; the notification then names it, see `UploadOutcome.untitled`). It
         * is stored with the default, so it is never a translated text.
         */
        fun fromExtras(
            path: String?,
            photo: String?,
            url: String?,
            title: String?,
            resolvePhoto: (String) -> File?,
            isFile: (String) -> Boolean = { File(it).isFile },
        ): Picked? {
            val t = title?.takeIf { it.isNotBlank() }
            path?.takeIf { it.isNotBlank() && isFile(it) }?.let { p ->
                return Picked(Source(Type.PATH, p, t ?: File(p).nameWithoutExtension), Art.File(p))
            }
            photo?.takeIf { it.isNotBlank() }?.let { name ->
                resolvePhoto(name)?.let { f ->
                    return Picked(Source(Type.PHOTO, name, t ?: f.nameWithoutExtension), Art.File(f.path))
                }
            }
            url?.takeIf { it.isNotBlank() }?.let { u -> return Picked(Source(Type.URL, u, t.orEmpty()), Art.Url(u)) }
            return null
        }
    }
}

/** A [Source] picked from `UploadService` extras and the picture it points at now. */
data class Picked(val source: Source, val art: Art)

/** The default picture of the frame: the last manual upload. */
interface DefaultStore {
    /** The default picture, or `null` if there is none (or it no longer resolves). */
    fun load(): Art?

    /** Remember [source] as the new default. */
    fun save(source: Source)
}

/** The key confirmed on the frame (`FrameController.onFrame`), kept across process restarts. */
interface FrameState {
    fun load(): String?
    fun save(key: String)
}

/** [FrameState] in shared preferences `frame`. */
class PrefsFrameState(context: Context) : FrameState {
    private val prefs: SharedPreferences = context.getSharedPreferences("frame", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(key: String) {
        prefs.edit().putString(KEY, key).apply()
    }

    private companion object {
        const val KEY = "onFrame"
    }
}

/**
 * [DefaultStore] in shared preferences `default` (the entries of [Source.encode]). A `photo` source is looked
 * up again on every [load] through [resolvePhoto] (the pictures folder lookup of `UploadService`), so a
 * deleted photo yields `null`.
 */
class PrefsDefaultStore(context: Context, private val resolvePhoto: (String) -> File?) : DefaultStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE)

    override fun load(): Art? = Source.decode(prefs.all)?.toArt(resolvePhoto)

    override fun save(source: Source) {
        val e = prefs.edit()
        source.encode().forEach { (k, v) -> e.putString(k, v) }
        e.apply()
    }
}
