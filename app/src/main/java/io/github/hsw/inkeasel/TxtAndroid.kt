package io.github.hsw.inkeasel

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date

/** The text in the current locale; nested [Txt] arguments are resolved first. */
fun Txt.resolve(ctx: Context): String =
    format { id, args -> if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args) }

/** [resolve] for Compose: follows a locale change like `stringResource`. */
@Composable
@ReadOnlyComposable
fun Txt.text(): String {
    if (args.isEmpty()) return stringResource(id)
    return stringResource(id, *args.map { if (it is Txt) it.text() else it }.toTypedArray())
}

/** A wall-clock time [ms] as a short day, month and time in the current locale (e.g. "7 Nov, 18:40"). */
@Composable
fun dateTimeText(ms: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMjjmm"), locale).format(Date(ms))
}
