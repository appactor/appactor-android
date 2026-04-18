# CDN-Compatible Response Signing - Android SDK Parity Analysis

**Tarih:** 2026-04-12
**Kaynak:** `final-cachable.md` (iOS SDK + Backend implementation plan)
**Hedef:** Android SDK'da eksik olan SDK phase'lerinin (1, 3, 4) tespiti ve implementasyon yol haritasi

---

## Ozet

`final-cachable.md` dosyasinda 7 phase tanimlanmis. Backend phase'leri (2, 5, 6) ve iOS SDK phase'leri (1, 3, 4) tamamlanmis. Phase 7 (test) backend tarafinda tamamlanmis.

**Android SDK'da hicbiri yapilmamis.** Asagida phase bazli detayli analiz var.

| Phase | Kapsam | iOS | Android | Oncelik |
|-------|--------|-----|---------|---------|
| Phase 1 | Endpoint-based nonce policy | DONE | **EKSIK** | KRITIK |
| Phase 2 | Backend salt-based signing | DONE (backend) | N/A | - |
| Phase 3 | Salt-based verification + VerificationResult | DONE | **EKSIK** | KRITIK |
| Phase 4 | ETag cache + verification reuse | DONE | **EKSIK** | KRITIK |
| Phase 5 | Backend CDN cache headers | DONE (backend) | N/A | - |
| Phase 6 | CDN infrastructure | DONE (backend) | N/A | - |
| Phase 7 | Comprehensive testing | DONE (backend) | **SDK TESTLERI EKSIK** | YUKSEK |

---

## Phase 1: Endpoint-Based Nonce Policy — EKSIK

### Mevcut Durum (Android)

Android SDK **tum endpoint'lere** nonce gonderiyor. Nonce uretimi `AppActorAuthHeaderProvider.apply()` icerisinde:

**Dosya:** `backend/auth/AppActorAuthHeaderProvider.kt:29-31`
```kotlin
val nonce = UUID.randomUUID().toString()
builder.header("X-AppActor-Nonce", nonce)
return nonce
```

Bu method 3 farkli yerde cagiriliyor:
1. `buildJsonRequest()` (HttpBackendClient:197) — POST request'ler (identify, login, logout, receipt, restore, sync)
2. `buildGetRequest()` (HttpBackendClient:214) — GET request'ler (offerings, customer, remote-config)
3. `postExperimentAssignment()` (HttpBackendClient:144) — dogrudan builder kullanimi

Ayrica retry logic'te (HttpBackendClient:383) `requestForAttempt()` her retry'da yeniden `apply()` cagiriyor → yeni nonce uretiyor.

### Hedef Durum

Offerings (`/v1/payment/offerings`) ve remote-config (`/v1/remote-config`) endpoint'leri nonce **GONDERMEMELI**. Diger tum endpoint'ler nonce gondermeye devam etmeli.

### Gerekli Degisiklikler

#### 1.1: Endpoint Signing Policy Tanimi (Yeni Dosya)

**Konum:** `backend/client/AppActorEndpointSigningPolicy.kt` (yeni)

Endpoint path'ine gore nonce policy belirleyen bir obje/enum:

| Endpoint | Path | Nonce |
|----------|------|-------|
| Offerings | `/v1/payment/offerings` | HAYIR |
| Remote Config | `/v1/remote-config` | HAYIR |
| Identify | `/v1/payment/identify` | EVET |
| Login | `/v1/payment/login` | EVET |
| Logout | `/v1/payment/logout` | EVET |
| Customer | `/v1/customers/{id}` | EVET |
| Receipt | `/v1/payment/receipts/google` | EVET |
| Restore | `/v1/payment/restore/google` | EVET |
| Sync | `/v1/payment/sync/google` | EVET |
| Experiments | `/v1/experiments/{key}/assignments` | EVET |

**Android-spesifik not:** iOS'ta receipt/restore path'leri `/apple` suffix'li. Android'de `/google` suffix'li. Ayrica Android'de `/sync/google` endpoint'i var — iOS'ta yok. Tumu POST+mutation, nonce-required.

