# AppActor SDK Cross-Platform Parity Report
## iOS vs Android — Detailed Comparison

**Date:** 2026-04-11
**Scope:** Feature parity, architectural alignment, risk assessment

---

## Executive Summary

Both SDKs share the same core architecture: server-authoritative billing, durable receipt queue, ETag caching, HMAC/Ed25519 signature verification, identity management, and hybrid plugin support. However, there are **8 significant parity gaps** and **5 minor differences** that should be addressed to ensure consistent behavior across platforms.

---

## CRITICAL PARITY GAPS

### GAP-1: Dead-Letter Recovery at Startup
| | Android | iOS |
|--|---------|-----|
| **Behavior** | `retryDeadLetteredItems()` runs at startup after purchase sync. Consumes dead-lettered items with resolved product types, resets retry count to 0, re-enqueues with `NeedsPost` phase. | **NO equivalent.** Dead-lettered items remain in queue until 30-day retention purges them. |
| **Risk** | None (just added) | Receipts that hit transient server errors 3 times are permanently lost after 30 days. Users who purchased during a backend outage may never get their entitlements. |
| **Severity** | **P1** |
| **Fix** | Port `retryDeadLetteredItems()` to iOS `PaymentProcessor`. Call after sweep/sync in bootstrap sequence. |

### GAP-2: Receipt Queue Persist Failure Handling
| | Android | iOS |
|--|---------|-----|
| **Behavior** | `upsert()`, `update()`, `remove()`, `consumeDeadLettered()` all check `persist()` return value. On failure: update in-memory state + warn log. | `writeToDisk()` returns `Void`. Failures are logged but in-memory state is NOT explicitly preserved on disk write failure. |
| **Risk** | None (just fixed) | If disk write fails, the receipt may be lost from both memory and disk on next `loadState()` call. |
| **Severity** | **P2** |
| **Fix** | Make `writeToDisk()` return `Bool`. Check return in `upsert()`, `update()`, `remove()`. Preserve in-memory state on failure. |

### GAP-3: Batch Sync/Restore Partial Failure Recovery
| | Android | iOS |
|--|---------|-----|
| **Behavior** | `enqueueFailedBatchPurchases()` individually enqueues purchases that failed in a batch sync/restore, ensuring they enter the receipt pipeline and get finalized at Google. | **N/A** — iOS uses StoreKit 2 per-transaction model. No batch sync/restore exists. Each transaction posts independently. |
| **Risk** | None | **Not a gap** — this is a legitimate platform difference. StoreKit 2 handles transactions individually. Google Play Billing requires batch sync because `queryPurchases()` returns all active purchases at once. |
| **Severity** | Platform difference, no action needed |

