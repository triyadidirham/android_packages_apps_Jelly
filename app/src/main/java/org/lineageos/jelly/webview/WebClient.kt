/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.AssetLoader
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.UrlUtils
import java.net.URISyntaxException

internal class WebClient(
    private val context: Context,
    private val urlBarLayout: UrlBarLayout,
) : WebViewClient() {
    private val sharedPreferencesExt by lazy { SharedPreferencesExt(context) }
    private var externalAppDialog: AlertDialog? = null
    private var lastCheckedUrl: String? = null

    private val scripts by lazy {
        val mediaSessionAPI = AssetLoader.loadAsset(
            context.resources,
            "MediaSessionAPI.js",
        )
        buildString {
            appendLine(mediaSessionAPI)
            appendLine(JsMediaSession.SCRIPT)
            appendLine(JsShare.SCRIPT)
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Form submissions skip shouldOverrideUrlLoading, but logins often redirect by script next
        if (url != lastCheckedUrl) {
            (view as? WebViewExt)?.markUserNavigation()
        }
        urlBarLayout.onPageLoadStarted(url)
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(scripts, null)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        urlBarLayout.onPageLoadFinished(view.certificate)
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(JsSyncUrl.SCRIPT, null)
            view.evaluateJavascript(JsManifest.SCRIPT, null)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        super.onReceivedSslError(view, handler, error)
        urlBarLayout.onSslError(error)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) {
            return false
        }

        val webViewExt = view as WebViewExt
        val url = request.url.toString()
        val settings = sharedPreferencesExt.navigationGuardSettings
        val navigation = NavigationGuard.Navigation(
            url = url,
            currentUrl = view.url,
            hasGesture = request.hasGesture(),
            isRedirect = request.isRedirect,
            // A redirect's target is expected to differ from what the link pointed to
            tappedLink = webViewExt.lastTouchWasOnLink(url.takeUnless { request.isRedirect }),
            recentUserNavigation = webViewExt.hasRecentUserNavigation,
        )
        lastCheckedUrl = url
        if (navigation.isLinkTap || request.isRedirect) {
            webViewExt.markUserNavigation()
        }

        val needsLookup = (request.hasGesture()
                || !TextUtils.equals(url, webViewExt.lastLoadedUrl))
        val intent = if (!webViewExt.isIncognito && needsLookup) {
            resolveExternalIntent(view.context, url)
        } else {
            null
        }

        if (NavigationGuard.isAppScheme(url)) {
            // The page can't render these, so keep it from being replaced by an error page
            intent ?: return settings.blockUrlSchemes
            when (NavigationGuard.appSchemeAction(settings, navigation)) {
                NavigationGuard.AppAction.BLOCK -> showBlockedAppSnackbar(view, intent)
                NavigationGuard.AppAction.CONFIRM -> confirmExternalApp(view, intent)
                else -> return startExternalActivity(view, intent) || settings.blockUrlSchemes
            }
            return true
        }

        if (intent != null) {
            when (NavigationGuard.appLinkAction(settings, navigation)) {
                NavigationGuard.AppAction.CONFIRM -> {
                    confirmExternalApp(view, intent) { webViewExt.loadUrl(url) }
                    return true
                }

                NavigationGuard.AppAction.LAUNCH -> {
                    if (startExternalActivity(view, intent) && !request.isRedirect) {
                        return true
                    }
                }

                else -> Unit
            }
        }

        if (NavigationGuard.isBlockedRedirect(settings, navigation)) {
            showBlockedSnackbar(view, R.string.navigation_guard_redirect_blocked) {
                webViewExt.loadUrl(url)
            }
            return true
        }

        if (webViewExt.requestHeaders.isNotEmpty()) {
            webViewExt.followUrl(url)
            return true
        }
        return false
    }

    private fun confirmExternalApp(view: WebView, intent: Intent, onStay: () -> Unit = {}) {
        val activity = hostActivity(view)
        if (activity == null) {
            onStay()
            return
        }
        // A page firing several navigations in a row shouldn't stack dialogs
        if (externalAppDialog?.isShowing == true) {
            return
        }
        val site = view.url?.let { Uri.parse(it).host } ?: context.getString(R.string.app_name)
        externalAppDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.external_app_dialog_title)
            .setMessage(context.getString(R.string.external_app_dialog_message, site))
            .setPositiveButton(R.string.external_app_dialog_open) { _, _ ->
                startExternalActivity(view, intent)
            }
            .setNegativeButton(R.string.external_app_dialog_stay) { _, _ -> onStay() }
            .setOnCancelListener { onStay() }
            .show()
    }

    private fun showBlockedAppSnackbar(view: WebView, intent: Intent) =
        showBlockedSnackbar(view, R.string.navigation_guard_app_blocked) {
            startExternalActivity(view, intent)
        }

    private fun showBlockedSnackbar(view: WebView, @StringRes message: Int, onAllow: () -> Unit) {
        // Background shortcuts keep running while detached from any window
        if (!view.isAttachedToWindow) return
        Snackbar.make(view, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.navigation_guard_allow) { onAllow() }
            .show()
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler, host: String, realm: String
    ) {
        val context = view.context
        val builder = AlertDialog.Builder(context)
        val layoutInflater = LayoutInflater.from(context)
        val dialogView = layoutInflater.inflate(R.layout.auth_dialog, LinearLayout(context))
        val usernameEditText = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val authDetailTextView = dialogView.findViewById<TextView>(R.id.authDetailTextView)
        val text = context.getString(R.string.auth_dialog_detail, view.url)
        authDetailTextView.text = text
        builder.setView(dialogView)
            .setTitle(R.string.auth_dialog_title)
            .setPositiveButton(R.string.auth_dialog_login)
            { _: DialogInterface?, _: Int ->
                handler.proceed(
                    usernameEditText.text.toString(), passwordEditText.text.toString()
                )
            }
            .setNegativeButton(android.R.string.cancel)
            { _: DialogInterface?, _: Int ->
                handler.cancel()
            }
            .setOnDismissListener { handler.cancel() }
            .show()
    }

    private fun resolveExternalIntent(context: Context, url: String): Intent? {
        var intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (ex: URISyntaxException) {
            return null
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val m = UrlUtils.ACCEPTED_URI_SCHEMA.matcher(url)
        if (m.matches()) {
            // If there only are browsers for this URL, handle it ourselves
            return makeHandlerChooserIntent(context, intent, url)
        } else {
            val packageName = intent.getPackage()
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(intent, ResolveInfoFlags.of(0))
            } else {
                context.packageManager.resolveActivity(intent, 0)
            }
            if (packageName != null && resolveInfo == null) {
                // Explicit intent, but app is not installed - try to redirect to Play Store
                val storeUri = Uri.parse("market://search?q=pname:$packageName")
                intent = Intent(Intent.ACTION_VIEW, storeUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return intent
    }

    /**
     * The activity currently showing this WebView. Background shortcut WebViews are created by a
     * service and outlive activities, so neither their own context nor [context] can be used.
     */
    private fun hostActivity(view: WebView): Activity? {
        var current = (view.parent as? View)?.context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current.takeUnless { it.isFinishing || it.isDestroyed }
            }
            current = current.baseContext
        }
        return null
    }

    private fun startExternalActivity(view: WebView, intent: Intent): Boolean {
        val activity = hostActivity(view)
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val context = activity ?: view.context
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(
                view, context.getString(R.string.error_no_activity_found),
                Snackbar.LENGTH_LONG
            ).show()
        }
        return false
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun makeHandlerChooserIntent(context: Context, intent: Intent, url: String): Intent? {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, ResolveInfoFlags.of(flags.toLong()))
        } else {
            pm.queryIntentActivities(intent, flags)
        }
        if (activities.isEmpty()) {
            return null
        }
        val chooserIntents = ArrayList<Intent>()
        val ourPackageName = context.packageName
        activities.sortWith(ResolveInfo.DisplayNameComparator(pm))
        for (resolveInfo in activities) {
            val filter = resolveInfo.filter ?: continue
            val info = resolveInfo.activityInfo
            if (!info.enabled || !info.exported) {
                continue
            }
            if (filter.countDataAuthorities() == 0
                && !TextUtils.equals(info.packageName, ourPackageName)
            ) {
                continue
            }
            val targetIntent = Intent(intent)
            targetIntent.setPackage(info.packageName)
            chooserIntents.add(targetIntent)
        }
        if (chooserIntents.isEmpty()) {
            return null
        }
        val lastIntent = chooserIntents.removeAt(chooserIntents.size - 1)
        if (chooserIntents.isEmpty()) {
            // there was only one, no need to show the chooser
            return if (ourPackageName.equals(lastIntent.getPackage())) null else lastIntent
        }
        val changeIntent = Intent(IntentUtils.EVENT_URL_RESOLVED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            .putExtra(IntentUtils.EXTRA_URL, url)
        val pi = PendingIntent.getBroadcast(
            context, 0, changeIntent,
            PendingIntent.FLAG_IMMUTABLE
                    or PendingIntent.FLAG_UPDATE_CURRENT
                    or PendingIntent.FLAG_ONE_SHOT
        )
        val chooserIntent = Intent.createChooser(lastIntent, null)
        chooserIntent.putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            chooserIntents.toTypedArray()
        )
        chooserIntent.putExtra(Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, pi.intentSender)
        return chooserIntent
    }
}
