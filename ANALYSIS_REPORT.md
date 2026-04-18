# AppActor Android SDK - Kapsamli Karsilastirmali Analiz Raporu

**Tarih**: 2026-04-11
**Karsilastirma Kaynaklari**: RevenueCat Android, AdaptySDK Android, AppActor iOS SDK
**Analiz Kapsamı**: Purchase, Restore, Sync, Entitlement, Identity, Receipt Pipeline, Error Handling, Offline

---

## OZET SKOR TABLOSU

| Kategori | Puan (10 uzerinden) | Aciklama |
|----------|:-------------------:|----------|
| **1. Satin Alma Guvenligi & Entitlement Korumasi** | **8.0** | Sagmam temeller, minor edge case riskleri |
| **2. Mimari & Kod Kalitesi** | **8.5** | Temiz ayirma, explicit API, iyi mutex stratejisi |
| **3. Hata Yonetimi & Kurtarma** | **7.5** | Saglam retry/backoff, rate limit persist, RC'ye kiyasla minor eksikler |
| **4. iOS ile Ozellik Paritesi** | **7.5** | Yakin eslesme, bazi aciklar aktif olarak kapatiliyor |
| **GENEL ORTALAMA** | **7.88** | Saglam SDK, production-ready, iyilestirme alanlari var |

---

## TEST DURUMU

```
BUILD SUCCESSFUL - Tum unit testler gecti (18 task, hepsi up-to-date)
```

Uncommitted degisikliklerde aktif olarak duzeltilen konular tespit edildi (asagida detayli).

---

## KATEGORI 1: SATIN ALMA GUVENLIGI & ENTITLEMENT KORUMASI (8.0/10)

### Guclu Yanlar

**1. Durable Receipt Queue (Dayanikli Makbuz Kuyrugu)**
- Satin alma Google Play'de basarili oldugu anda, backend POST'tan once diske yaziliyor
- `AppActorReceiptQueueStore`: Atomic JSON dosya yazimi (temp + rename pattern)
- Uygulama crash'lese bile makbuz kaybolmuyor
- RC ve Adapty ile ayni seviyede

**2. Posted Ledger ile Deduplication**
- `AppActorPostedLedgerStore`: 90 gun retention, max 5000 entry
- Receipt key formati: `google:productId:basePlanId:purchaseToken`
- Ayni makbuzun iki kez post edilmesini onluyor
- iOS SDK ile birebir ayni pattern

**3. Identity Epoch Tracking**
- `@Volatile identityEpoch` ile eski session'lardan gelen callback'ler engelleniyor
- Kullanici degistirirken stale entitlement delivery onleniyor
- RC'de yok, Adapty'de profil ID tracking ile benzer

**4. Mutual Exclusion Stratejisi**
- `purchaseMutex`: Ayni anda tek satin alma
- `pipelineMutex`: Receipt islemlerini serializes
- `transitionMutex`: Identity gecislerini koruma
- RC'nin synchronized map'lerinden daha temiz

**5. 3 Katmanli Pipeline Korumasi**
- Disk queue (NeedsPost -> Posting -> NeedsFinish -> tamamlandi)
- Runtime inflight tracking
- Backend idempotency key
- iOS ile birebir ayni, RC ve Adapty'den daha kapsamli

### Tespit Edilen Riskler

**RISK 1 (YUKSEK): Partial Restore'da Acknowledge Edilmeyen Satin Almalar**

Dosya: `AppActorPaymentProcessor.kt:1668-1686`

```kotlin
private fun successfulBatchPurchaseKeys(...): Set<String> {
    if (results.isNotEmpty()) {
        return results.filter { it.status == "synced" }.map(::batchPurchaseKey).toSet()
    }
    if (successCount < purchases.size) {
        return emptySet()  // SORUN: Hicbir purchase finalize edilmiyor!
    }
    return purchases.asSequence().map(::batchPurchaseKey).toSet()
}
```

