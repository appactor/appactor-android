# Phase 09 - Bootstrap and Lifecycle

## Amac
SDK'nin `configure()` sonrasi baslangic sirasini ve uygulama yasam dongusu entegrasyonunu netlestirmek.

## Onerilen Bootstrap Sirasi
1. callback/listener wiring
2. identify
3. offerings fetch
4. billing connection warmup
5. syncPurchases
6. fresh customer fetch

## Kapsam
- `configure()` siralamasi
- lifecycle observer
- app foreground refresh
- billing reconnect davranisi

## Yazilacak Parcalar
- `internal/bootstrap/BootstrapCoordinator`
- `internal/bootstrap/BootstrapStep`
- lifecycle observer veya process lifecycle entegrasyonu

## Teknik Gorevler
- Configure cagrilari idempotent mi olacak karar ver
- Coklu configure cagrilarinda davranis netlestir
- App foreground oldugunda:
   - customer refresh gerekli mi
   - queue drain gerekli mi
- Billing connection koparsa reconnect stratejisi tanimla

## Ozel Notlar
- Passive expiration ya da grace period degisiklikleri her zaman push ile gelmeyebilir
- Bu yuzden foreground refresh customer state icin onemli
- Bootstrap sirasini bozmak race condition yaratabilir

## Cikti
- Deterministik startup akisi
- Yaşam dongusune bagli controlled refresh davranisi

## Bagimliliklar
- Faz 06, 07, 08 tamamlanmaya yakin olmali

## Test ve Dogrulama
- bootstrap order unit testleri
- configure iki kez cagrildiginda davranis testleri
- foreground refresh testleri

## Done Kriteri
- SDK configure olduktan sonra predictable state'e geliyor
- Ilk kullanici deneyimi tutarli
- Lifecycle refresh mantigi net
