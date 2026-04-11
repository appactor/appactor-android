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
    implementation("com.appactor:appactor-android:0.0.2")
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

// Check entitlements
val info = AppActor.shared.getCustomerInfo()
val isPremium = info.hasActiveEntitlement("premium")
```

## Documentation

Visit [appactor.com/docs](https://appactor.com/docs) for full documentation.

## Contributing

- Open an issue for bug reports or feature requests
- Email us at [sdk@appactor.com](mailto:sdk@appactor.com)

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
