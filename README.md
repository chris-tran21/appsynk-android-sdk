# AppSynk Android SDK

Kotlin SDK for Android install tracking, event measurement, and attribution — symmetric to the
AppSynk iOS SDK (same wire format, `platform = "android"`).

- **Reliable delivery** — disk-persisted queue + WorkManager: no event lost, even if the process is
  killed right after a purchase.
- **Google Ads attribution** — Play Install Referrer raw string (gclid/gbraid) parsed server-side.
- **Meta view-through** — native FB/Instagram content-provider collection.
- **Privacy-first** — GDPR consent, anonymized mode, opt-out toggles, backup-excluded device id.

## Requirements

- Android 5.0+ (`minSdk 21`)
- Kotlin 1.9+ / AGP 8.5+

## Installation

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.appsynk:sdk:1.0.0")
}
```

The SDK ships its own permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`) and the FB/IG
`<queries>` via manifest merge. Two host-app steps remain: **backup exclusion** (below) and,
optionally, the **Facebook App ID** for Meta attribution.

## Quick Start

```kotlin
// Application.onCreate()
AppSynkSDK.configure(this, apiKey = "ak_live_xxx")

// Anywhere
AppSynkSDK.trackEvent("level_complete", mapOf("level" to 5))
AppSynkSDK.trackRevenue(4.99, "USD", "premium_monthly", orderId = "GPA.1234")
AppSynkSDK.setUserId("user_12345")
```

Deep links — call from your launch/deep-link activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppSynkSDK.handleIntent(intent)
    AppSynkSDK.getDeepLinkData { data -> data?.deepLink?.let { route(it) } }
}
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); AppSynkSDK.handleIntent(intent) }
```

Java apps use `AppSynkJava` (callbacks instead of Kotlin lambdas):

```java
AppSynkJava.configure(this, "ak_live_xxx");
AppSynkJava.trackEvent("level_complete", Map.of("level", 5));
AppSynkJava.getDeepLinkData(data -> { if (data != null) route(data); });
```

## Public API

| Method | Purpose |
|---|---|
| `configure(context, apiKey, options?)` | Initialize once from `Application.onCreate()` |
| `trackEvent(name, properties?)` | Custom event |
| `trackRevenue(amount, currency, productId, orderId)` | Purchase (24h dedup on `orderId`) |
| `trackAdRevenue(network, amount, currency, adUnit?, adType?)` | Ad impression revenue |
| `setUserId(id)` / `setUserProperties(map)` | Persisted user identity (folded into `properties.user`) |
| `setConsent(gdpr, dataUsage, adsPersonalization)` | GDPR consent (attached to every event) |
| `anonymizeUser(enabled)` | Strip gaid/androidId, set `isAnonymized` |
| `handleIntent(intent)` | Resolve a deep link (App Link or `appsynk://`) |
| `getDeepLinkData(cb)` / `getAttributionData(cb)` | Attribution callbacks (buffered/replayed) |
| `reset()` | Logout — clears user identity + session |
| `flush()` | Send queued events now |

### Options (`AppSynkOptions`)

`environment`, `customApiUrl`, `logLevel`, `sessionTimeout` (1800s), `batchSize` (10),
`flushInterval` (30s), `referrerWaitTimeout` (10s), `disableGaid` / `disableAndroidId` /
`disableMetaReferrer`, `anonymizeUserByDefault`, `hmacSecretKey` / `hmacKeyId`.

## How attribution is collected (the gating flow)

`configure()` warms three collectors in parallel and gates the **install** event on them (bounded by
`referrerWaitTimeout`) — the Android analogue of iOS's ATT gating:

```
configure() ─┬─ Play Install Referrer  (gclid/gbraid → attribution.referrerUrl, raw)
             ├─ GAID                    (AdvertisingIdClient, honours LimitAdTracking)
             └─ Meta Install Referrer   (FB/IG content provider → attribution.metaInstallReferrer)
                        │
            withTimeout(referrerWaitTimeout) → install event (enriched, or with what was collected)
```

A slow Play service never blocks the app — the warmups keep running past the timeout and enrich
later events. Reliable delivery is handled by the persistent queue + a network-constrained
`WorkManager` worker, so an event survives a process kill.

## GAID & permissions

The Advertising ID is read off-main via `AdvertisingIdClient` and returned `null` when the user has
limited ad tracking. The `com.google.android.gms.permission.AD_ID` permission is declared by the SDK.
Set `disableGaid = true` to never read it.

## Meta attribution (optional)

Add your **Facebook App ID** to your app's `<application>` manifest as `<meta-data>`:

```xml
<meta-data android:name="com.facebook.sdk.ApplicationId" android:value="@string/facebook_app_id" />
<!-- or, without the Facebook SDK: -->
<meta-data android:name="com.appsynk.FacebookApplicationId" android:value="@string/facebook_app_id" />
```

The FB/IG/FB-Lite `<queries>` are shipped by the SDK. With no Facebook App ID or no FB/IG app
installed, Meta collection is skipped (no crash). Disable with `disableMetaReferrer = true`.

## Backup configuration (required)

The SDK stores a stable per-install device id and install-state flags that **must be excluded from
Android Auto Backup** — a restored copy on another device would corrupt attribution (a shared device
id) or suppress the install event. Reference the shipped rules from your `<application>` tag:

```xml
<application
    android:dataExtractionRules="@xml/appsynk_backup_rules"   <!-- Android 12+ (API 31+) -->
    android:fullBackupContent="@xml/appsynk_full_backup">     <!-- Android 6–11 (API 23–30) -->
```

Or merge these into your existing backup files:

```xml
<exclude domain="sharedpref" path="appsynk_id.xml" />
<exclude domain="sharedpref" path="appsynk_prefs.xml" />
<exclude domain="file" path="appsynk/" />
```

## Privacy / GDPR

```kotlin
AppSynkSDK.setConsent(
    isUserSubjectToGDPR = true,
    hasConsentForDataUsage = true,
    hasConsentForAdsPersonalization = false
)
AppSynkSDK.anonymizeUser(true) // strips gaid/androidId, sets isAnonymized=true
```

Consent is persisted in `EncryptedSharedPreferences` (API 23+, regular prefs below) and attached to
every event's root `consent` block. Start anonymized with `anonymizeUserByDefault = true`.

## License

MIT