#### 1.2: AuthHeaderProvider Guncelleme

**Dosya:** `backend/auth/AppActorAuthHeaderProvider.kt`

`apply()` method'u `path: String` parametresi almali ve `String?` donmeli (nonce gonderilmediyse `null`):

```kotlin
// Mevcut:
fun apply(builder: Request.Builder, configuration: AppActorConfiguration): String

// Hedef:
fun apply(builder: Request.Builder, configuration: AppActorConfiguration, path: String): String?
```

**Mimari sorun:** `buildJsonRequest()` ve `buildGetRequest()` su anda `url: String` (tam URL) aliyor, path prefix'i ayri olarak gecirilmiyor. Policy path-prefix match yapacagi icin ya:
- (a) `apply()`'a URL'den path cikarilarak verilmeli, ya da
- (b) Her endpoint method'u path'i ayri olarak gecirmeli

Tercih (b) — iOS ile uyumlu ve daha temiz. Her endpoint method'unda path string'i zaten biliniyor.

#### 1.3: Retry Logic Guncelleme

**Dosya:** `backend/client/AppActorHttpBackendClient.kt:375-385`

`requestForAttempt()` retry'larda `AppActorAuthHeaderProvider.apply()` cagiriyor → nonce uretiyor. Bu, nonce-free endpoint'ler icin nonce **URETMEMELI**:

```kotlin
// Mevcut:
private fun requestForAttempt(initialRequest: Request, attempt: Int): Request {
    if (attempt == 0) return initialRequest
    val builder = initialRequest.newBuilder().removeHeader("If-None-Match")
    AppActorAuthHeaderProvider.apply(builder, configuration)  // ← her zaman nonce ekliyor
    return builder.build()
}

// Hedef: path parametresi eklenmeli, policy kontrol edilmeli
```

#### 1.4: Etkilenen Tum Cagri Noktalari

| Method | Satir | Path | Nonce |
|--------|-------|------|-------|
| `identify()` | ~48 | `/v1/payment/identify` | EVET |
| `login()` | ~57 | `/v1/payment/login` | EVET |
| `logout()` | ~65 | `/v1/payment/logout` | EVET |
| `getOfferings()` | ~74 | `/v1/payment/offerings` | **HAYIR** |
| `getCustomer()` | ~82 | `/v1/customers/{id}` | EVET |
| `getRemoteConfigs()` | ~100 | `/v1/remote-config` | **HAYIR** |
| `postExperimentAssignment()` | ~122 | `/v1/experiments/{key}/assignments` | EVET |
| `postGoogleReceipt()` | ~149 | `/v1/payment/receipts/google` | EVET |
| `postGoogleRestore()` | ~160 | `/v1/payment/restore/google` | EVET |
| `postGoogleSync()` | ~171 | `/v1/payment/sync/google` | EVET |

### Phase 1 Dogrulama Kriterleri

- [ ] Offerings request'inde `X-AppActor-Nonce` header'i GONDERILMIYOR
- [ ] Remote-config request'inde `X-AppActor-Nonce` header'i GONDERILMIYOR
- [ ] Diger tum endpoint'lerde nonce hala GONDERILIYOR
- [ ] Retry'da nonce-free endpoint'lere nonce URETILMIYOR
- [ ] Mevcut testler geciyor

---

## Phase 3: Salt-Based Verification + VerificationResult — EKSIK

### Mevcut Durum (Android)

1. **Signature headers:** `AppActorResponseSignatureHeaders` (HttpModels.kt:14-34) sadece `requestNonce`, `signature`, `signatureTimestamp` parse ediyor. **`X-AppActor-Signature-Salt` header'i parse EDILMIYOR.**

