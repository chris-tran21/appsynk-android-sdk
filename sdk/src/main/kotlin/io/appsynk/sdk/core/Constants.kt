package io.appsynk.sdk.core

/**
 * SDK-wide constants. Single source of truth for the base URLs and the SDK version, so a release
 * bump or a host change is a one-line edit referenced everywhere.
 *
 * Mirrors the iOS `AppSynkConstants` — only [PLATFORM] differs (`"android"` vs `"ios"`).
 */
object AppSynkConstants {

    /** SDK semantic version. Bump on each release — sent as the `X-SDK-Version` request header. */
    const val SDK_VERSION = "1.0.0"

    /** Platform identifier — sent as the `X-SDK-Platform` header and as `platform` on every event. */
    const val PLATFORM = "android"

    /** Production API base URL. */
    const val PRODUCTION_BASE_URL = "https://api.appsynk.io"

    /**
     * Sandbox API base URL. Identical to production: the backend routes sandbox vs live from the
     * API key prefix (`ak_sandbox_` / `ak_live_`), not from the host. Kept as a distinct constant
     * so a future split (e.g. a sandbox subdomain) is a one-line change.
     */
    const val SANDBOX_BASE_URL = "https://api.appsynk.io"
}
