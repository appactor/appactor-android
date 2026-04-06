# Phase 11 - Test Strategy and Coverage

## Amac
Android SDK'nin kritik satin alma ve cache akislarini release once guvenli hale getirmek icin test katmanini tamamlamak.

## Test Kategorileri
- unit tests
- fixture decode tests
- mapper tests
- storage tests
- retry/queue tests
- fake billing adapter tests
- integration-style manager tests
- example smoke tests

## Kapsanacak Ana Alanlar
1. Public model mapping
2. Offerings decode ve enrichment
3. Customer decode
4. Queue persistence
5. Posted ledger duplicate korumasi
6. Receipt response classification
7. Ack/consume karar handling
8. Sync ve restore akislari
9. Offline entitlement fallback

## Teknik Gorevler
- `backend` fixture JSON dosyalari ekle
- Fake `PaymentClient` yaz
- Fake `StoreAdapter`/`BillingAdapter` yaz
- Fake cache/store implementasyonlari ekle
- deterministic retry policy testleri yaz

## Ozel Test Senaryolari
- duplicate purchase token
- retryable error sonra success
- permanent error sonra queue temizligi
- only-play product offerings
- missing ProductDetails
- login/logout sonrasi cache invalidation

## Cikti
- Kritik is mantigini koruyan test seti
- Regression riskini dusuren fixture tabani

## Bagimliliklar
- Temel implementation fazlari en azindan ilk versiyonlariyla bitmis olmali

## Riskler
- Fazla mock'lu testler gercek akislari kacirabilir
- BillingClient'i birebir taklit etmek zor olabilir

## Test Komutlari
- unit test task'leri
- gerekiyorsa instrumentation smoke test'leri

## Done Kriteri
- Kritik purchase/caching akislarinin testleri var
- DTO fixture'lari canonical source haline gelmis
- Refactor sirasinda guven veren bir test matrisi olusmus
