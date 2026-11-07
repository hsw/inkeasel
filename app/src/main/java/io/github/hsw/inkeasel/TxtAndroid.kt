package io.github.hsw.inkeasel

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date

/** The text in the current locale ([Txt.format]: nested [Txt] arguments are resolved first). */
fun Txt.resolve(ctx: Context): String = resolve(ctx.resources)

private fun Txt.resolve(res: Resources): String =
    format { id, args -> if (args.isEmpty()) res.getString(id) else res.getString(id, *args) }

/** [resolve] for Compose: follows a locale change like `stringResource`, which reads the same two locals. */
@Composable
@ReadOnlyComposable
fun Txt.text(): String {
    LocalConfiguration.current // a locale change recomposes the caller
    return resolve(LocalContext.current.resources)
}

/** A wall-clock time [ms] as a short day, month and time in the current locale (e.g. "7 Nov, 18:40"). */
@Composable
fun dateTimeText(ms: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMjjmm"), locale).format(Date(ms))
}