2. **Verify guard:** `verifyResponseSignature()` (HttpBackendClient.kt:467) su guard'a sahip:
   ```kotlin
   if (statusCode !in 200..299 || !configuration.options.verifyResponseSignatures || sentNonce.isBlank()) {
       return false
   }
   ```
   `sentNonce.isBlank()` kontrolu, nonce-free endpoint'ler icin verification'i tamamen ATLIYOR. Salt-based verification bu yuzden calismaz.

3. **Verifier:** `AppActorResponseSignatureVerifier.verify()` (SignatureVerifier.kt:32-45) `sentNonce: String` (non-optional) aliyor. Salt-based payload formati desteklenmiyor. Nonce-based payload: `"$sentNonce\n$timestamp\n$body"`. Salt-based olmasi gereken: `"$salt\n$apiKey\n$path\n$timestamp\n$eTag\n$body"`.

4. **V1/V2 routing:** `verifyV1()` ve `verifyV2()` her ikisi de `payloadBytes(sentNonce, timestamp, body)` cagiriyor. Salt-based path icin farkli bir payload uretimi gerekiyor.

### Gerekli Degisiklikler

#### 3.1: Signature Headers Guncelleme

**Dosya:** `backend/client/AppActorBackendHttpModels.kt:14-34`

`AppActorResponseSignatureHeaders`'a `signatureSalt` field'i eklenmeli:

```kotlin
// Eklenmesi gereken:
val signatureSalt: String? = null,  // X-AppActor-Signature-Salt header

// fromHeaders()'da:
val signatureSalt = headers["X-AppActor-Signature-Salt"]
```

#### 3.2: Verifier Salt-Based Path

**Dosya:** `backend/client/AppActorResponseSignatureVerifier.kt`

`verify()` method'u asagidaki parametreleri almali:

```kotlin
// Mevcut:
fun verify(headers, body, sentNonce: String): VerificationResult

// Hedef:
fun verify(headers, body, sentNonce: String?, apiKey: String, requestPath: String): VerificationResult
```

**Yeni routing logic:**
- `sentNonce != null` + `headers.requestNonce` var → nonce-based verify (mevcut, degismez)
- `sentNonce == null` + `headers.signatureSalt` var → salt-based verify (YENi)
- Hicbiri → `SigningNotSupported`

**Salt-based payload formati:**
```
{saltBase64}\n{apiKey}\n{requestPath}\n{timestamp}\n{eTag}\n{body}
```

`payloadBytes()` helper'i genellestirilmeli veya yeni bir `saltPayloadBytes()` eklenmeli. `verifyV1()` ve `verifyV2()` payload byte'lari parametre olarak alacak sekilde refactor edilmeli.

#### 3.3: HTTP Client verifyResponseSignature Guncelleme

**Dosya:** `backend/client/AppActorHttpBackendClient.kt:460-493`

```kotlin
// Mevcut guard (satir 467):
if (statusCode !in 200..299 || !configuration.options.verifyResponseSignatures || sentNonce.isBlank()) {
    return false
}

// Hedef: sentNonce.isBlank() guard'i KALDIRILMALI
// sentNonce artik String? tipinde, null nonce-free endpoint icin
if (statusCode !in 200..299 || !configuration.options.verifyResponseSignatures) {
    return false
}
```

`verifyResponseSignature()`'a `apiKey` ve `requestPath` parametreleri eklenmeli. Bunlar `configuration.apiKey` ve request URL'inden cikarilabilir.

Ayrica `SigningNotSupported` case'i iOS ile ayni sekilde ele alinmali:
```kotlin
// Sadece nonce-required endpoint'lerde enforce et
AppActorResponseSignatureVerifier.VerificationResult.SigningNotSupported -> {
    if (configuration.options.requireResponseSignatures && sentNonce != null) {
        throw AppActorBackendException.Signature(...)
    }
    false // nonce-free endpoint'ler icin transitional donemde kabul et
}
```

#### 3.4: executeRaw Guncelleme

**Dosya:** `backend/client/AppActorHttpBackendClient.kt:419-458`

