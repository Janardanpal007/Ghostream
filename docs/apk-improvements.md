# Ghostream Android app: improvement list

Read-only review of `android/` (source of the 1.3.8 APK), 12 Nov 2026. No code was changed and nothing was built or run.
Paths are relative to `android/`. `A/` means `app/src/main/java/com/fastdownloader/app/`.
Sizes: S = under half a day, M = 1 to 3 days, L = a week or more.
Every item was read in the code unless it is marked **unverified**. None was reproduced on a phone; the "Check" line is the test to run.

## Top 10

1. A crashed or killed page renderer takes the whole app down: no `onRenderProcessGone` (`A/browser/TabWebView.kt:335`, M).
2. "Clear cookies" puts Google/YouTube sign-in cookies back without `HttpOnly`, so page scripts can read them (`A/browser/SignIns.kt:34`, S).
3. The proxy setting covers downloads only; the browser's own traffic goes direct and shows the real IP (`A/engine/Net.kt:64`, no `ProxyController` anywhere, M).
4. The page bridge `__fdBridge` is open to every frame, including ad iframes, and trusts the address the caller passes (`A/browser/TabWebView.kt:311`, M).
5. A damaged `downloads.json`, `settings.json`, bookmarks or history file is silently replaced by an empty one (`A/engine/Model.kt:169`, `A/browser/BrowserStore.kt:175`, S).
6. Password vault is far behind desktop: no master password, no 2FA codes, no notes; on a phone with no screen lock it opens with no check (`A/browser/Passwords.kt:22`, `A/ui/Passwords.kt:67`, L).
7. Web pages are never paused when the app goes to the background: up to 6 live tabs keep running scripts and timers (`A/ui/MainActivity.kt:202`, `A/browser/TabManager.kt:112`, M).
8. Sites can't ask for camera, microphone or location, and sites with a browser sign-in box (HTTP auth) can't be opened (`A/browser/TabWebView.kt:452`, M).
9. yt-dlp cookie files with the user's sign-in cookies stay in the cache folder for good (`A/engine/VideoTask.kt:169`, S).
10. Start-up work on the main thread: disk reads in `Application.onCreate` and key-store work plus a possible 300 ms sleep while the browser is first drawn (`A/App.kt:31`, `A/ui/BrowserScreen.kt:190`, `A/browser/Passwords.kt:66`, S).

## Bugs and crash risks

