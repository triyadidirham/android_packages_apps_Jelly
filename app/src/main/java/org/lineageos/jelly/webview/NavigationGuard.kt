/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.UrlUtils
import java.util.Locale

/**
 * Decides what a page may do without the user meaning it.
 *
 * WebView reports any tap as a gesture, including one swallowed by an invisible ad layer on top
 * of a video player, so a gesture alone is not treated as consent: the tap has to land on a link.
 */
internal object NavigationGuard {
    data class Settings(
        val blockRedirects: Boolean,
        val blockPopups: Boolean,
        val confirmExternalApps: Boolean,
        val blockUrlSchemes: Boolean,
        val dynamicPopups: Boolean,
    )

    data class Navigation(
        val url: String,
        val currentUrl: String?,
        val hasGesture: Boolean,
        val isRedirect: Boolean,
        val tappedLink: Boolean,
        val recentUserNavigation: Boolean,
    ) {
        val isLinkTap = hasGesture && tappedLink

        /** Without a gesture, a page may still be an interstitial on the way to what was asked. */
        val isDeliberate = if (hasGesture) tappedLink else recentUserNavigation
    }

    enum class AppAction { LAUNCH, CONFIRM, BLOCK, STAY_IN_BROWSER }

    enum class PopupAction { OPEN, HOLD, REJECT }

    /** Whether only an app can handle this URL, as opposed to the web engine itself. */
    fun isAppScheme(url: String) = !UrlUtils.ACCEPTED_URI_SCHEMA.matcher(url).matches()
            && !url.startsWith("blob:", true)
            && !url.startsWith("filesystem:", true)

    /**
     * For schemes only an app can handle, such as intent://, market:// or tel:.
     *
     * A server redirect here may be a login or payment finishing in its app, and it isn't
     * distinguishable from an ad, so it gets asked about rather than blocked. It must not launch
     * without asking though.
     */
    fun appSchemeAction(settings: Settings, navigation: Navigation) = when {
        !settings.blockUrlSchemes || navigation.isDeliberate ->
            if (settings.confirmExternalApps) AppAction.CONFIRM else AppAction.LAUNCH

        navigation.isRedirect && settings.confirmExternalApps -> AppAction.CONFIRM
        else -> AppAction.BLOCK
    }

    /** For web URLs that an installed app can also handle. */
    fun appLinkAction(settings: Settings, navigation: Navigation) = when {
        navigation.isLinkTap ->
            if (settings.confirmExternalApps) AppAction.CONFIRM else AppAction.LAUNCH

        settings.confirmExternalApps || settings.blockRedirects || settings.blockUrlSchemes ->
            AppAction.STAY_IN_BROWSER

        else -> AppAction.LAUNCH
    }

    /**
     * Server redirects are always allowed, since login and payment flows rely on them and an ad
     * has to get past this check on its first hop anyway.
     */
    fun isBlockedRedirect(settings: Settings, navigation: Navigation) =
        settings.blockRedirects
                && !navigation.isRedirect
                && !navigation.isDeliberate
                && hostOf(navigation.currentUrl) != null
                && hostOf(navigation.url) != null
                && !isSameSite(navigation.currentUrl, navigation.url)

    fun popupAction(settings: Settings, hasGesture: Boolean, tappedLink: Boolean) = when {
        !hasGesture && !settings.dynamicPopups -> PopupAction.REJECT
        !settings.blockPopups || (hasGesture && tappedLink) -> PopupAction.OPEN
        else -> PopupAction.HOLD
    }

    fun isSameSite(first: String?, second: String?): Boolean {
        val firstHost = hostOf(first) ?: return false
        val secondHost = hostOf(second) ?: return false
        return siteOf(firstHost) == siteOf(secondHost)
    }

    private fun hostOf(url: String?): String? {
        val authority = url
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringBefore('\\')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?: return null
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith('[')) {
            hostAndPort.substringBefore(']') + ']'
        } else {
            hostAndPort.substringBefore(':')
        }
        return host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /**
     * Approximates the registrable domain without bundling the public suffix list: hosts like
     * shopee.co.id keep three labels so that every *.co.id doesn't count as one site.
     */
    private fun siteOf(host: String): String {
        val labels = host.split('.')
        if (labels.size < 3 || labels.last().all { it.isDigit() }) return host
        val isCountrySecondLevel = labels.last().length == 2
                && labels[labels.size - 2] in COUNTRY_SECOND_LEVEL_LABELS
        return labels.takeLast(if (isCountrySecondLevel) 3 else 2).joinToString(".")
    }

    private val COUNTRY_SECOND_LEVEL_LABELS = setOf(
        "ac", "biz", "co", "com", "edu", "go", "gov", "mil", "my", "ne", "net", "or", "org",
        "sch", "web",
    )
}

internal val SharedPreferencesExt.navigationGuardSettings
    get() = NavigationGuard.Settings(
        blockRedirects = blockRedirectsEnabled,
        blockPopups = blockPopupsEnabled,
        confirmExternalApps = confirmExternalAppsEnabled,
        blockUrlSchemes = blockUrlSchemesEnabled,
        dynamicPopups = dynamicPopupEnabled,
    )
