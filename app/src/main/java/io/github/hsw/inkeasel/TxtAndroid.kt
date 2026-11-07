package io.github.hsw.inkeasel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

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
