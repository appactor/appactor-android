# Phase 05 - Google Play Billing Adapter

## Amac
Google Play Billing tarafini SDK icin soyutlayacak adapter katmanini kurmak. Bu katman BillingClient ile konusur ama public API'ye Play tipleri sızdirmaz.

## Kapsam
- BillingClient baglantisi
- ProductDetails sorgulama
- Billing flow baslatma
- Aktif satin alimlari tarama
- Restore/sync icin purchase listeleme
- Acknowledge ve consume islemleri

## Yazilacak Parcalar
- `billing/GooglePlayStoreAdapter`
- `billing/BillingConnectionManager`
- `billing/ProductDetailsMapper`
- `billing/PurchaseMapper`
- `billing/BillingErrorMapper`

## Teknik Tasarim
1. Ince abstraction kullan:
   - ileride baska store eklemek istersek araya interface kalmali
2. Public modele direkt `ProductDetails` koyma
3. Purchase launch icin `Activity` ihtiyacini sadece bu katmanda tut
4. Connection lifecycle yonet:
   - lazy connect
   - reconnect
   - shutdown

## Desteklenecek Operasyonlar
- `connect()`
- `queryProductDetails(products)`
- `launchPurchase(activity, productRef)`
- `queryActivePurchases()`
- `queryPurchaseHistory()` gerekiyorsa
- `acknowledgePurchase(token)`
- `consumePurchase(token)`

## Kritik Kararlar
- Subscription ve one-time urunler icin query mantigi ayrilacak
- `basePlanId` + `offerId` -> `offerToken` cozumleme burada yapilacak
- Billing sonucu map edilirken:
   - purchased
   - pending
   - cancelled
   - failed
  ayrilacak

## Cikti
- Calisan Google Play adapter
- ProductDetails ve Purchase verisini ic modellere cevirebilen katman

## Bagimliliklar
- Faz 02 modelleri
- Faz 03 backend contract bilgisi

## Riskler
- Offer token secimi yanlis olursa satin alma baslamaz
- BillingClient reconnect ve background davranislari sorun cikartabilir
- Consumable ve non-consumable handling karisabilir

## Test ve Dogrulama
- Billing wrapper unit testleri
- Fake adapter ile launch path testleri
- ProductDetails secim testleri
- Ack/consume karar testleri

## Done Kriteri
- Adapter urun cekebiliyor
- Billing flow baslatabiliyor
- Ack/consume cagirilarina cevap verebiliyor
- Public API bu katmana yaslanmaya hazir
