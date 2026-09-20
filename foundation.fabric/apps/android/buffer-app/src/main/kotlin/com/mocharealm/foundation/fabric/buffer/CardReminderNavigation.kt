package com.mocharealm.foundation.fabric.buffer
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object CardReminderNavigation {
    private const val ACTION = "com.mocharealm.foundation.fabric.buffer.OPEN_REMINDER_CARD"
    fun intent(context: Context, cardId: String): Intent {
        require(validId(cardId))
        return Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("buffer").authority("reminder").appendPath(cardId).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    fun cardId(intent: Intent): String? {
        if (intent.action != ACTION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "buffer" || uri.authority != "reminder" || uri.query != null || uri.fragment != null) return null
        return uri.pathSegments.singleOrNull()?.takeIf(::validId)
    }
    private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9._-]{1,80}"))
}
