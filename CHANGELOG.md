# Changelog

## 2.3.4

- Added optional purchase placement forwarding for explicit purchase receipts.
- Omitted blank and overlong placements before receipt submission to match backend limits.
- Preserved null placement for restore, sync, background update, and webhook-originated transactions.

## 2.3.3

- Added attribution helper null-clear support for campaign, creative, keyword, ad, ad group, and media source fields.
- Rejected unsupported Android purchase quantities at the plugin bridge instead of silently ignoring them.
- Kept install referrer retries available when no referrer is recorded and capped stale attribution snapshots with queued users.

## 2.3.2

- Added customer attribute polish for RevenueCat-style migration helpers, nullable custom-attribute unsets, profile validation, and typed date payload parity.
- Expanded Android system profile-current context with platform, wrapper platform, and timezone metadata while keeping explicit helpers for sensitive identifiers.
- Updated the Android plugin bridge to keep `set_attributes` developer-custom only, reject null bridge values in favor of `unset_attribute`, and decode typed date envelopes from Flutter.

## 0.1.3

- Classified background purchase updates as queued source intent while preserving explicit foreground purchases for backend billing classification.
- Kept boot, sync, restore, and retry receipt paths from being treated as new subscriber purchase intent.

## 0.1.2

- Added source intent tagging for purchase, restore, and sync receipt flows while preserving queued purchase intent across retries.
- Kept restore and sync replays distinguishable from live purchase submissions for backend billing classification.

## 0.1.1

- Clarified restore, reinstall identity, main-process, and retryable receipt queue policies in the Android SDK documentation.
- Removed stale retry-attempt exhaustion helpers so retryable receipt failures remain queued instead of implying a dead-letter threshold.
- Preserved cached Play Store storefront country code on receipt submissions.

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
