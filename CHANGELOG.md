# Changelog

## Unreleased

Fixes from the 2026-09-26 SDK audit (#14, #16, #17, #18, #19 and the last Android batch). Audit ids in parentheses. The version is set when the release is cut.

Behaviour changes apps can see:

- Changed: placeholder appUserIds (`"null"`, `"guest"`, `"0"`, ...) given to `configure()` mean signed out: it keeps an anonymous id or starts a new one and never inherits the last signed-in user. Ids with `/` or control characters throw from `configure()`, and `logIn()` rejects both. A stored id that breaks the backend's rules is replaced with a new anonymous id. (D1)
- Changed: a launch no longer restores acknowledged Play purchases on its own. After a reinstall, or a logout and relaunch, a subscriber shows no premium until they tap Restore or the app calls `syncPurchases()`, as the README documents and as on iOS. `syncPurchases()` and `restorePurchases()` still sync everything. (D4)
- Changed: `reset()` can't be cancelled, so a `withTimeout` or `viewModelScope` around it no longer cuts the wipe short. It also waits for fetches the app started, and cancels their HTTP calls. (C3, D5b, C31)
- Changed: attribute writes (`setAttribute` and the helpers) return normally when the server rejects a value for good (400, 409, 413, 422) and log a warning; before, they threw and the queue stayed jammed. 401/403 still throw and keep the queue. A key the backend defined as `string_array` now rejects empty typed arrays too. (C2, C29)
- Changed: public error types. Kotlin callers of `logIn`, `configure`, `setFallbackOfferings`, `syncPurchases`, `drainReceiptQueueAndRefreshCustomer` and `restorePurchases` get `InvalidConfiguration`, `Decoding`, `Network` or `Server` instead of `IllegalArgumentException`, `SerializationException` or internal backend exceptions. A restore 5xx/429 is a transient `Server` error with its status and retry-after; a restore signature failure is its typed error. Wrapper codes: a queued receipt 2012 (was 2005), a second purchase 2013 (was 2003), a blank key or invalid id 2003 and bad fallback JSON 2006 (were 2099), a restore 5xx/429 2007 (was 2099), a restore signature failure 2015. (C5, D12, D21)
- Changed: a second concurrent `purchase()` throws `PurchaseAlreadyInProgress`; a paid purchase whose receipt is queued with no offline info throws `ReceiptQueuedForRetry`; `purchase()` during `reset()` throws `NotConfigured` instead of hanging. Bridge and Java purchase callbacks still don't fire across a reset. (D12, C26)
- Changed: an unsigned offerings or remote config response fails with `SignatureMissing`, even when a cache exists. Unverified cache entries written by older versions are deleted at the first `configure()`, together with the offline product catalog derived from the offerings, so a user who upgrades while offline has no offline entitlements until the next offerings fetch (as on iOS). (E5a)
- Changed: a 304 counts only when it answers the ETag the SDK sent and its signature verifies; otherwise the SDK asks once more without the ETag. A 304 to a request without an ETag is refused (`CACHE_INCONSISTENCY`), and offerings then serve their cache. (D31)
- Changed: `onCustomerInfoChanged` and the plugin's `customer_info_updated` no longer fire for unchanged info. The first delivery after an identity change or a listener swap still fires. (D19)
- Changed: `canMakePurchases()` and `getStoreCapabilities()` are false/empty on devices without working Play billing, and the first store call of a session can wait up to 4 s for the billing setup. `launchBillingFlow` runs on the main thread, as Google requires. (C23, C24)
- Changed: `logIn()` retries the backend's "concurrent identity merge in progress" 409 (up to 3 attempts, about 1-6 s) before failing as before. (D30)
- Changed: offline, a subscription seen outside the purchase flow gets entitlements only from the cached customer, since Play doesn't report its base plan. A live Play purchase that carries a pending prepaid plan update is ignored until the update is paid; a waiting purchase flow reports it as pending. (C21, D8b)
- Changed: restore and sync send at most 20 purchases per request, so they may send several. (D9)
- Changed: logged timestamps, `millisToIso8601` and restore `observedAt` always print milliseconds (`...:00.000Z`). (K2)
- Changed: the published POM declares `kotlinx-coroutines-android` (it used to come through `androidx.core`).

Fixes:

- Fixed: the app crashed when the attribute flush after `configure()` failed for good, e.g. a wrong API key's 401. Exceptions from the SDK's background work are now logged instead of reaching the app. (K1)
- Fixed: crashes on API 24-25 from `java.time`, which the SDK no longer uses itself. The public `*Instant` helpers still need API 26 or core library desugaring. (K2)
- Fixed: receipts. A 429 or 408 on a receipt POST stays queued with the server's Retry-After instead of being dead-lettered; dead letters are re-posted at launch for up to 30 days and never consumed or acknowledged twice; a consume that gets ITEM_NOT_OWNED counts as done when Play no longer lists the purchase; the retry wake no longer cancels its own drain. (C1, D2, C8, D17)
- Fixed: purchases. A DEFERRED plan change is no longer posted as the new product; a subscription seen outside the purchase flow is posted without a guessed plan, offer or price; a package purchase launches the offer the package shows; a synchronous launch failure no longer ends in "Already resumed". (D8a, C21, C10, C24)
- Fixed: one user's state leaking into the next user's session. Customer info never moves the identity, and drains, syncs and purchase updates report only the current user's; attribute writes and queued receipts stay with the user they were made for; `customerInfoFlow` is one process-wide flow that keeps working across `reset()`; `reset()` is no longer undone by in-flight work; a listener set while `configure()` runs is no longer lost. (C16, C7, C27, D3, D6, D5a, C17)
- Fixed: a failed `logIn()` no longer clears the current user's caches. (D15)
- Fixed: offerings. Calls no longer hang after a failed enrichment, a cancelled caller or a reset; a 304 after a new payload failed to enrich serves that payload instead of the older one in memory, and a 304 that confirms the offerings in memory restarts their freshness; bundled fallback offerings used at startup are stale at once, so the next call asks the backend. (C9, C31, C13, C14)
- Fixed: experiments. A later session no longer erases assignments stored by an earlier one, and a user adopted by restore or sync no longer gets the previous user's cached assignments, online or offline. (D16, C15)
- Fixed: remote config on an offline cold start; a 429 on customer info keeps the cached entitlements; offline entitlements use purchased (not pending) purchases only; a queued purchase no longer hides the user's other entitlements; the backend's `grace` status reads as `GracePeriod`; a `null` productId no longer breaks customer decoding. (C11, D14, C12, C22, D28, C19)
- Fixed: background drains publish the customer info the backend returned, and no false "deferred purchase resolved" is reported after a sync. (D20, C6)
- Fixed: an activity started before `configure()` (Flutter, React Native, Capacitor, or a reset and reconfigure) no longer makes an in-app activity switch or the Play purchase sheet look like the app going to the background, which kept the offerings cache for 24 hours and stopped the 5-minute customer refresh. (C18)
- Fixed: a signed response can't be relayed to another user's request, and a nonce replayed by OkHttp's silent retry is sent once more with a fresh nonce. (D25, D11)
- Fixed: attribution fields are cut to the backend's limits instead of the whole attribution being rejected; the device country goes out only as an ISO alpha-2 code; pending entries and remote config keep the appUserId verbatim instead of trimmed. (C30, C20, D22)
- Fixed: receipt-queue and ledger warnings no longer log Play purchase tokens. (D24)

## 2.3.15

- Added: `AppActorOffering.offeringKey` (the dashboard lookup key, falling back to `id`), `AppActorOfferings.getOffering(offeringKey)` / `offerings["key"]` / `allOfferings` (current first), and `AppActor.getOffering(offeringKey, fetchPolicy)` to fetch and look up in one call. Also on `AppActorBridge` and `AppActorJava.getOfferingAsync`.
- Removed: `AppActorOfferings.offeringByLookupKey(lookupKey)` — call `getOffering(offeringKey)` instead (a one-line rename; the compiler points at every call site).
- Added: `AppActor.getExperiment(experimentKey)` returns an `AppActorExperiment` that is never null — `isEnrolled`, `variantKey`, `isVariant(key)`, `boolValue / stringValue / intValue / doubleValue(defaultValue)` and `experiment["key"]` for JSON payloads — so a feature check no longer needs a null-check plus a typed accessor plus a default. `getExperimentAssignment` is unchanged. Also on `AppActorBridge` and `AppActorJava.getExperimentAsync`.

## 2.3.14

- Added: `AppActorPackage` now exposes the resolved subscription offer's pricing phases publicly via `pricingPhases: List<AppActorPricingPhase>` plus `freePhase` / `introPhase` / `fullPricePhase` / `hasFreeTrial` helpers, so a paywall can render "3 days free, then ₺39.99/week" dynamically from the offer the SDK auto-selected (RevenueCat / Adapty parity). Each `AppActorPricingPhase` carries the ISO-8601 `billingPeriod` (with a parsed `period` of `AppActorSubscriptionPeriod`), `formattedPrice`, `priceAmountMicros`, `currencyCode`, `billingCycleCount`, `recurrenceMode` (`AppActorRecurrenceMode`), plus computed `isFreeTrial` and `paymentMode` (`AppActorOfferPaymentMode`). Existing `price` / `localizedPriceString` fields are unchanged (still the full recurring price); Apple and one-time products expose an empty list.
- Changed: Google Play subscription purchases that do not name an explicit offer (`offerId == null`) now auto-apply the best eligible offer Play returns for the base plan — the longest free trial, else the cheapest introductory price, else the base plan itself (RevenueCat parity). Previously such purchases always resolved to the base-plan token at full price, even when the user was eligible for a free trial. Ties resolve deterministically to the first offer in Play's order. Opt an offer out of auto-selection by adding the `aa-ignore-offer` tag to it in Play Console; explicitly pinned offer ids keep today's exact-match behavior (including the base-plan fallback and ambiguity fail-fast).
- Added: subscription offer payloads now capture Google Play offer tags and the full pricing-phase list internally (previously only the final recurring phase was kept), enabling trial/intro-aware ranking and future trial-period exposure.
- Fixed: offerings enrichment no longer drops a package when the store adapter resolves it to a different offer than the backend named (offer auto-selection, or the 2.3.13 base-plan fallback for a pinned-but-unavailable offer) — the resolved product is now also indexed under the request's key.

## 2.3.13

- Fixed: a Google Play subscription purchase that names a specific offer (e.g. a free trial) now falls back to the base plan when Play does not return that offer for the user — most commonly a returning / trial-ineligible user for whom Play omits the offer. Previously the product was dropped or an `InvalidConfiguration` was thrown, so a returning user could not subscribe at all; now they are charged the standard base-plan price (matching RevenueCat and Adapty). The fallback only ever resolves to the base plan (never a different offer), and logs a warning so a misconfigured/typo'd offer id stays observable.

## 2.3.12

- Added: entitlement state now seeds from the persisted cache (and, on a cache miss, from local Play Billing purchases) at cold start, before the network refresh.
- Improved: the automatic device-attribute sync is skipped when nothing changed since the last confirmed delivery, removing a redundant per-launch network write.

## 2.3.11

- Fixed: a corrupt or forward-incompatible `receipt_queue.json` is now quarantined to a `.corrupt` sidecar instead of being deleted, so a single bad record no longer wipes every queued (paid) receipt. The sidecar is purged on `clear()`/`reset()` so it never retains receipt data past a logout. (audit android-7)
- Fixed: `AppActorBridge.setFallbackOfferings` now decodes off the caller thread and delivers callbacks on the main thread via `launchAsync`, matching every other bridge method; malformed JSON still surfaces as `CODE_DECODING`. (audit android-12)
- Cleanup: `purchaseDateString()` delegates to the shared `AppActorIso8601` formatter instead of re-implementing a per-call `SimpleDateFormat` (byte-identical output). (audit android-25)

## 2.3.10

- Refactor: decomposed the 2357-line `AppActorPaymentProcessor` god-class into a ~1082-line orchestrator + 6 focused collaborators (`AppActorReceiptQueueDrainer`, `AppActorRestoreSyncCoordinator`, `AppActorRetryWakeScheduler`, `AppActorIdentityTransitionBuffer`, `AppActorPendingPurchaseRegistry`, `AppActorOfflineCustomerInfoBuilder`). Strictly behavior-preserving (no logic, ordering, error-handling, or lock-semantics change; shared `pipelineMutex` stays in the orchestrator, exclusive locks moved with their state). Internal-only — public API unchanged. (audit android-1)

## 2.3.9

- Fixed: offline entitlement fallback now unions store-derived keys with the cached server-authoritative set instead of returning only store-derived keys, so promo/grant/cross-platform entitlements are no longer dropped offline. (audit android-3)
- Fixed: entitlement `willRenew` is now `false` when `unsubscribeDetectedAt` is set (was derived from active/grace status, so cancelled-but-active subscriptions wrongly reported `willRenew = true`). (audit android-4)
- Fixed: the retry-wake scheduler guards all shared state under a dedicated lock with an identity-checked completion cleanup, closing a data race. (audit android-6)
- Fixed: blank-argument validation errors now surface as `CODE_VALIDATION` (`InvalidConfiguration`) instead of `CODE_UNKNOWN`. (audit android-10)
- Fixed: the receipt-queue and posted-ledger stores retry the atomic rename instead of falling back to an in-place `writeText`, so a failed rename can no longer truncate the durable file. (audit android-19)
- Changed: `reset()` / `AppActorBridge.reset()` no longer clear registered listeners, so customer-info / receipt-pipeline / deferred-purchase callbacks keep working after a reconfigure (previously they could go silent because event listening is idempotent). Callback delivery is now session+identity-epoch guarded, so stale callbacks from a superseded session/identity are dropped rather than fired.

## 2.3.8

- Published the current Android SDK line with MIT-aligned Maven metadata so Central matches the repository license.

## 2.3.7

- Harden automatic profile context refreshes during identity transitions and keep the post-transition refresh off the `logIn`/`logOut` return path.

## 2.3.6

- Automatically sync privacy-safe profile context during `configure()` while keeping identifier collection behind `collectDeviceIdentifiers()`.
- Dropped invalid non-alpha-2 locale country values from automatic profile context so best-effort context cannot poison the attribute queue.

## 2.3.5

- Aligned bridge, Java facade, and plugin `syncPurchases` semantics with quiet purchase sync.
- Kept `drainReceiptQueueAndRefreshCustomer` as the explicit queue-drain API for advanced/internal use.

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
