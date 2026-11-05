/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.jelly.webview.NavigationGuard.AppAction
import org.lineageos.jelly.webview.NavigationGuard.Navigation
import org.lineageos.jelly.webview.NavigationGuard.PopupAction
import org.lineageos.jelly.webview.NavigationGuard.Settings

class NavigationGuardTest {
    private val hardened = Settings(
        blockRedirects = true,
        blockPopups = true,
        confirmExternalApps = true,
        blockUrlSchemes = true,
        dynamicPopups = false,
    )
    private val legacy = Settings(
        blockRedirects = false,
        blockPopups = false,
        confirmExternalApps = false,
        blockUrlSchemes = false,
        dynamicPopups = false,
    )

    private fun navigation(
        url: String,
        currentUrl: String? = "https://stream.example/watch",
        hasGesture: Boolean = false,
        isRedirect: Boolean = false,
        tappedLink: Boolean = false,
        recentUserNavigation: Boolean = false,
    ) = Navigation(url, currentUrl, hasGesture, isRedirect, tappedLink, recentUserNavigation)

    // App schemes (intent://, shopee://, market://)

    @Test
    fun `tap on video overlay cannot launch an app scheme`() {
        val nav = navigation("intent://shop#Intent;package=com.shopee.id;end", hasGesture = true)
        assertEquals(AppAction.BLOCK, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `script without a tap cannot launch an app scheme`() {
        val nav = navigation("snssdk1233://video/1")
        assertEquals(AppAction.BLOCK, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `tap on a real link asks before launching an app scheme`() {
        val nav = navigation("market://details?id=a", hasGesture = true, tappedLink = true)
        assertEquals(AppAction.CONFIRM, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `interstitial shortly after a user navigation asks before launching an app scheme`() {
        val nav = navigation("shopeeid://home", recentUserNavigation = true)
        assertEquals(AppAction.CONFIRM, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `recent user navigation does not excuse a tap on a non link`() {
        val nav = navigation("shopeeid://home", hasGesture = true, recentUserNavigation = true)
        assertEquals(AppAction.BLOCK, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `scheme blocking off still asks for confirmation`() {
        val nav = navigation("shopeeid://home")
        val settings = hardened.copy(blockUrlSchemes = false)
        assertEquals(AppAction.CONFIRM, NavigationGuard.appSchemeAction(settings, nav))
    }

    @Test
    fun `confirmation off launches a real link tap directly`() {
        val nav = navigation("tel:123", hasGesture = true, tappedLink = true)
        val settings = hardened.copy(confirmExternalApps = false)
        assertEquals(AppAction.LAUNCH, NavigationGuard.appSchemeAction(settings, nav))
    }

    @Test
    fun `all switches off keeps the stock app scheme behaviour`() {
        assertEquals(
            AppAction.LAUNCH,
            NavigationGuard.appSchemeAction(legacy, navigation("shopeeid://home")),
        )
    }

    @Test
    fun `server redirect to an app scheme asks so login and payment can finish`() {
        val nav = navigation("gojek://pay", isRedirect = true)
        assertEquals(AppAction.CONFIRM, NavigationGuard.appSchemeAction(hardened, nav))
    }

    @Test
    fun `server redirect to an app scheme never launches silently`() {
        val nav = navigation("gojek://pay", isRedirect = true)
        val settings = hardened.copy(confirmExternalApps = false)
        assertEquals(AppAction.BLOCK, NavigationGuard.appSchemeAction(settings, nav))
    }

    @Test
    fun `urls the web engine loads itself are not app schemes`() {
        assertFalse(NavigationGuard.isAppScheme("blob:https://stream.example/1234"))
        assertFalse(NavigationGuard.isAppScheme("filesystem:https://stream.example/temporary/a"))
        assertFalse(NavigationGuard.isAppScheme("https://stream.example/"))
        assertFalse(NavigationGuard.isAppScheme("about:blank"))
    }

    @Test
    fun `urls only an app can handle are app schemes`() {
        assertTrue(NavigationGuard.isAppScheme("intent://x#Intent;scheme=a;end"))
        assertTrue(NavigationGuard.isAppScheme("market://details?id=a"))
        assertTrue(NavigationGuard.isAppScheme("tel:123"))
        assertTrue(NavigationGuard.isAppScheme("blobby://x"))
    }

    // Web links that an installed app can handle

    @Test
    fun `scheme blocking alone keeps an overlay tap away from apps`() {
        val nav = navigation("https://shopee.co.id/", hasGesture = true)
        val settings = legacy.copy(blockUrlSchemes = true)
        assertEquals(AppAction.STAY_IN_BROWSER, NavigationGuard.appLinkAction(settings, nav))
    }

    @Test
    fun `tap on a real web link with an installed app asks first`() {
        val nav = navigation("https://shopee.co.id/", hasGesture = true, tappedLink = true)
        assertEquals(AppAction.CONFIRM, NavigationGuard.appLinkAction(hardened, nav))
    }

    @Test
    fun `web link with an app stays in the browser without a real link tap`() {
        val nav = navigation("https://shopee.co.id/", hasGesture = true)
        assertEquals(AppAction.STAY_IN_BROWSER, NavigationGuard.appLinkAction(hardened, nav))
    }

    @Test
    fun `follow up load of an app link does not ask again`() {
        val nav = navigation("https://shopee.co.id/home", recentUserNavigation = true)
        assertEquals(AppAction.STAY_IN_BROWSER, NavigationGuard.appLinkAction(hardened, nav))
    }

    @Test
    fun `confirmation off still keeps an overlay tap away from apps`() {
        val nav = navigation("https://shopee.co.id/", hasGesture = true)
        val settings = hardened.copy(confirmExternalApps = false)
        assertEquals(AppAction.STAY_IN_BROWSER, NavigationGuard.appLinkAction(settings, nav))
    }

    @Test
    fun `all switches off keeps the stock app link behaviour`() {
        assertEquals(
            AppAction.LAUNCH,
            NavigationGuard.appLinkAction(legacy, navigation("https://shopee.co.id/")),
        )
    }

    // Web navigation inside the browser

    @Test
    fun `script redirect to another site is blocked`() {
        assertTrue(NavigationGuard.isBlockedRedirect(hardened, navigation("https://ads.test/")))
    }

    @Test
    fun `tap on a non link that navigates to another site is blocked`() {
        val nav = navigation("https://shopee.co.id/", hasGesture = true)
        assertTrue(NavigationGuard.isBlockedRedirect(hardened, nav))
    }

    @Test
    fun `tap on a real link to another site is allowed`() {
        val nav = navigation("https://other.test/", hasGesture = true, tappedLink = true)
        assertFalse(NavigationGuard.isBlockedRedirect(hardened, nav))
    }

    @Test
    fun `server redirect is allowed so login and payment keep working`() {
        val nav = navigation("https://accounts.test/", isRedirect = true)
        assertFalse(NavigationGuard.isBlockedRedirect(hardened, nav))
    }

    @Test
    fun `script redirect within the same site is allowed`() {
        val nav = navigation("https://www.stream.example/next")
        assertFalse(NavigationGuard.isBlockedRedirect(hardened, nav))
    }

    @Test
    fun `script redirect shortly after a user navigation is allowed`() {
        val nav = navigation("https://target.test/", recentUserNavigation = true)
        assertFalse(NavigationGuard.isBlockedRedirect(hardened, nav))
    }

    @Test
    fun `first navigation of an empty tab is allowed`() {
        assertFalse(
            NavigationGuard.isBlockedRedirect(
                hardened, navigation("https://target.test/", currentUrl = null)
            )
        )
        assertFalse(
            NavigationGuard.isBlockedRedirect(
                hardened, navigation("https://target.test/", currentUrl = "about:blank")
            )
        )
    }

    @Test
    fun `redirect blocking switch off allows script redirects`() {
        val settings = hardened.copy(blockRedirects = false)
        assertFalse(NavigationGuard.isBlockedRedirect(settings, navigation("https://ads.test/")))
    }

    // Pop-ups and new tabs

    @Test
    fun `popup without a tap is rejected`() {
        assertEquals(
            PopupAction.REJECT,
            NavigationGuard.popupAction(hardened, hasGesture = false, tappedLink = false),
        )
    }

    @Test
    fun `popup from a tap on a video overlay is held`() {
        assertEquals(
            PopupAction.HOLD,
            NavigationGuard.popupAction(hardened, hasGesture = true, tappedLink = false),
        )
    }

    @Test
    fun `new tab from a real link is opened`() {
        assertEquals(
            PopupAction.OPEN,
            NavigationGuard.popupAction(hardened, hasGesture = true, tappedLink = true),
        )
    }

    @Test
    fun `popup blocking wins over dynamic popups`() {
        val settings = hardened.copy(dynamicPopups = true)
        assertEquals(
            PopupAction.HOLD,
            NavigationGuard.popupAction(settings, hasGesture = false, tappedLink = false),
        )
    }

    @Test
    fun `all switches off keeps the stock popup behaviour`() {
        assertEquals(
            PopupAction.REJECT,
            NavigationGuard.popupAction(legacy, hasGesture = false, tappedLink = false),
        )
        assertEquals(
            PopupAction.OPEN,
            NavigationGuard.popupAction(legacy, hasGesture = true, tappedLink = false),
        )
        assertEquals(
            PopupAction.OPEN,
            NavigationGuard.popupAction(
                legacy.copy(dynamicPopups = true), hasGesture = false, tappedLink = false
            ),
        )
    }

    // Same-site comparison

    @Test
    fun `subdomains are the same site`() {
        assertTrue(NavigationGuard.isSameSite("https://a.example.com/x", "http://b.example.com:8080"))
    }

    @Test
    fun `country code second level domains do not merge different sites`() {
        assertTrue(NavigationGuard.isSameSite("https://www.shopee.co.id/", "https://shopee.co.id/"))
        assertFalse(NavigationGuard.isSameSite("https://shopee.co.id/", "https://tokopedia.co.id/"))
    }

    @Test
    fun `short two label hosts are compared whole`() {
        assertFalse(NavigationGuard.isSameSite("https://t.co/a", "https://x.co/a"))
    }

    @Test
    fun `lookalike hosts are different sites`() {
        assertFalse(NavigationGuard.isSameSite("https://stream.example/", "https://evilstream.example/"))
        assertFalse(NavigationGuard.isSameSite("https://stream.example/", "https://stream.example@evil.test/"))
        assertFalse(NavigationGuard.isSameSite("https://stream.example/", "https://stream.example.evil.test/"))
    }

    @Test
    fun `short names under a country domain keep their subdomains together`() {
        assertTrue(NavigationGuard.isSameSite("https://www.abc.id/", "https://m.abc.id/"))
    }

    @Test
    fun `ipv6 addresses are compared whole`() {
        assertTrue(NavigationGuard.isSameSite("http://[::1]/a", "http://[::1]:8080/b"))
        assertFalse(NavigationGuard.isSameSite("http://[fe80::1]/", "http://[fe80::2]/"))
    }

    @Test
    fun `ip addresses are compared whole`() {
        assertTrue(NavigationGuard.isSameSite("http://10.0.0.1/a", "http://10.0.0.1:80/b"))
        assertFalse(NavigationGuard.isSameSite("http://10.0.0.1/", "http://11.0.0.1/"))
    }
}
