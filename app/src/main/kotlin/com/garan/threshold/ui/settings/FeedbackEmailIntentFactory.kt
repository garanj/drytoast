package com.garan.threshold.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.garan.threshold.R

object FeedbackEmailIntentFactory {

    fun createFeedbackEmailIntent(
        context: Context,
        appVersion: String = resolveAppVersion(context),
        androidVersion: String = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    ): Intent {
        val recipient = context.getString(R.string.feedback_email_recipient)
            .reversed()
            .replace('=', '@')
        val subject = context.getString(R.string.feedback_email_subject)
        val body = context.getString(
            R.string.feedback_email_body_template,
            appVersion,
            androidVersion,
            deviceModel,
        )
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    internal fun resolveAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
    }.getOrDefault("0.1.0")
}
