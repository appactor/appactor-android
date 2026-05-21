# Phase 07 - Purchase Pipeline

## Amac
Android SDK'nin en kritik parcasi olan satin alma akisini kurmak: billing sonucu alma, queue'ya yazma, backend receipt post etme, ack/consume kararlarini uygulama ve retry mantigini yonetme.

## Ana Akis
1. Kullanici `purchase(...)` cagirir
2. Billing flow baslar
3. Billing sonucu `Purchase` nesnesi olarak gelir
4. Purchase queue'ya yazilir
5. Receipt backend'e post edilir
6. Backend sonucu:
   - `ok`
   - `retryable_error`
   - `permanent_error`
7. Sonuca gore:
   - ack
   - consume
   - queue'da tut
   - ledger'a yaz
   - customer cache guncelle

## Yazilacak Parcalar
- `pipeline/PaymentProcessor`
- `pipeline/PurchaseQueueWorker`
- `pipeline/ReceiptRequestBuilder`
- `pipeline/RetryPolicy`
- `pipeline/PostResultHandler`

## Teknik Gorevler
- Queue item olusturma
- Client idempotency key uretme
- Duplicate purchase token kontrolu
- Receipt request body olusturma
- `retryAfterSeconds` destekleme
- Basarili post sonrasi ledger guncelleme
- Ack/consume backend kararina gore uygulama

## Kritik Kurallar
- `retryable_error` durumunda purchase kaybolmamali
- `permanent_error` durumunda sonsuz retry olmamali
- Duplicate token geldiyse ledger sayesinde ikinci kez post edilmemeli
- Ack veya consume sadece backend `ok` dediginde yapilmali

## iOS'tan Korunacak Guclu Fikir
- Durable queue
- Backoff/retry state
- Posted ledger
- Server-authoritative final karar

## Cikti
- Calisan purchase pipeline
- Retry ve duplicate korumasi
- Server-driven ack/consume uygulamasi

## Bagimliliklar
- Faz 04 storage/cache
- Faz 05 billing adapter
- Faz 03 backend receipt contract

## Test ve Dogrulama
- ok/retryable/permanent path testleri
- duplicate ledger testleri
- backoff testleri
- ack/consume decision testleri

## Done Kriteri
- Basarili satin alma uctan uca isleniyor
- Retryable durumlarda veri kaybi olmuyor
- Duplicate post ve yanlis ack engelleniyor