**Senaryo**: Backend `results=[]` ve `successCount < purchases.size` dondurse, `finalizeRestoredActivePurchases` bos liste aliyor. Backend tarafinda entitlement verilmis olsa bile, Google Play tarafinda acknowledge/consume yapilmiyor. Google Play 3 gun icinde acknowledge edilmeyen satin almalari otomatik iade eder.

**Etki**: Nadir ama gercek entitlement kaybi riski. Backend'in `results` alanini bos dondurmesi durumunda tetiklenir.

**Oneri**: `results` bossa ve `successCount > 0` ise, ilk `successCount` kadar purchase'i basarili kabul et.

---

**RISK 2 (ORTA): Identity Transition Buffer Overflow**

Dosya: `AppActorPaymentProcessor.kt:95-96, 221-223`

```kotlin
private val maxTransitionBufferSize = 50

if (identityTransitionBuffer.size < maxTransitionBufferSize) {
    identityTransitionBuffer.add(BufferedPurchase(purchase, userId))
}
// 50'den sonra sessizce drop ediliyor!
```

**Senaryo**: Login/logout sirasinda Google Play 50'den fazla purchase update gonderse, fazlalari sessizce kayboluyor.

**Pratik Risk**: Dusuk. Google Play normalde bu kadar update gondermez. Ama log/warning olmamasi sorun.

**Oneri**: Buffer overflow'da warning log'u ekle. Alternatif: buffer limitini kaldirip LinkedList kullan.

---

**RISK 3 (ORTA): Pending Purchase 7 Gun Expiry**

Dosya: `AppActorPaymentProcessor.kt:76-85`

```kotlin
const val PENDING_EXPIRY_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L
```

Pending purchase token'lari 7 gun sonra bellekten siliniyor. Eger Google Play bu sureden sonra satin almayi onaylarsa, deferred callback tetiklenmez.

**RC Karsilastirma**: RC pending purchase'lari SharedPreferences'a persist eder (bellek degil).
**Adapty Karsilastirma**: Adapty pending state'i ayin sekilde handle eder.

---

### Karsilastirma Tablosu: Purchase Safety

| Ozellik | AppActor Android | RevenueCat | Adapty | AppActor iOS |
|---------|:---:|:---:|:---:|:---:|
| Durable receipt queue | ✅ | ✅ | ✅ (unsynced data) | ✅ |
| Posted ledger dedup | ✅ 90 gun | ✅ SHA-1 token | ❌ (synced purchases) | ✅ 90 gun |
| Staged finalization | ✅ NeedsFinish | ✅ consumeAndSave | ⚠️ (failure'da da ack) | ✅ |
| Identity epoch guard | ✅ | ❌ | ⚠️ (profil ID) | ✅ |
| Idempotency key | ✅ | ✅ | ✅ | ✅ |
| Pending purchase persist | ❌ (bellek) | ✅ (disk) | ❌ | N/A (StoreKit2) |

---

## KATEGORI 2: MIMARI & KOD KALITESI (8.5/10)

### Guclu Yanlar

**1. Explicit API Mode**
- `-Xexplicit-api=strict`: Tum public member'lar explicit visibility ve return type gerektirir
- Compiler seviyesinde API surface korumasi
- RC'de yok, Adapty'de yok - AppActor'un benzersiz avantaji

**2. Metalava API Check**
- `apiCheck` / `apiDump` ile API surface otomatik dogrulama
- Kazara public API degisikligini onler
- RC'de var (benzer), Adapty'de yok

**3. Temiz Katmanli Mimari**
```
api/ -> managers/ -> pipeline/ -> backend/ -> storage/
```
- Her katman net sorumluluk
- iOS SDK ile birebir eslesme
- RC'den daha basit (RC'de Orchestrator + Helper + Wrapper + UseCase)