`executeRaw()` icinde `sentNonce` su anda `request.header("X-AppActor-Nonce").orEmpty()` ile alinip `String` olarak `verifyResponseSignature`'a geciriliyor (satir 429-435). Bunun `String?` tipine cekilmesi ve `apiKey` + `path`'in de gecilmesi gerekiyor.

#### 3.5: Public VerificationResult Enum (Opsiyonel, Phase 4 ile birlikte)

**Konum:** `models/` altinda public bir `AppActorVerificationResult` enum tanimlanabilir. iOS'ta `VerificationResult.swift` olarak ayri dosya.

Sinirlari:
```kotlin
enum class AppActorVerificationResult(val value: Int) {
    NotRequested(0),  // Verification yapilmadi
    Verified(1),      // Imza dogrulandi
    Failed(2),        // Imza dogrulanamadi — olasi tahrifat
}
```

### Phase 3 Dogrulama Kriterleri

- [ ] Nonce-free endpoint + salt-based response → salt-based verify → `Success`
- [ ] Nonce-free endpoint + tampered body → `SignatureInvalid`
- [ ] Nonce-free endpoint + tampered salt → `SignatureInvalid`
- [ ] Nonce-free endpoint + wrong apiKey → `SignatureInvalid`
- [ ] Nonce-free endpoint + wrong path → `SignatureInvalid`
- [ ] Nonce-free endpoint + timestamp drift > 300s → `TimestampOutOfRange`
- [ ] Nonce-required endpoint + nonce-based response → mevcut verify (regression yok)
- [ ] `.SigningNotSupported` nonce-free endpoint'lerde throw ETMIYOR
- [ ] V2 intermediate chain salt-based payload ile de calisiyor

---

## Phase 4: ETag Cache + Verification Reuse — EKSIK

### Mevcut Durum (Android)

`AppActorCacheEntry` (CacheModels.kt:6-11) sadece `responseVerified: Boolean` field'ina sahip:

```kotlin
@Serializable
internal data class AppActorCacheEntry(
    val payload: String,
    val eTag: String? = null,
    val cachedAtMillis: Long,
    val responseVerified: Boolean,
)
```

ETag manager (ETagManager.kt:14) binary `!entry.responseVerified` kontrolu yapiyor:

```kotlin
if (responseVerificationEnabled && !entry.responseVerified) {
    return null
}
```

`clearAllUnverified()` (CacheDiskStore.kt:89-100) ayni binary kontrolu kullaniyor.

### Sorun

Salt-based verification transition doneminde, server henuz salt signing desteklemediginde `verified = false` geliyor. Mevcut binary logic bunu **failed** olarak degerlendiriyor ve:
- ETag gondermiyor (fresh fetch zorluyor — gereksiz bandwidth)
- Cache entry'yi temizliyor (`clearAllUnverified`)
- `handleNotModified()` cached veriyi reddediyor

Bu iOS'taki `.notRequested` vs `.failed` ayrimi ile cozuluyor.

### Gerekli Degisiklikler

#### 4.1: Cache Entry Model Guncelleme

**Dosya:** `cache/AppActorCacheModels.kt`

```kotlin
@Serializable
internal data class AppActorCacheEntry(
    val payload: String,
    val eTag: String? = null,
    val cachedAtMillis: Long,
    val responseVerified: Boolean,                           // backward compat
    val verificationResult: AppActorVerificationResult? = null,  // yeni, migration icin optional
) {
    val resolvedVerification: AppActorVerificationResult
        get() = verificationResult
            ?: if (responseVerified) AppActorVerificationResult.Verified
               else AppActorVerificationResult.Failed
}
```

