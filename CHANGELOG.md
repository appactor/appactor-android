# Changelog

## 0.1.0

- Added Google Play purchase price and currency propagation from resolved Play Billing products into receipt submissions.
- Persisted receipt queue price metadata so retries and delayed drains keep transaction economics intact.
- Updated the Android plugin bridge offering payloads with `price_amount_micros` for Flutter visibility.

## 0.0.9

- Resolved Play Billing lookups through `storeProductId` while preserving logical public product identifiers.
- Buffered live purchase updates during identity transitions against the captured previous identity and suppressed stale-user deferred callbacks.
- Restored same-user login deferred purchase callbacks and customer info publishing when the buffered purchase still belongs to the current user.
- Surfaced offline entitlement bridge errors and routed debug events through the SDK log handler/plugin `sdk_log` event stream.
- Persisted one-time offline catalog keys using store product identifiers for consumable and non-consumable recovery.

## 0.0.8

- Established the local `appUserId` during `configure()` and aligned startup flows around the resolved local identity.
- Switched `logOut()` to the local-only anonymous reset flow and removed the backend logout dependency from the Android SDK surface.
- Tightened bootstrap sequencing so offerings warmup runs in the background while purchase sync, dead-letter retry, and customer refresh complete deterministically.
- Extended bridge/plugin configure flows with optional `appUserId` support and refreshed Android release metadata for Maven Central publishing.