### GAP-4: Pipeline Retry Backoff Formula
| | Android | iOS |
|--|---------|-----|
| **Behavior** | Exponential: `2^retryCount * 1000ms`, capped at 60 min. Respects `Retry-After`. | Fixed schedule: `0ms, 750ms, 3s`. Respects `Retry-After` (capped at 30s per delay). |
| **Risk** | Different retry timing could cause divergent behavior under load. Android retries more aggressively early but backs off harder. iOS retries more gently but has a low ceiling. |
| **Severity** | **P3** — behavioral divergence but both work correctly |
| **Fix** | Align to same formula (recommend Android's exponential backoff for both). |

### GAP-5: Backend Client Retry Backoff Formula
| | Android | iOS |
|--|---------|-----|
| **Behavior** | `2^(attempt-1) + jitter`, capped at 120s per retry. Max `Retry-After` cap: 3600s. | `2^(attempt-1) + jitter`, capped at 120s per retry. Max `Retry-After` cap: 3600s. |
| **Risk** | None — backend client retry formulas are **identical**. |
| **Severity** | No gap |

### GAP-6: Error Model Metadata Richness
| | Android | iOS |
|--|---------|-----|
| **Behavior** | `AppActorError` sealed class. Per-subtype properties. No `scope` or `retryAfterSeconds` fields. | `AppActorError` struct with `scope: String?` and `retryAfterSeconds: Double?` fields. |
| **Risk** | Android consumers cannot inspect rate-limit scope or server-suggested retry delay from error objects. |
| **Severity** | **P3** |
| **Fix** | Add `scope` and `retryAfterSeconds` to Android `AppActorError.Server`. |

### GAP-7: Plugin Request Count
| | Android | iOS |
|--|---------|-----|
| **Requests** | 22 built-in requests | 29 built-in requests |
| **iOS-only requests** | — | `PresentOfferCodeRequest`, `PurchaseFromIntentRequest` (iOS 16.4+), `GetASADiagnosticsRequest`, `GetASAFirstInstallOnDeviceRequest`, `GetASAFirstInstallOnAccountRequest`, `GetPendingASAPurchaseEventCountRequest`, `ActiveEntitlementsOfflineRequest` |
| **Risk** | Feature gap for hybrid wrappers using the plugin layer. |
| **Severity** | **P3** — most are platform-specific (ASA, offer codes). `ActiveEntitlementsOfflineRequest` should be added to Android. |

### GAP-8: Identity Transition Buffer Architecture
| | Android | iOS |
|--|---------|-----|
| **Behavior** | Explicit buffer with 50-item limit. Overflow purchases fall through to normal pipeline with current identity + warning log. | Actor-based concurrency. `beginIdentityTransition()` / `endIdentityTransition()` on TransactionWatcher actor. No explicit buffer limit — actor serialization prevents concurrent access. |
| **Risk** | Architectural difference, not a bug. Android's buffer exists because coroutines + mutex don't provide the same serialization guarantees as Swift actors. |
| **Severity** | Platform difference, no action needed |

---

## MINOR DIFFERENCES

### DIFF-1: Offerings Cache TTL
| | Android | iOS |
|--|---------|-----|
| **Foreground** | Implicit (manager-controlled, no explicit constant) | Explicit: 5 minutes |
| **Background** | Implicit | Explicit: 24 hours |
| **Note** | Android should expose explicit TTL constants for clarity. |

### DIFF-2: Customer Cache Staleness Check
| | Android | iOS |
|--|---------|-----|
| **Foreground refresh** | No periodic staleness timer | 5-minute staleness timer during foreground sessions |
| **Note** | iOS proactively refreshes stale customer info. Android relies on explicit calls or purchase events. |

### DIFF-3: Retry-After Header Parsing
| | Android | iOS |
|--|---------|-----|
| **Format** | Parses seconds only (Double) | Parses seconds AND RFC 7231 HTTP-date format |
| **Note** | Minor — servers typically send seconds. |

### DIFF-4: Posted Ledger Architecture
| | Android | iOS |
|--|---------|-----|
| **Implementation** | Separate `AppActorPostedLedgerStore` class | Integrated into `AppActorAtomicJSONQueueStore` |
| **Retention** | Implicit | 90 days, max 5,000 entries |
| **Note** | Same functionality, different code organization. |

### DIFF-5: `upsertAll` Batch Insert
| | Android | iOS |
|--|---------|-----|
| **Exists** | Yes — single disk write for N items | No — only single-item `upsert()` |
| **Impact** | Android startup dead-letter retry is O(1) disk writes. iOS equivalent would be O(N). |
| **Note** | Add `upsertAll` to iOS when porting dead-letter retry. |

---

## FEATURE PARITY MATRIX

| Feature | Android | iOS | Status |
|---------|---------|-----|--------|
| **Core Pipeline** | | | |
| Durable receipt queue | Yes | Yes | Parity |
| 3-layer dedup (disk/runtime/server) | Yes | Yes | Parity |
| Dead-letter after 3 retries | Yes | Yes | Parity |
| Dead-letter 30-day retention | Yes | Yes | Parity |
| Dead-letter startup recovery | Yes | **No** | **GAP-1** |
| Persist failure in-memory fallback | Yes | **No** | **GAP-2** |
| Batch upsertAll | Yes | No | Minor |
| **Identity** | | | |
| Anonymous ID generation | Yes | Yes | Parity |
| Login/logout flows | Yes | Yes | Parity |
| Identity transition buffering | Yes (50-item buffer) | Yes (actor-based) | Platform diff |
| App account token (StoreKit) | obfuscatedAccountId | appAccountToken UUID | Platform diff |
| **Backend** | | | |
| HTTP client retries (3x) | Yes | Yes | Parity |
| Exponential backoff + jitter | Yes | Yes | Parity |
| Ed25519 signature verification | Yes (v1 + v2) | Yes (v1 + v2) | Parity |
| ETag / 304 conditional requests | Yes | Yes | Parity |
| Rate-limit cooldown | Yes | Yes | Parity |
| Retry-After RFC 7231 parsing | No | Yes | Minor |
| **Caching** | | | |
| Offerings ETag cache | Yes | Yes | Parity |
| Customer ETag cache | Yes | Yes | Parity |
| Remote config cache | Yes | Yes | Parity |
| Experiment cache | Yes | Yes | Parity |
| Disk cache atomic writes | Yes | Yes | Parity |
| Foreground staleness timer | No | Yes (5 min) | Minor |
| **Managers** | | | |
| Customer manager | Yes | Yes | Parity |
| Offerings manager | Yes | Yes | Parity |
| Experiment manager | Yes | Yes | Parity |
| Remote config manager | Yes | Yes | Parity |
| Offline entitlements | Yes | Yes | Parity |
| In-flight request dedup | Yes | Yes | Parity |
| SWR (stale-while-revalidate) | Yes | Yes | Parity |
| **Error Model** | | | |
| Sealed error types | Yes (23 subtypes) | Yes (20 kinds) | ~Parity |
| Transient classification | Yes | Yes | Parity |
| Bridge error codes (16) | Yes | Yes | Parity |
| Error scope field | No | Yes | **GAP-6** |
| Error retryAfterSeconds field | No | Yes | **GAP-6** |
| **Plugin/Bridge** | | | |
| Hybrid wrapper bridge | Yes (AppActorBridge) | Yes (AppActorPlugin) | Parity |
| Dynamic request registration | Yes | Yes | Parity |
| Built-in request count | 22 | 29 | **GAP-7** |
| Event streaming | Listener callbacks | Delegate streaming | Platform diff |
| Listener save/restore | Yes | Yes | Parity |
| **Platform-Specific** | | | |
| Google Play Billing adapter | Yes | N/A | Platform |
| StoreKit 2 integration | N/A | Yes | Platform |
| Install referrer (Google) | Yes | N/A | Platform |
| Apple Search Ads (ASA) | N/A | Yes | Platform |
| PurchaseIntent (iOS 16.4+) | N/A | Yes | Platform |
| Win-back offers | N/A | Yes (iOS 18+) | Platform |

---

## RECOMMENDED ACTION ITEMS

### Priority 1 (Revenue Risk)
1. **[iOS] Port dead-letter startup recovery** — Add `retryDeadLetteredItems()` to iOS `PaymentProcessor`. Call after sweep/sync in bootstrap. Include `upsertAll` for efficient batch persistence. (GAP-1)

### Priority 2 (Data Integrity)
2. **[iOS] Add persist failure handling** — Make `writeToDisk()` return success/failure. Update `upsert()`, `update()`, `remove()` to preserve in-memory state on disk failure. (GAP-2)

### Priority 3 (Alignment)
3. **[iOS] Align pipeline retry backoff** — Change from fixed (0/750ms/3s) to exponential (2^n * 1000ms, capped 60m) to match Android. (GAP-4)
4. **[Android] Add error metadata fields** — Add `scope` and `retryAfterSeconds` to `AppActorError.Server`. (GAP-6)
5. **[Android] Add `ActiveEntitlementsOfflineRequest`** to plugin. (GAP-7)
6. **[Android] Add explicit offerings cache TTL constants** for foreground/background modes. (DIFF-1)
