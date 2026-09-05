# BiteSite Android Apps (Capacitor)

Two native Android shells around the BiteSite web app. Both load the deployed
Azure backend in a WebView and rely on same-origin session cookies / CSRF, so no
frontend is bundled — the apps are thin remote-URL shells.

| App | Directory | Package ID | Remote URL |
|-----|-----------|-----------|------------|
| Student | `android-student/` | `in.bitesite.app` | `https://bitesite-app.azurewebsites.net` |
| Outlet | `android-outlet/` | `in.bitesite.outlet` | `https://bitesite-app.azurewebsites.net/canteen` |

Paths below are relative to the repository root.

The outlet app loads the `/canteen` path so the backend's path-based portal
routing resolves it to the OUTLET portal and serves the canteen console. That
routing lives in
[`PortalResolver.java`](../src/main/java/com/bitesite/config/PortalResolver.java)
and is covered by
[`PortalResolverTest`](../src/test/java/com/bitesite/config/PortalResolverTest.java).

> **Deployment prerequisite:** `PortalResolver` checks the `APP_TARGET` env var
> *before* the path, pinning the whole process to one portal. It defaults to
> empty (`application.yml`), which is what lets a single deployment serve every
> portal. **Do not set `APP_TARGET` on the shared Azure deployment** — pinning it
> to `APP` makes `/canteen` resolve to the app portal, and `PortalGateFilter`
> will 403 every canteen manager using the outlet app.

## Prerequisites

- **JDK 21** (newer JDKs break some tooling; the project targets 21)
- **Android SDK** (Android Studio or a standalone SDK; `ANDROID_HOME` set)
- **Node.js + npm** (for Capacitor CLI / asset generation)

## Build a debug APK

From the app's `android/` directory:

```bash
cd android-student/android
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew assembleDebug
```

The debug APK lands at:

```
android-student/android/app/build/outputs/apk/debug/app-debug.apk
android-outlet/android/app/build/outputs/apk/debug/app-debug.apk
```

## Re-sync the Android platform after changing config

```bash
cd android-student
npm install
npx cap sync android
```

## Regenerating launcher icons / splash

Source images live in `resources/` (`icon-only.png`, `icon-foreground.png`,
`splash.png`). Regenerate the Android assets with:

```bash
npx capacitor-assets generate --android
```

## Plugins

| Plugin | Apps | What it does here |
|---|---|---|
| `@capacitor/splash-screen` | both | `launchAutoHide` is **false**, because a remote-URL shell otherwise shows a blank WebView for the whole first round trip. Dismissing it is the web app's job: `initNativeShell()` in `app.js` hides it on every page, and `www/offline.html` hides it too. |
| `@capacitor/network` | both | Overrides `navigator.onLine`, which in a WebView reports whether an interface exists rather than whether it carries traffic. It drives the existing offline bar through `window.__bitesiteNativeOnline`. |
| `@capacitor/app` | both | Reloads a backgrounded order screen on `resume` instead of waiting out the poll interval. |
| `@capacitor/keyboard` | both | `resize: native`. |
| `capacitor-razorpay` | student | Native checkout. See below. |
| `@capacitor/push-notifications` | student | FCM order alerts, the only channel that can reach these apps. See below. |

Deliberately **not** installed, because Capacitor 8 already covers them:
`@capacitor/geolocation` (`BridgeWebChromeClient` requests the runtime location
permission for `navigator.geolocation` on its own), `@capacitor/camera`
(`onShowFileChooser` handles `<input type="file">`), and `@capacitor/status-bar`
(superseded by the built-in auto-registered `SystemBars`).

## Why checkout is native in the app

`BridgeWebViewClient.shouldOverrideUrlLoading` does not check `isForMainFrame()`,
so **every** navigation, iframes included, goes through `Bridge.launchIntent`,
which throws any host that is not ours out to the system browser. Razorpay's web
checkout moves through its own domains and then the card issuer's 3-D Secure
page, and those bank hosts cannot be allow-listed ahead of time.

Widening `server.allowNavigation` would also be a downgrade rather than a fix:
those entries land in `allowedOriginRules`, which Capacitor passes to
`addWebMessageListener(webView, "androidBridge", ...)`, exposing the native
bridge channel to whatever origin was added.

So [`checkout.html`](../src/main/resources/templates/student/checkout.html)
branches on `window.Capacitor.isNativePlatform()`: the app calls
`Capacitor.Plugins.Checkout.open(...)` and the web keeps `new Razorpay(...)`.
Both hand the same three fields to the same unchanged server-side verification.