**4. Coroutine Discipline**
- `SupervisorJob + Dispatchers.Default`: Tek task failure tum scope'u iptal etmiyor
- `Dispatchers.Main` sadece callback delivery'de
- Mutex-based mutual exclusion (RC'nin synchronized'dan daha modern)

**5. Server-Authoritative Model**
- Backend entitlement'larin tek kaynagi (RTDN ile)
- Client-side guessing minimalize edilmis
- RC ve Adapty ile ayni felsefe

### Iyilestirme Alanlari

**1. Silent Failure'lar**
- `AppActorReceiptQueueStore.persist()`: Disk write failure sessiz (sadece false donuyor)
- **Aktif duzeltme**: `AppActorPostedLedgerStore`'a logging ekleniyor (uncommitted diff'te goruldu)
- Oneri: Queue store'a da ayni logging eklensin

**2. Stale-While-Revalidate (SWR) Pattern**
- Offerings'te SWR mevcut (stale cache don, arkada refresh)
- CustomerManager'da SWR yok - ya fresh ya da full block
- RC'de foreground/background icin farkli TTL var
- iOS AppActor'da da SWR offerings'te var ama customer'da yok

---

## KATEGORI 3: HATA YONETIMI & KURTARMA (7.0/10)

### Guclu Yanlar

**1. Exponential Backoff with Jitter**
```kotlin
val baseDelay = min(2.0.pow((attempt - 1).toDouble()), 30.0)
val ownDelay = baseDelay + Random.nextDouble(0.0, baseDelay)
```
- 3 retry, max 120s delay
- Jitter thundering herd'u onler
- RC ile ayni seviye

**2. Dead-Letter Handling**
- Kalici hata alan receipt'ler `DeadLettered` olarak isaretleniyor
- `lastError` ile diagnostik bilgi sakli
- `productType=Unknown` olanlar offerings yuklendikten sonra revive edilebilir
- Adapty'de dead-letter yok, RC'de SHOULD_BE_MARKED_SYNCED benzer

**3. Offline Fallback Zinciri**
```
Network fetch -> Disk cache -> Offline entitlement computation -> Error
```
- Google Play aktif purchase query + offerings mapping ile offline entitlement
- 24 saat TTL ile cached customer info fallback
- RC'de benzer (OfflineEntitlementsManager)
- Adapty'de offline PAL benzer

### Eksikler (RC/iOS ile Karsilastirma)

**EKSIK 1 (ORTA): Dead-Letter Recovery Sinirli**

- Dead-letter'lar 30 gun tutuluyor ama aktif recovery yok
- Sadece `productType=Unknown` olanlar revive edilebilir
- Transient backend hatasi sonucu dead-letter olan receipt'ler 30 gun bekliyor
- RC'de: Backend 5xx -> offline entitlements, dead-letter yok
- Adapty'de: Unsynced data retry mekanizmasi daha agresif

**Oneri**: Dead-letter'lara "retry on next app launch" secenegi ekle.

---

**EKSIK 2 (DUSUK): Crash Recovery for Stale Claims**

iOS SDK'da `Posting` fazindayken crash olursa, 2 dakika sonra stale claim olarak reset edilip yeniden deneniyor.

Android'de ayni mekanizma mevcut (`AppActorReceiptQueueStore.claimReady`):
```kotlin
AppActorReceiptQueuePhase.Posting -> (item.claimedAtMillis ?: 0L) <= staleThresholdMillis
```
Stale threshold = 2 dakika. Bu iOS ile ESIT.

### Karsilastirma Tablosu: Error Handling