**Backward compat notu:** `kotlinx.serialization` ile `explicitNulls = false` (AppActorBackendJson.instance'da zaten set) sayesinde eski cache dosyalari `verificationResult = null` olarak decode edilir. `resolvedVerification` computed property legacy bool'u kullanir.

#### 4.2: ETag Manager Uc-Yonlu Logic

**Dosya:** `cache/AppActorETagManager.kt:8-18`

```kotlin
// Mevcut (binary):
if (responseVerificationEnabled && !entry.responseVerified) {
    return null
}

// Hedef (uc-yonlu):
if (responseVerificationEnabled) {
    when (entry.resolvedVerification) {
        AppActorVerificationResult.Verified -> { /* ETag gonder */ }
        AppActorVerificationResult.NotRequested -> { /* ETag gonder — transitional */ }
        AppActorVerificationResult.Failed -> return null  // Force fresh fetch
    }
}
```

Bu degisiklik asagidaki method'lara uygulanmali:
- `eTag()` (satir 8)
- `handleNotModified()` (satir 37)
- `cached()` (satir 52)
- `isFresh()` (satir 64)

#### 4.3: storeFresh() Verification Mapping

**Dosya:** `cache/AppActorETagManager.kt:20-35`

```kotlin
// Mevcut:
diskStore.save(
    entry = AppActorCacheEntry(
        payload = payload,
        eTag = eTag,
        cachedAtMillis = System.currentTimeMillis(),
        responseVerified = verified,  // Boolean
    ),
    resource = resource,
)

// Hedef: verificationResult da set edilmeli
// verified == true  → .Verified
// verified == false → .NotRequested (server imzalamadi — transitional, FAILURE DEGIL)
// verified parametresi default'ta responseVerificationEnabled
```

**KRITIK:** `verified == false` → `NotRequested` (`.Failed` DEGIL). Transition doneminde server henuz salt signing desteklemediginde cache kirilmasin diye. `.Failed` sadece legacy fallback'te uretilir.

#### 4.4: clearAllUnverified() Guncelleme

**Dosya:** `cache/AppActorCacheDiskStore.kt:89-100`

```kotlin
// Mevcut:
if (entry == null || !entry.responseVerified) {
    file.delete()
}

// Hedef: sadece .Failed olanlari temizle, .NotRequested'i KORUMA
if (entry == null || entry.resolvedVerification == AppActorVerificationResult.Failed) {
    file.delete()
}
```

### Phase 4 Dogrulama Kriterleri

- [ ] Salt-based verified response cache'e `.Verified` ile kaydediliyor
- [ ] ETag gonderme: `.Verified` → gonder, `.NotRequested` → gonder, `.Failed` → GONDERME
- [ ] 304 response: cached `.Verified` entry reuse ediliyor
- [ ] 304 response: cached `.Failed` entry reuse EDILMIYOR
- [ ] Eski cache dosyalari backward compat ile decode ediliyor
- [ ] `clearAllUnverified()` sadece `.Failed` entry'leri temizliyor
- [ ] Mixed: offerings salt-based cached + customer nonce-signed → her ikisi dogru calisiyor

---

## Etki Analizi: Dosya Bazli Degisiklik Ozeti

| Dosya | Degisiklik | Phase |
|-------|-----------|-------|
| `backend/client/AppActorEndpointSigningPolicy.kt` | **YENI DOSYA** — endpoint nonce policy | Phase 1 |
| `backend/auth/AppActorAuthHeaderProvider.kt` | `apply()` → optional nonce, path param, `String?` donus tipi | Phase 1 |
| `backend/client/AppActorHttpBackendClient.kt` | Path threading, retry nonce policy, salt-based verify wiring | Phase 1 + 3 |
| `backend/client/AppActorBackendHttpModels.kt` | `signatureSalt` field ekleme | Phase 3 |
| `backend/client/AppActorResponseSignatureVerifier.kt` | Salt-based verification path, optional sentNonce | Phase 3 |
| `models/AppActorVerificationResult.kt` | **YENI DOSYA** — public VerificationResult enum (opsiyonel) | Phase 3/4 |
| `cache/AppActorCacheModels.kt` | `verificationResult` field + `resolvedVerification` property | Phase 4 |
| `cache/AppActorETagManager.kt` | Uc-yonlu verification logic, storeFresh mapping | Phase 4 |
| `cache/AppActorCacheDiskStore.kt` | `clearAllUnverified()` → `.Failed`-only temizlik | Phase 4 |

**Toplam:** 2 yeni dosya, 7 mevcut dosya degisikligi.

---

## Android-Spesifik Mimari Notlar

### 1. Path Extraction Problemi

iOS'ta `applyAuth(to:path:)` path'i dogrudan parametre olarak aliyor. Android'de `buildJsonRequest()` ve `buildGetRequest()` full URL aliyor, path ayri gecirilmiyor. Iki yaklasim:

- **(a)** URL'den path extract etmek (URL parse + path component) — fragile
- **(b)** Her endpoint method'unda path string'ini ayri gecirmek — iOS ile paralel, daha temiz

**Oneri:** (b) tercih edilmeli. Her endpoint method'u `"/v1/payment/offerings"` gibi path constant'i zaten biliyor.

### 2. Android-Spesifik Endpoint'ler

iOS'ta olmayan endpoint'ler:
- `/v1/payment/receipts/google` (iOS: `/apple`)
- `/v1/payment/restore/google` (iOS: `/apple`)
- `/v1/payment/sync/google` (iOS: yok)

Tumu POST+mutation → nonce-required. Fonksiyonel fark yok.

### 3. Threading / Concurrency Farki

iOS `ResponseSignatureVerifier` static method'lar kullaniyor. Android'de de `object` (singleton) static method'lar — ayni pattern. Concurrency sorunu yok.

### 4. BouncyCastle vs CryptoKit

iOS Ed25519 icin `CryptoKit.Curve25519.Signing.PublicKey` kullaniyor. Android BouncyCastle (`Ed25519Signer`) kullaniyor. Salt-based payload icin sadece payload string degisiyor — crypto layer'da degisiklik yok.

### 5. kotlinx.serialization Cache Migration

`AppActorCacheEntry` `@Serializable`. Yeni optional field (`verificationResult`) `kotlinx.serialization` ile sorunsuz deserialize olur cunku:
- `explicitNulls = false` zaten set
- `ignoreUnknownKeys = true` zaten set
- Default value (`null`) verilmis

Eski cache dosyalari `verificationResult` field'i icermeyecek → `null` olarak decode → `resolvedVerification` legacy `responseVerified` bool'u kullanir.

---

## Implementasyon Sirasi (Onerilen)

```
Phase 1 (nonce policy)
  ├── 1.1: AppActorEndpointSigningPolicy.kt (yeni dosya)
  ├── 1.2: AppActorAuthHeaderProvider.apply() guncelleme
  ├── 1.3: AppActorHttpBackendClient — path threading + retry
  └── 1.4: Unit testler
       │
       ▼
Phase 3 (salt-based verify)
  ├── 3.1: AppActorResponseSignatureHeaders — salt field
  ├── 3.2: AppActorResponseSignatureVerifier — salt-based path
  ├── 3.3: AppActorHttpBackendClient — verify guard + wiring
  ├── 3.4: AppActorVerificationResult enum (opsiyonel)
  └── 3.5: Unit testler
       │
       ▼
Phase 4 (cache verification reuse)
  ├── 4.1: AppActorCacheEntry — verificationResult field
  ├── 4.2: AppActorETagManager — uc-yonlu logic
  ├── 4.3: AppActorCacheDiskStore — clearAllUnverified
  └── 4.4: Unit testler
```

**Tahmini effort:** 2-3 gun (Phase 1: 0.5g, Phase 3: 1-1.5g, Phase 4: 0.5g)

---

## Sonuc

Android SDK'da CDN-compatible response signing icin **3 phase** (1, 3, 4) implementasyonu gerekiyor. Backend tarafinda degisiklik gereksiz — backend zaten her iki signing mode'u destekliyor. Android implementasyonu tamamlanip release edildikten sonra, eski SDK'lar sunset olunca CDN (`CDN_ENABLED=true`) guvenle aktive edilebilir.
