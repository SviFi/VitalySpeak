package com.svifi.vitalyspeak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.svifi.vitalyspeak.Ui.column
import com.svifi.vitalyspeak.Ui.dp
import com.svifi.vitalyspeak.Ui.primary

/**
 * Prominent disclosure shown before the user is sent to Accessibility settings (required by
 * Google Play for apps using the Accessibility API, and simply fair to the user): what the
 * service does, what it never does, and where audio goes. Nothing happens until "I agree".
 */
class DisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setPadding(dp(24), dp(24), dp(24), dp(32)) }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.disclosure_title)
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        })
        fun block(title: Int, body: Int) {
            root.addView(Ui.section(this, getString(title)).apply { setPadding(0, dp(16), 0, dp(6)) })
            root.addView(Ui.note(this, getString(body)).apply { setPadding(0, 0, 0, 0); textSize = 15f })
        }
        block(R.string.disclosure_uses_title, R.string.disclosure_uses)
        block(R.string.disclosure_never_title, R.string.disclosure_never)
        block(R.string.disclosure_data_title, R.string.disclosure_data)
        root.addView(TextView(this).apply {
            text = getString(R.string.disclosure_policy_link)
            setTextColor(primary())
            setPadding(0, dp(16), 0, dp(24))
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) } catch (_: Exception) {} }
        })
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.disclosure_agree)
            setOnClickListener {
                Prefs.get(this@DisclosureActivity).edit().putBoolean(Prefs.DISCLOSURE_ACCEPTED, true).apply()
                Health.log(this@DisclosureActivity, "accessibility disclosure accepted")
                Health.openAccessibility(this@DisclosureActivity)
                finish()
            }
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.disclosure_decline)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        Ui.page(this, root)
    }
}