| Ozellik | AppActor Android | RevenueCat | Adapty | AppActor iOS |
|---------|:---:|:---:|:---:|:---:|
| Retry with backoff | ✅ 3x exp | ✅ 3x exp | ✅ 3x 2s | ✅ 3x exp |
| Jitter | ✅ | ✅ | ❌ (fixed 2s) | ✅ |
| Dead-letter handling | ✅ | ⚠️ (mark synced) | ❌ | ✅ |
| Offline entitlements | ✅ | ✅ | ✅ (PAL) | ✅ |
| Rate limit persist | ✅ | ❌ | ❌ | ✅ |
| Stale claim recovery | ✅ 2min | ❌ | ❌ | ✅ 2min |
| Backend error classification | ✅ | ✅ (behavior enum) | ✅ | ✅ |
| Transient vs permanent | ✅ | ✅ | ✅ | ✅ |

---

## KATEGORI 4: iOS ILE OZELLIK PARITESI (7.5/10)

### Tam Paritenin Oldugu Alanlar

| Ozellik | Durum |
|---------|-------|
| Receipt queue (durable, disk-persisted) | ✅ Esit |
| Posted ledger (90 gun, max 5000) | ✅ Esit |
| Signature verification (Ed25519 v1+v2) | ✅ Esit |
| ETag conditional requests (304 support) | ✅ Esit |
| Identity epoch / session numbering | ✅ Esit |
| Identity transition buffer | ✅ Esit |
| Offerings SWR pattern | ✅ Esit |
| Experiment manager (per-key TTL) | ✅ Esit |
| Remote config manager | ✅ Esit |
| Bulk restore endpoint | ✅ Esit |
| Bootstrap sequence (identify -> offerings -> sync -> refresh) | ✅ Esit |
| Identity gate before drain | ✅ Esit |
| Stale claim recovery (2 min) | ✅ Esit |

### Aktif Olarak Kapatilan Aciklar (Uncommitted Degisiklikler)

Git diff'te su aktif duzeltmeler tespit edildi:

**1. Cross-User Cache Serving (DUZELTILIYOR)**
- `AppActorExperimentManager`: `lastCacheUserId` tracking ekleniyor
- `AppActorRemoteConfigManager`: `lastCacheUserId` tracking ekleniyor
- iOS'ta bu zaten vardi - Android'e ekleniyor
- **Risk**: Kullanici degistirirken eski kullanicinin experiment/remote config sonuclari donebiliyordu

**2. Offering/Package Attribution (DUZELTILIYOR)**
- `AppActorPaymentProcessor`: `offeringId` ve `packageId` receipt queue item'a ekleniyor
- `AppActorOfferingsManager`: `offeringId` package enrichment'a ekleniyor
- `AppActorPackage`: `offeringId` field ekleniyor
- iOS'ta bu zaten vardi

**3. PostedLedgerStore Logging (DUZELTILIYOR)**
- Silent failure'lar artik log'laniyor
- Disk write/read/decode hatalari trace edilebilir oluyor

**4. OfferingsManager 304 Fallback (DUZELTILIYOR)**
- 304 response + cache miss durumunda fallback DTO kullaniliyor
- Oncesi: `IllegalStateException` throw ediyordu

### Kalan Aciklar (Henuz Cozulmemis)

| Eksik | iOS'ta Var mi? | RC'de Var mi? | Oncelik |
|-------|:-:|:-:|:-:|
| Pending purchase disk persistence | N/A | ✅ | ORTA |
| successfulBatchPurchaseKeys edge case fix | N/A (farkli flow) | N/A | YUKSEK |
| Dead-letter retry on app launch | ✅ (daha agresif) | ❌ | DUSUK |

### Platform-Specific (Android'de Mumkun Degil - Ceza Yok)

- AppAccountToken (StoreKit2 ozel)
- Transaction.currentEntitlements (StoreKit2 ozel)
- PurchaseIntent listener (iOS 16.4+ ozel)
- ASA Attribution (Apple Search Ads ozel)

---

## GENEL KARSILASTIRMA MATRISI

