# Phase 08 - Customer Sync and Restore

## Amac
Customer state'ini Android tarafinda guvenli ve tutarli sekilde guncellemek; explicit restore ile background sync akislarini ayirmak.

## Kapsam
- `getCustomerInfo()`
- `syncPurchases()`
- `restorePurchases()`
- active purchases scan
- bulk restore
- customer cache seed/update

## Akislar
### getCustomerInfo
- cache-first donus
- stale ise network refresh

### syncPurchases
- cihazdaki aktif satin alimlari tara
- queue veya receipt post ile backend ile hizala
- sonra taze customer cek

### restorePurchases
- kullanici aksiyonuyla cagrilir
- aktif purchase'lari topla
- bulk restore endpoint'ine gonder
- basarisizsa tekil receipt path fallback dusun

## Yazilacak Parcalar
- `managers/CustomerManager`
- `managers/SyncManager` veya `domain/PurchasesInteractor`
- `restore/RestoreCoordinator`

## Teknik Gorevler
- customer cache TTL stratejisi
- `requestId` ve hata bilgisini loglama
- sync ve restore farklarini API ve ic mantikta ayirma
- bulk restore sonucu ile customer cache'i seed etme

## Ozel Durumlar
- kullanici login degistirirse cache invalidation
- restore response customer state'i degistirirse callback yayinla
- offline durumda stale customer cache kontrollu sekilde sunulabilir

## Cikti
- Calisan customer retrieval
- Senkronizasyon ve restore akislari
- Customer cache'in merkezi yonetimi

## Bagimliliklar
- Faz 03 backend customer/restore contract
- Faz 04 cache/storage
- Faz 07 purchase pipeline

## Test ve Dogrulama
- customer cache hit/miss testleri
- sync purchase scan testleri
- restore success/fallback testleri
- customer callback update testleri

## Done Kriteri
- `getCustomerInfo`, `syncPurchases`, `restorePurchases` calisiyor
- Cache ve network akisi tutarli
- Customer state dogru sekilde guncelleniyor
