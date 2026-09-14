# Cookie Munch — Android SDK

A small, dependency-light Kotlin consent client for the self-hosted **Cookie Munch**
CMP. It mirrors the React Native and iOS SDKs so consent records are uniform across
platforms:

- Standard categories: `necessary` (always on) + `preferences`, `statistics`, `marketing`.
- **Implied** default until the visitor makes an explicit choice.
- Pluggable storage: in-memory by default, or **EncryptedSharedPreferences**-backed.
- `load` / `accept` / `decline` / `submitCustom` / `set`, a reactive `StateFlow`, and
  `onChange` callbacks.
- Region-aware, **offline-safe** API sync — a decision is never lost if the POST fails.
- A `gate(category) { … }` helper to guard consent-dependent code.

## Install (Gradle)

The module is published from this repo as the `:cookiemunch` Android library. Its
coordinates are:

```
group:   net.cookiemunch
name:    cookiemunch
version: 0.1.0
```

Consume it as a project module:

```kotlin
// settings.gradle.kts
include(":cookiemunch")

// app/build.gradle.kts
dependencies {
    implementation(project(":cookiemunch"))
}
```

Requirements: `minSdk 24`, `compileSdk 34`, Kotlin 1.9.22, JDK 17, Jetpack Compose.

## Usage

```kotlin
import net.cookiemunch.Category
import net.cookiemunch.Choices
import net.cookiemunch.CookieMunchConsent

// At startup — EncryptedSharedPreferences-backed, persisted record auto-loaded:
val consent = CookieMunchConsent.secure(
    context = applicationContext,
    cbid = "your-site-cbid",
    apiUrl = "https://cmp.example.com",
    region = "EU", // sent as the X-CookieMunch-Region header
)

// Record decisions (suspend — call from a coroutine):
lifecycleScope.launch { consent.accept() }
lifecycleScope.launch { consent.decline() }
lifecycleScope.launch { consent.submitCustom(Choices(preferences = true, statistics = false, marketing = true)) }
lifecycleScope.launch { consent.set(Category.STATISTICS, true) }

// Observe:
val hasResponded = consent.hasResponse()
consent.onChange { state -> /* react to changes */ }
// or collect consent.state (StateFlow<ConsentState>) from Compose / a coroutine.
```

### Gate pattern

Run consent-dependent code only when a category is granted. `gate` returns the block's
result, or `null` when the category is not granted:

```kotlin
consent.gate(Category.STATISTICS) {
    analytics.start()
}

val id = consent.gate(Category.MARKETING) { adSdk.deviceId() } // null if not granted
```

### Compose banner

```kotlin
setContent {
    Column {
        // ... your app ...
        ConsentBanner(consent) // renders only until the visitor responds
    }
}
```

## Storage

- `InMemoryConsentStorage` — the default; nothing hits disk. Handy for tests.
- `EncryptedPrefsConsentStorage` — persists the record in **EncryptedSharedPreferences**
  (`androidx.security:security-crypto`), encrypted at rest with a Keystore-backed master
  key (AES-256 SIV keys / AES-256 GCM values). `CookieMunchConsent.secure(...)` wires
  this up for you.

  > Note: this uses the `MasterKey.Builder` API from the `security-crypto` **1.1.x**
  > line (`1.1.0-alpha06`), not the older `MasterKeys` helper from `1.0.0`.

Implement `ConsentStorage` yourself to plug in DataStore or any other backend.

## Offline-safe sync

Every explicit decision is written to storage **before** the network POST is attempted.
The POST (`POST {apiUrl}/api/v1/consent`, with `X-CookieMunch-Region`) runs on
`Dispatchers.IO` and its failures are swallowed — a `decline()`/`accept()` call never
throws because the device is offline. Inject a custom `ConsentTransport` to change the
HTTP layer (the default uses `HttpURLConnection`, no extra dependencies).

## Testing

Unit tests live in `src/test/java/net/cookiemunch/` and run on the JVM with no device or
network (an injected `ConsentTransport` records/fails POSTs). Run them via:

```bash
# This repo has no Gradle wrapper; use a system Gradle (as CI does):
gradle -p native/android :cookiemunch:test

# CI builds the release artifact with:
gradle -p native/android :cookiemunch:assembleRelease
```
