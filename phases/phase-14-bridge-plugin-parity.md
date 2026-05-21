# Phase 14 - Bridge and Plugin Parity Contract

## Goal
Freeze the wrapper-facing contract so Android and iOS can be consumed as a single product at the bridge/plugin layer.

## Canonical Contract
- `configure` payload:
  - `api_key`
  - `options.log_level`
  - `options.platform_info.flavor`
  - `options.platform_info.version`
- `get_offerings` payload:
  - `fetch_policy`
  - values: `freshIfStale`, `returnCachedThenRefresh`, `cacheOnly`
- Receipt pipeline event types:
  - `POSTED_OK`
  - `DEFERRED_WAITING_FOR_IDENTITY`
  - `RETRY_SCHEDULED`
  - `PERMANENTLY_REJECTED`
  - `DEAD_LETTERED`
  - `DUPLICATE_SKIPPED`
- Verification values:
  - `notRequested`
  - `verified`
  - `verifiedOnDevice`
  - `failed`
- Bridge error diagnostics:
  - `backendCode`
  - `requestId`
  - `scope`
  - `retryAfterSeconds`

## Guardrails
- Wrapper-facing additions are spec-first and bridge-first.
- Keep legacy aliases for one compatibility window, then remove them intentionally.
- Any release that changes bridge/plugin payloads must update tests, `api.txt`, and this file together.

## Release Checklist
- Android plugin tests cover canonical `options.platform_info`.
- Android plugin tests cover canonical `fetch_policy`.
- Android bridge tests cover `DEFERRED_WAITING_FOR_IDENTITY`.
- Android bridge error tests cover structured diagnostics.
