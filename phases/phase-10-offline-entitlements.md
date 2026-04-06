# Phase 10 - Offline Entitlements

## Amac
Network yokken veya backend gecici olarak ulasilamazken local customer cache ve entitlement mapping kullanarak minimum offline access kontrolunu saglamak.

## Kapsam
- `activeEntitlementKeysOffline()` benzeri davranis
- `productEntitlements` mapping yorumlama
- customer cache uzerinden local access hesaplama

## Temel Kural
Backend source of truth olmaya devam eder. Offline fallback sadece gecici ve kontrollu bir yardimci mekanizma olacak.

## Desteklenecek Key Formatlari
- subscription:
  - `android:{productId}:{basePlanId}`
- one-time / non-subscription:
  - `android:{productId}`

## Yazilacak Parcalar
- `managers/OfflineEntitlementManager`
- `models/EntitlementKeyResolver`

## Teknik Gorevler
- active subscription listesi uzerinden compound key uret
- product entitlements map'i ile entitlement key'leri cikar
- gecersiz veya baska store key'lerini ignore et
- stale customer cache durumunda davranisi netlestir

## Ozel Durumlar
- `basePlanId` bossa hangi key uretilecek karar ver
- expired subscription local access vermemeli
- cancelled ama grace period icindeki kullanici state'i backend customer verisine gore yorumlanmali

## Cikti
- Offline access helper
- Example ve uygulama tarafinda hizli local gate kontrolu

## Bagimliliklar
- Faz 02 customer modelleri
- Faz 04 customer cache
- Faz 08 customer state akisi

## Test ve Dogrulama
- compound key resolver testleri
- stale cache testleri
- false-positive prevention testleri
- one-time purchase mapping testleri

## Done Kriteri
- Network yokken minimum access kontrolu yapilabiliyor
- Yanlis entitlement verme riski minimuma indiriliyor
