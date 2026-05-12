<h1 align="center" style="border-bottom: none">
<b>
    <a href="https://appactor.com">
        AppActor
    </a>
</b>
<br>In-App Purchase Infrastructure
<br>for Android
</h1>

<p align="center">
<a href="https://github.com/appactor/appactor-android/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
<img src="https://img.shields.io/badge/Android-24%2B-green.svg">
<img src="https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg">
</p>

AppActor handles in-app purchases, subscriptions, and entitlements so you can focus on building your app.

## Installation

```kotlin
dependencies {
    implementation("com.appactor:appactor-android:0.1.3")
}
```

## Quick Start

```kotlin
// Configure once
AppActor.shared.configure(
    context = applicationContext,
    apiKey = "pk_YOUR_API_KEY",
)

// Fetch offerings
val offerings = AppActor.shared.offerings()

// Make a purchase
val result = AppActor.shared.purchase(activity, offerings.current?.monthly!!)

// Direct store purchases are supported via AppActorPurchaseParams, but only
// when the exact Play target is known:
// productType is required
// subscriptions require basePlanId
// bare productId-only purchases are intentionally rejected

// Check entitlements
val info = AppActor.shared.getCustomerInfo()
val isPremium = info.hasActiveEntitlement("premium")
```

## Payment Restore & Retry Policy

`configure()` starts the SDK, establishes the local AppActor user, warms billing
state, runs the startup purchase reconciliation flow, and refreshes customer
info. It does not perform a user-visible Google Play restore flow.

Initialize AppActor only from your app's main process. The SDK maintains local
receipt queue and posted-ledger state on disk and assumes a single AppActor
runtime is writing that state. Apps that declare services or other Android
processes should guard `configure()` with their own main-process check.

For anonymous users, an app reinstall creates a new local AppActor user unless
your app passes a stable `appUserId`. To recover previous Play Store purchases
after reinstall, call `syncPurchases()` from your own account recovery flow or
show a user-triggered restore button that calls `restorePurchases()`.

Apps with their own account system should configure AppActor with the same stable
`appUserId` for that account on every install. That keeps entitlements attached to
the account and avoids relying on restore as the primary identity mechanism.

Consumable, token, and credit products should be granted only after your backend
accepts the receipt and AppActor returns an `ok` receipt result/customer update.
Retryable receipt failures remain queued for later delivery and should not be
treated as final backend credit grants.

## Documentation

Visit [appactor.com/docs](https://appactor.com/docs) for full documentation.

## Contributing

- Open an issue for bug reports or feature requests
- Email us at [sdk@appactor.com](mailto:sdk@appactor.com)

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