### B1. Renderer crash kills the app (M)
- **What and why:** The `WebViewClient` has no `onRenderProcessGone`. On Android 8+ an unhandled renderer crash, or the system killing the renderer for memory, ends the whole app, with every tab and any download not yet saved.
- **Where:** `A/browser/TabWebView.kt:335` (the client object, lines 335-451).
- **Fix:** Override it, return `true`, drop that tab's WebView (`destroyWeb()`), and show a "This page stopped. Reload" page. The probe WebView at line 491 needs the same.
- **Check:** Open `chrome://crash` in a tab (or `adb shell kill` the app's `sandboxed_process` while a heavy page is open). Today: app closes. After: only that tab shows an error.

### B2. A damaged data file is silently thrown away (S)
- **What and why:** `read()` returns null on any parse error and the caller falls back to an empty list or default settings; the next save overwrites the file. One bad write or one incompatible field loses all downloads, settings, bookmarks or history with no message.
- **Where:** `A/engine/Model.kt:169-173` (used at 160 and 166), `A/browser/BrowserStore.kt:175-179`. `Engine.save()` also swallows write errors (`A/engine/Engine.kt:435-437`), so a full disk is silent.
- **Fix:** On a parse error, rename the file to `name.bad.<time>` before continuing (the password vault already does this at `A/browser/Passwords.kt:69`) and tell the user once.
- **Check:** `adb shell run-as com.ghostream.app.debug` and truncate `files/browser/bookmarks.json` to half; start the app, add one bookmark. Today the old bookmarks are gone for good.

### B3. Password save can crash, and concurrent saves can interleave (S)
- **What and why:** `change()` encrypts and writes with no try/catch, so a key-store error (the file's own comment at line 65 says the key store can be briefly unavailable) throws into the UI callback that called it. It also writes the same `.tmp` file with no lock, and does this on the caller's thread (the main thread for UI actions).
- **Where:** `A/browser/Passwords.kt:143-149`.
- **Fix:** `@Synchronized`, catch and report, write on a background thread.
- **Check:** Unit test with a failing cipher; import a 500-row CSV and watch for dropped frames. **Unverified** as a real-world crash.

### B4. Service stop race can leave a download without the foreground service (S)
- **What and why:** The service loop calls `stopSelf()` after about 3 s idle, but `running` stays true until `onDestroy`. A download that starts in that gap sees `running == true`, skips the start, and then runs with no foreground service or wake lock. `ensureRunning` also swallows the "not allowed from background" error on Android 12+.
- **Where:** `A/service/DownloadService.kt:63-75` and `136-139`.
- **Fix:** Set `running = false` before `stopSelf()`, and re-check the queue in `onDestroy`.
- **Check:** Script: finish one download, add another 3 s later, repeat 50 times; `adb shell dumpsys activity services com.ghostream.app` should always show the service while something downloads. **Unverified** on a device (narrow window).

### B5. Long downloads are paused after 6 hours on Android 15+ (M)
- **What and why:** The service type is `dataSync`, which Android 15+ limits to 6 hours per day; `onTimeout` then pauses everything. The wake lock also expires at 6 hours. Large torrents stop overnight with no explanation.
- **Where:** `A/service/DownloadService.kt:51` and `85-88`, `app/src/main/AndroidManifest.xml:91`.
- **Fix:** Tell the user why ("Android paused downloads after 6 hours, tap to continue") in a notification; consider a user-initiated data transfer job for big files.
- **Check:** `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000`, start a download, wait a minute.

### B6. Opening a .torrent reads the whole file on the main thread, with no size limit (S)
- **Where:** `A/ui/MainActivity.kt:170` (`viewData`, called from `onCreate`/`onNewIntent`).
- **Fix:** Read on a background thread, refuse files over a few MB.
- **Check:** "Open with Ghostream" on a 300 MB file renamed to `.torrent`: freeze or out-of-memory today.

### B7. Unsaved changes are lost if Android ends the app (S)
- **What and why:** History, tabs and counters are written 0.4 to 3 s after a change; `onStop` only flushes cookies.
- **Where:** `A/browser/BrowserStore.kt:183-198`, `A/ui/MainActivity.kt:202-205`.
- **Fix:** Flush pending writes in `onStop`.
- **Check:** Open a new tab, press Home at once, `adb shell am kill com.ghostream.app`, reopen: the tab is missing.

## Security and privacy

### S1. "Clear cookies" weakens Google and YouTube sign-in cookies (S)
- **What and why:** The kept cookies are read with `getCookie` (name=value only) and written back with `Secure` but without `HttpOnly`. Google's session cookies are `HttpOnly`; after one "Clear cookies" they become readable by any script on google.com/youtube.com, and all get a fixed 400-day life and `Path=/`.
- **Where:** `A/browser/SignIns.kt:30-35`.
- **Fix:** Add `; HttpOnly` to restored cookies (safe default for sign-in cookies), or don't clear those two sites' cookies at all instead of clear-and-restore.
- **Check:** Sign in to Google, tap Clear cookies, then inspect in a debug build (`chrome://inspect`, Application > Cookies): `SID`, `HSID`, `__Secure-1PSID` have lost the HttpOnly mark.

### S2. Proxy setting does not cover the browser (M)
- **What and why:** `Net.kt` says "no direct fallback ... rather than leaking the real address", but only OkHttp and yt-dlp use the proxy. No `ProxyController` call exists, so pages in the browser load directly. A user who set a proxy for privacy is exposed.
- **Where:** `A/engine/Net.kt:30-73` (grep for `ProxyController` finds nothing in the app).
- **Fix:** `ProxyController.setProxyOverride` (androidx.webkit, feature `PROXY_OVERRIDE`) when the proxy is on; if that is not wanted, say "downloads only" in Settings.
- **Check:** Set a proxy, open an "what is my IP" page in the browser and compare with a download through the proxy.

### S3. Page bridge is reachable from every frame and trusts its arguments (M)
- **What and why:** `addJavascriptInterface` puts `__fdBridge` in every frame of every page. The methods check the `url` the caller passes, not who is really calling. A third-party ad frame can: read settings (`hideShorts()`, `youtubeQuality()`, a fingerprint and a sure "this is Ghostream" sign), raise a fake "save password" prompt for the top site (`loginSubmitted`), stage a fake password import (`passwordFile`), and plant download targets (`media`, `found`).
- **Where:** `A/browser/TabWebView.kt:311` (injection), `114-236` (methods; note the comment at 113).
- **Fix:** Move the calls that change state to `WebViewCompat.addWebMessageListener`, which reports the real origin and `isMainFrame`; keep only harmless read calls on the old bridge.
- **Check:** A test page with a cross-origin iframe that runs `__fdBridge.loginSubmitted(document.referrer, 'x', 'y')`, then navigates: today a save prompt for the top site appears.

### S4. yt-dlp cookie files are never deleted (S)
- **What and why:** Each video check or download writes the site's cookies in clear text to `cache/cookies-<hash>.txt` and leaves them there. "Clear cookies" does not remove them. The cookie domain is taken as the last two labels of the host, which is wrong for names like `example.co.uk` (it becomes `.co.uk`).
- **Where:** `A/engine/VideoTask.kt:165-177`; callers at `VideoTask.kt:219`, `A/ui/AddPanel.kt:90`, `A/engine/VideoPrefetch.kt:41`.
- **Fix:** Delete the file in a `finally` after each yt-dlp call, use `AdBlock.baseDomain`, and wipe `cookies-*.txt` on "Clear cookies". The same applies to the `Cookie` header kept in `downloads.json` for finished items.
- **Check:** Download a signed-in YouTube video, then `run-as ... ls cache/`.

### S5. Vault opens with no check on phones without a screen lock (S, or part of F1)
- **Where:** `A/ui/Passwords.kt:67-72`.
- **Fix:** Optional master password (see F1) so there is always a gate.
- **Check:** Remove the screen lock, open Passwords: everything is shown after a toast.

### S6. Passwords and proxy password leave the phone in clear form during backup (S)
- **What and why:** For a backup the vault is written unencrypted to `files/passwords.restore`, and `settings.json` (which holds `proxyPass` and `turboKey` in clear text) is also included, for cloud backup and for device transfer. Android encrypts the cloud backup, and the file is deleted after; this is a design choice, not a hole, but worth an on/off switch ("Include passwords in backup") and moving `proxyPass` into the encrypted vault.
- **Where:** `A/backup/AppBackup.kt:21-30`, `A/browser/Passwords.kt:93-96`, `app/src/main/res/xml/data_extraction_rules.xml:5-19`, `A/engine/Model.kt` (Settings: `proxyPass`).
- **Check:** `adb shell bmgr backupnow com.ghostream.app` and watch `files/` during the run.

### S7. File provider can hand out any file on shared storage (S)
- **Where:** `app/src/main/res/xml/file_paths.xml:3` (`external-path path="."`).
- **Fix:** Limit to `Download/` (or `Download/Ghostream/`). The provider is not exported, so today's risk is low.
- **Check:** Open and share a finished download still works after narrowing.

### S8. No screenshot protection on passwords and private tabs (S)
- **Where:** no `FLAG_SECURE` anywhere in the app; password UI is `A/ui/Passwords.kt`.
- **Fix:** Set `FLAG_SECURE` while the password sheet is open and (as an option) for private tabs.
- **Check:** The recent-apps view shows passwords today.

### S9. Update file is installed even when no checksum was published (S)
- **Where:** `A/engine/Updater.kt:148` (`rel.sha256 != null && ...`).
- **Fix:** Refuse when the checksum is missing. Android still checks the signature, so risk is low.
- **Check:** Unit test: release without a checksum line must end in an error.

### S10. WebView content access left on (S, unverified)
- **Where:** `A/browser/TabWebView.kt:297-309` (settings block; `allowContentAccess` is not set, default is true).
- **Fix:** `allowContentAccess = false`. File access is already off by default at this target SDK.
- **Check:** Type a `content://` address in the address bar. **Unverified** whether the address bar lets it through.

Checked and fine: activity is the only exported component; service and provider are not exported; all `PendingIntent`s are immutable; `intent:` links have component and selector removed and need a tap (`TabWebView.kt:367-374`); TLS errors are cancelled, never accepted (`TabWebView.kt:447`); no custom trust manager; remote debugging only in debug builds; vault uses AES-256-GCM with an Android Keystore key. `usesCleartextTraffic="true"` is needed for a browser.

## Performance and battery

### P1. Background tabs are never paused (M)
- **What and why:** Nothing calls `WebView.onPause()`, `pauseTimers()` or `onResume()`. With the app in the background, up to 6 live WebViews (`MAX_LIVE`) keep running scripts, timers and network. Hidden tabs inside the app also keep running.
- **Where:** `A/ui/MainActivity.kt:202` (only flushes cookies), `A/browser/TabManager.kt:51-57` and `112`.
- **Fix:** `onPause()` a tab when it is no longer the current one and on activity `onStop`, unless that tab is playing (`tab.playing`) or picture-in-picture is on.
- **Check:** Open 5 heavy pages, press Home, `adb shell top` for 2 minutes; compare CPU before and after.

### P2. Main-thread work at start (S)
- **What and why:** `Engine.init` reads and parses `settings.json` and `downloads.json` in `Application.onCreate`. `BrowserStore.init`, `Passwords.init` (key store, decrypt, and `Thread.sleep(300)` on a failed read) and the two page scripts are loaded inside `remember {}` while the browser screen is first drawn.
- **Where:** `A/App.kt:31`, `A/engine/Engine.kt:62-68`, `A/ui/BrowserScreen.kt:165` and `190`, `A/browser/Passwords.kt:66`.
- **Fix:** Load on `Dispatchers.IO` and show the screen when ready; never sleep on the main thread.
- **Check:** `adb shell am start -W` cold-start time, and StrictMode disk-read log in a debug build.

### P3. Release build is not shrunk (M)
- **Where:** `app/build.gradle.kts:59` (`isMinifyEnabled = false`).
- **Fix:** Turn R8 on with keep rules for `com.yausername.**`, `org.libtorrent4j.**` and the `@JavascriptInterface` methods; add `isShrinkResources`. Smaller APK and faster start.
- **Check:** Compare APK size; run one HTTP, one torrent and one video download on the shrunk build.

### P4. Progress notification is rebuilt twice a second (S)
- **Where:** `A/service/DownloadService.kt:60-74`.
- **Fix:** Once a second, and only when the text or percent changed.
- **Check:** `adb shell dumpsys notification` shows the update count.

### P5. Whole history file is rewritten on every page visit (S)
- **Where:** `A/browser/BrowserStore.kt:135` (up to 2000 entries, `HISTORY_MAX` at line 48); address-bar suggestions also scan all of it on each key press (`148-154`).
- **Fix:** Longer write delay for history (5 s) plus the flush from B7; fine as it is until the cap is raised.
- **Check:** Method trace while browsing 20 pages quickly.

## UX gaps

### U1. No camera, microphone or location for sites (M)
- **Where:** `A/browser/TabWebView.kt:452-511`: the `WebChromeClient` has no `onPermissionRequest` and no `onGeolocationPermissionsShowPrompt`; the manifest asks for none of these permissions.
- **Fix:** Per-site ask/allow/block, kept beside the other per-site Shields.
- **Check:** A video-call test page and a "find my location" map page: both fail silently today.

### U2. Sites with a browser sign-in box can't be opened (S)
- **Where:** no `onReceivedHttpAuthRequest` in `A/browser/TabWebView.kt:335`.
- **Check:** `https://httpbin.org/basic-auth/a/b` shows an error instead of asking.

### U3. "Clear cookies" can't sign out of Google/YouTube (S)
- **Where:** `A/ui/SettingsScreen.kt:127-129`, `A/browser/SignIns.kt`.
- **Fix:** A second choice: "Clear everything, sign-ins too".
- **Check:** Clear cookies, open youtube.com: still signed in.

### U4. Saved sign-ins only match the exact host (S)
- **Where:** `A/browser/Passwords.kt:109-113` (`it.site == site`).
- **Fix:** Also offer sign-ins of the same base domain (`AdBlock.baseDomain`), marked as "from accounts.example.com".
- **Check:** Save on `login.example.com`, open `app.example.com`: nothing is offered.

### U5. Blocked pop-ups give no notice and no "open anyway" (S)
- **Where:** `A/browser/TabWebView.kt:478-510` only counts them; no pop-up text exists in `A/ui/`.
- **Fix:** A short bar: "Pop-up blocked. Open / Always allow on this site".
- **Check:** A bank or payment site that opens its sign-in in a new window after a script delay.

### U6. No tests for the browser side (M)
- **Where:** `app/src/test/` covers the engine, ad block rules and the address bar only; nothing for `Passwords`, `TabManager`, `SignIns`, `BrowserStore`, and there is no `androidTest`.
- **Fix:** Start with unit tests for S1, B2 and U4.

## Compared with desktop Ghostream

| Desktop feature | APK today | Gap |
|---|---|---|
| Password vault: encrypted, optional master password, 2FA codes, notes | Encrypted vault (AES-256-GCM, key-store key), fingerprint or screen lock, 5-minute unlock, save/fill/update, CSV import, Google backup (`A/browser/Passwords.kt`, `A/ui/Passwords.kt`) | No master password, no 2FA codes, no notes, no password generator: see F1 |
| Hide YouTube Shorts | Yes: setting `hideShorts`, on by default (`assets/page.js:123`, `A/engine/Model.kt` Settings) | None |
| yt-dlp video download from any site, automatic detection, Download button on videos | Yes: yt-dlp + FFmpeg bundled and self-updating (`A/engine/VideoTask.kt:43-50`), stream detection (`TabWebView.kt:345`, `page.js:342`), Download buttons on videos (`page.js:234`) | None found. One limit: private tabs' cookies and the proxy, see S2 and S4 |
| Downloads panel opens when a download starts | Yes from the browser: the downloads sheet opens after the Add panel (`A/ui/MainActivity.kt:280`) | With "quick download" the Add panel still flashes first; minor |
| Built-in ad blocking (desktop: Ghostery) | Yes, own engine with uBlock Origin's lists, EasyList, EasyPrivacy, cosmetic rules and scriptlets, refreshed every 4 days (`A/engine/AdBlock.kt`, `A/engine/Filters.kt:22-44`) | No user-added lists or custom rules screen was looked for: **unverified**. See F3 |
| Planned: built-in pop-up blocker | Already there: windows opened without a tap are dropped, tapped ones are probed and kept only on the same site, ad redirects and `intent:` ad links are stopped (`TabWebView.kt:359-385`, `478-510`, `assets/scriptlets.js:239`) | Add the notice and per-site allow (U5) |
| Planned: stronger YouTube ad blocking | Ad entries are cut out of YouTube's player data before the player reads it, plus mute-and-skip as a fallback, plus uBlock's own YouTube scriptlets from the lists (`assets/page.js:74-121`) | The key names and skip-button selectors are fixed in the APK: see F2 |

## Features to add

### F1. Bring the vault up to desktop level (L)
- **What:** Optional master password (key derived with Argon2 or PBKDF2, wrapping the vault key), 2FA code field per sign-in with a live 6-digit code and copy button, a notes field, a password generator on sign-up forms. Use the same export format as desktop so a vault can move between the two.
- **Where:** data model `A/browser/Passwords.kt:22-37` (add `totp`, `note`; `ignoreUnknownKeys` is already on, so old files still load), UI `A/ui/Passwords.kt`.
- **Check:** RFC 6238 test vectors for the codes; vault made on desktop imports on the phone and back.

### F2. Make YouTube ad rules updatable without a new APK (M)
- **What and why:** `AD_KEYS` and the skip selectors are fixed in `page.js`, so a YouTube change needs a new release. Fetch a small signed rules file with the filter lists.
- **Where:** `assets/page.js:78` and `117`; update plumbing in `A/engine/Filters.kt:75`.
- **Check:** Change the remote file, tap "Update lists", confirm the new keys are used without reinstalling.

### F3. Ad block controls users expect when they drop their extension (M)
- **What:** Custom filter list addresses, a "my rules" box, and an element picker ("hide this"). **Unverified** which of these exist; `ownList` in `A/engine/Filters.kt:67` suggests own rules are partly there.
- **Check:** Add a rule `example.com##h1`, reload example.com.

### F2b. Keep ad blocking at its best and updated automatically (M)
Today: uBlock Origin's lists (filters, privacy, badware, unbreak, quick fixes), EasyList and EasyPrivacy, bundled and checked for validity before use (`A/engine/Filters.kt:21-35`, `91-94`). The weak spots are when and how often they update.
- **Updates only happen while the app is open.** `updateIfDue` runs only when the browser screen opens (`A/ui/BrowserScreen.kt:139`) and only after 4 days (`Filters.kt:44`). A phone that sits unopened for a week starts with old lists. **Fix:** a daily WorkManager job (on any network, battery not low) that calls `updateIfDue`, then reloads the engine.
- **One 4-day timer for every list.** uBlock's "Quick fixes" list is where the YouTube and anti-adblock fixes land, often within hours. **Fix:** read each list's own `! Expires:` header and use it, with a short floor for quick-fixes (for example 12 h). Send `If-None-Match`/`If-Modified-Since` so a list that hasn't changed costs almost no data (`Net.kt:139` already reads the ETag).
- **The scriptlets lag behind.** uBlock's YouTube fixes call scriptlets (`json-prune`, `set-constant`, `trusted-replace-*` and others). Any scriptlet name missing from `assets/scriptlets.js` is skipped, so those fixes do nothing. **Fix:** log and count the scriptlet names the lists ask for that the engine lacks, then add them. Long term, ship the scriptlet bundle as an updatable file, signed and checked like F2.
- **Optional extra lists (off by default):** AdGuard Base/Mobile, AdGuard or Fanboy annoyances (cookie banners, "open in app" bars), and a regional list chosen from the phone's language.
- **Check:** set the clock 2 days ahead, leave the app closed, and confirm the WorkManager job refreshed `filters/*.txt`. Next to the counts in Settings, show "missing scriptlets: N" and aim for 0. Test on youtube.com: no pre-roll ads after a quick-fixes update with no new APK.

### F2d. Reach uBlock Origin / Brave level on every site (L)
The lists are already top tier, but the engine skips rules it can't run (`A/engine/AdBlock.kt:14-15`, `303`, `327`, `331`). The skipped rules are the ones that make hard sites work:
- **`$redirect` blocks instead of serving a harmless stand-in script** (`AdBlock.kt:327`). Sites that check that their ad script loaded (anti-adblock walls, video players that wait on Google IMA) break or show "disable your ad blocker".
- **`$badfilter` is skipped** (`:331`), so a rule the list authors switched off **stays on** and can break sites.
- **Procedural element hiding is skipped** (`:has-text()`, `:upward()`, `:remove()`, `:matches-css()`, `:xpath()`). Many fixes for anti-adblock pop-ups, "sponsored" posts and overlays need them.
- **Regex rules, `$removeparam` (tracking parameters), `$csp`, `$denyallow`/`$to` and `$header` are skipped.**

**Two ways to fix it:**
- **A. Recommended: use Brave's `adblock-rust` engine through JNI (L).** It is the engine Brave's Android browser runs, open source (MPL-2.0), and supports nearly all of the above, including uBlock's redirect resources and scriptlets. Those resources can then be updated with the lists instead of being fixed in `assets/scriptlets.js`. Keep the Kotlin engine as a fallback for one release. Cost: about +2–4 MB per ABI and an NDK build step.
- **B. Extend the Kotlin engine (M per feature, L in total).** In this order: `$badfilter` (S), `$redirect` with bundled stand-in resources (M), procedural `:has-text()`/`:upward()`/`:remove()` in `page.js` (M), regex rules (S–M), `$removeparam` (S).

**Pop-ups on every site** (adds to `TabWebView.kt:478-510`):
- Block **tab-unders**, where the page opens your link in a new tab and turns the old tab into an ad.
- Block **click hijacks**, where the first tap anywhere opens an ad window.
- Block **cross-site redirects with no tap**, whether by `location` change or meta refresh.
- Block **notification-permission spam** and **`beforeunload` traps**.
- Show a "Pop-up blocked · Allow on this site" bar (U5).

**Check:** score on public ad-block test pages (d3ward toolz, adblock-tester.com, canyoublockit.com) before and after. Run a fixed list of about 50 hard sites (streaming, news, file hosts, YouTube) and count ads seen, pop-ups opened and broken pages. Aim for parity with uBlock Origin in Firefox for Android on the same list.

### F2c. AI help for ad and pop-up blocking (M–L, after F2/F2b)
Filter lists stay first: they are fast, free and private. AI only handles what the lists miss. There are three layers, best value first:
1. **AI rule maker off the phone (M).** A scheduled job (for example a GitHub Action with Claude) opens YouTube and a test set of pop-up-heavy sites in a headless browser with Ghostream's rules. It spots ads or pop-ups that got through, writes candidate rules and opens a PR to the remote rules file (F2). A person approves it, and phones pick it up through the update in F2b. This keeps YouTube fixes coming within hours, with no AI running on the phone.
2. **On-device pop-up and redirect scoring (M).** A small rule-based or tiny on-device model (TFLite) scores each new window or redirect on signals such as: no tap first, different site, known ad-network patterns, very short time on the page. High scores are blocked; medium ones get "Pop-up blocked · Allow". Runs offline in well under 1 ms.
3. **Learning from the user (S–M).** Long-press an element and choose "Hide this" (F3) to save a rule. Repeated "Allow" taps on a site count as a trust signal. All of this stays on the device.
- **Not recommended:** sending pages to a cloud LLM while browsing. It is slow, costs money, and sends your browsing history off the phone.
- **Check:** for layer 1, a pre-roll ad that gets through on a test run produces a rule PR the same day. For layer 2, block rate and false blocks on a fixed set of 50 sites, before and after.

### F4. Passkeys and Android autofill (L)
- **What:** Offer the vault to other apps as an Android Autofill service, as desktop password managers do. Not in the manifest today.
- **Check:** The vault appears under Settings > Passwords & accounts.

### F5. Per-site permissions page (S, after U1)
- **What:** One place listing each site's Shields, scripts, cookies, pop-ups, camera, microphone and location.
- **Where:** site lists already live in Settings (`scriptBlockSites`, `thirdPartyCookieSites`, `httpsOffSites`, `adblockAllow`).
