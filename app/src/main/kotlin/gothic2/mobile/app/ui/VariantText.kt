package gothic2.mobile.app.ui

import android.content.Context
import gothic2.mobile.app.R
import gothic2.mobile.core.gamedata.GameVariant

/**
 * Как вариант игры называется на экранах: коротко («Ночь Ворона», «Классика») и с описанием.
 * Полное название из supported_builds.ini — для настроек и журнала.
 */
object VariantText {
    fun title(context: Context, variant: GameVariant): String = when (variant.id) {
        "notr" -> context.getString(R.string.variant_notr_title)
        "classic" -> context.getString(R.string.variant_classic_title)
        else -> variant.title
    }

    fun description(context: Context, variant: GameVariant): String? = when (variant.id) {
        "notr" -> context.getString(R.string.variant_notr_desc)
        "classic" -> context.getString(R.string.variant_classic_desc)
        else -> null
    }
}