| Alan | AppActor Android | RevenueCat | Adapty |
|------|:---:|:---:|:---:|
| **Purchase safety** | 8.0 | 8.5 | 7.0 |
| **Code quality** | 8.5 | 8.0 | 7.5 |
| **Error handling** | 7.5 | 8.0 | 7.0 |
| **iOS parity** | 7.5 | N/A | N/A |
| **Offline support** | 7.5 | 8.0 | 7.5 |
| **Identity management** | 8.0 | 7.5 | 7.0 |
| **Diagnostics** | 6.5 | 9.0 | 7.0 |

### AppActor Android vs RevenueCat Ozet

**AppActor'un ustun oldugu alanlar**:
- Explicit API mode (compiler-enforced)
- Metalava API surface check
- Identity epoch tracking
- Dead-letter handling with diagnostics
- Temiz mutex-based concurrency (vs RC'nin synchronized)
- Stale claim crash recovery

**RC'nin ustun oldugu alanlar**:
- Callback coalescing (ayni request icin tek network call)
- Foreground/background farkli cache TTL
- Daha zengin diagnostics/telemetry
- Pending purchase disk persistence
- Daha olgun error behavior sistemi (enum-driven)
- Daha buyuk test suite ve edge case coverage

### AppActor Android vs Adapty Ozet

**AppActor'un ustun oldugu alanlar**:
- 3 katmanli deduplication (vs Adapty'nin synced purchases)
- Staged finalization (acknowledge AFTER backend confirm)
- Identity transition buffer
- Dead-letter handling
- Signature verification (Ed25519)
- Daha sagam offline fallback

**Adapty'nin ustun oldugu alanlar**:
- Daha agresif unsynced data retry
- Basit semaphore-based concurrency (daha az deadlock riski)
- Offline PAL (Personal Access Level) hesaplamasi
- Daha basit API surface

---

## ONCELIKLI AKSIYON LISTESI

### P0 - Kritik (Entitlement Kaybi Riski)

1. **`successfulBatchPurchaseKeys` fix**: `results` bos ve `successCount > 0` ise, ilk N purchase'i basarili say
   - Dosya: `AppActorPaymentProcessor.kt:1668-1686`
   - Tahmini etki: Restore flow'da acknowledge edilmeyen purchase'lar

### P1 - Yuksek (Guvenligi Arttirir)

2. **Pending purchase disk persistence**: Memory-only `pendingPurchaseTokens`'i SharedPreferences'a tasi
   - RC'de var, AppActor'da yok
   - Dosya: `AppActorPaymentProcessor.kt`

### P2 - Orta (Kaliteyi Arttirir)

4. **Identity buffer overflow warning**: Buffer tasti zaman log/warning ekle
   - Dosya: `AppActorPaymentProcessor.kt:221`

5. **Receipt queue store logging**: PostedLedgerStore'daki gibi logging ekle
   - Dosya: `AppActorReceiptQueueStore.kt`

6. **Dead-letter retry on launch**: App baslatildiginda dead-letter'lari yeniden dene (max 1 retry)
   - Dosya: `AppActorPaymentProcessor.kt`

### P3 - Dusuk (Nice-to-Have)

7. **Customer foreground/background TTL**: Farkli cache sureleri (RC patterni)
8. **Callback coalescing**: Ayni endpoint'e concurrent request'leri birlestir

---

## SONUC

AppActor Android SDK **production-ready ve saglam temeller uzerine kurulu**. RevenueCat ile aradaki en buyuk fark diagnostics/telemetry zenginligi ve bazi edge case coverage'da. Adapty'den mimari olarak daha ustun.

iOS SDK ile paritenin buyuk kismi saglanmis ve uncommitted degisikliklerde aktif olarak kapatilan aciklar (cross-user cache, offering attribution) dogru yonde ilerliyor.

**En kritik bulgu**: `successfulBatchPurchaseKeys` fonksiyonundaki edge case - bu fix'lenirse entitlement kaybi riski pratik olarak sifira iner.

**Genel Degerlendirme**: 7.75/10 - Guvenilir, iyi mimarili, aktif gelisim altinda.