## Safe-area handling

The shells run `targetSdkVersion 36`, where Android forces edge-to-edge and
content draws under the status bar. Two tokens in `01-tokens.css` carry the
insets, and both read **two** sources:

```css
--safe-area-bottom: max(env(safe-area-inset-bottom, 0px), var(--safe-area-inset-bottom, 0px));
```

Android WebView only passes real insets through to `env()` from WebView 140
onward. Below that, Capacitor's `SystemBars` sets `--safe-area-inset-*` as inline
custom properties instead, which `env()` never sees. `max()` takes whichever is
live; on the desktop web both fall back to `0px` and nothing moves.

## Notes

- **No custom `WebViewClient`.** Capacitor's bridge already keeps the remote
  host inside the WebView and hands external URLs to the system. Adding a custom
  client would replace the bridge's and break navigation/error handling.
- **`server.errorPath`** points at the bundled `www/offline.html`. Capacitor
  routes both `onReceivedError` (no network) and `onReceivedHttpError` (5xx) to
  it, which also guarantees the non-auto-hiding splash always gets dismissed.
- **Student app** declares `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` for
  nearest-canteen selection. The outlet app does not.
- **Phase 2** (not yet done): release signing, signed AABs, Play CI.

## Push notifications (FCM)

Web Push cannot reach these apps: `app.js` guards on `'PushManager' in window`
and Android's WebView does not implement the Push API, so the VAPID path never
registers a subscription there. FCM is a **second channel alongside** Web Push,
not a replacement. [`OrderNotifier`](../src/main/java/com/bitesite/service/OrderNotifier.java)
sends to both, because someone signed in on the phone app and a laptop browser
holds a row in each table and should hear about the order in both places.

Push is in the **student app only**. The outlet app does not get the plugin: the
push UI lives solely in the student templates and every `notifyOrderUpdate` call
targets the order's own student, so there is no staff notification path for it to
use yet.

Everything is wired. It stays dormant until two credentials exist, and both the
app build and the server run normally without them.

**1. The app.** In the Firebase console create a project, add an Android app with
package `in.bitesite.app`, and download `google-services.json` to:

```
android-student/android/app/google-services.json
```

No Gradle edits are needed. Capacitor's generated `app/build.gradle` already ends
with a block that applies `com.google.gms.google-services` only when that file is
present, and the root `build.gradle` already carries the classpath. Dropping the
file in is the whole step. Rebuild afterwards.

**2. The server.** Generate a service-account key (Project settings, Service
accounts, "Generate new private key") and give it to the app as **one** of:

| Variable | For |
|---|---|
| `FIREBASE_CREDENTIALS_JSON` | Azure. The whole JSON as a single app setting; no file to mount. |
| `FIREBASE_CREDENTIALS_PATH` | Local dev, pointing at the downloaded file. |

Both files are gitignored, for different reasons. The service-account key is a
real secret: it can send notifications as this project. `google-services.json` is
not a secret in Google's sense, since it ships inside every APK and anyone with
the app already has it, but **this repository is public** and no workflow here
builds the Android apps, so committing it would publish the project id and API
key to scrapers for no benefit. Each developer drops their own copy into
`android-student/android/app/` locally.

Regenerate it any time without going through the console:

```bash
firebase apps:sdkconfig ANDROID 1:69524657250:android:8ef93da1b44c119b736d3b \
  --project bitesite-113 -o android-student/android/app/google-services.json
```

Until both exist, `FcmSender.isConfigured()` is false and `OrderNotifier` skips
the channel, exactly as blank VAPID keys already skip Web Push. Tokens are still
accepted and stored while the channel is off, so installs do not need to be
reopened once credentials land.

### Consent

Nothing prompts unannounced. The existing `#push-toggle` and `#push-invite` now
branch on `isNativeShell()` and drive `PushNotifications` in the app, so the
Android permission dialog appears only when the student uses the same opt-in they
would in the browser. "Off" deletes the token server-side, which is what actually
stops delivery, since Android has no in-app way to revoke the permission.

### One consequence of native checkout

The Razorpay SDK adds `NFC` (`com.razorpay:standard-core`) and
`READ_BASIC_PHONE_STATE` (`com.razorpay:core`) to the merged manifest. They will
appear on the Play listing, so they are worth knowing about before submission.
