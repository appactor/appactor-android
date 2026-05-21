# Phase 04 - Storage and Cache

## Amac
Kimlik, queue, posted ledger, customer cache, offerings cache ve ETag verilerini Android tarafinda guvenli sekilde saklamak.

## Kapsam
- identity persistence
- install id / app user id saklama
- customer cache
- offerings cache
- ETag metadata
- receipt queue store
- posted ledger

## Mimari Oneri
- Hafif key-value veriler icin `SharedPreferences` veya `DataStore`
- Yapili cache/queue dosyalari icin disk JSON store
- Queue ve posted ledger icin atomik yazim mantigi

## Yazilacak Parcalar
- `storage/IdentityStore`
- `cache/OfferingsCacheStore`
- `cache/CustomerCacheStore`
- `cache/ETagStore`
- `storage/ReceiptQueueStore`
- `storage/PostedLedgerStore`

## Teknik Gorevler
1. Identity alanlarini tanimla:
   - appUserId
   - installId
   - serverUserId
2. Cache entry modeli tasarla:
   - payload
   - etag
   - lastUpdatedAt
   - expiresAt
3. Queue item modeli tasarla:
   - purchaseToken
   - productId
   - basePlanId
   - offerId
   - retryCount
   - nextRetryAt
   - createdAt
4. Posted ledger modeli:
   - unique purchase token veya compound post key
   - post time
5. Corruption ve migration stratejisi:
   - okunamayan cache dosyasi silinsin

## iOS'tan Tasinacak Ana Fikir
- Queue durable olacak
- Retry state diskte yasayacak
- Duplicate post korumasi ledger ile yapilacak
- Cache stale olabilir ama kontrolsuz bozulmamis olmali

## Cikti
- Kalici storage katmani
- Queue ve posted ledger altyapisi
- ETag destekli cache altyapisi

## Bagimliliklar
- Faz 03 backend client DTO katmani hazir olmali

## Test ve Dogrulama
- Queue CRUD testleri
- Cache read/write testleri
- Corrupt data recovery testleri
- Duplicate ledger testleri

## Done Kriteri
- Identity, cache ve queue state uygulama kapanip acilsa da korunuyor
- Retry ve duplicate bilgisi kaybolmuyor
- ETag metadata saklanabiliyor
