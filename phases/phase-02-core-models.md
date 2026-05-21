# Phase 02 - Core Models

## Amac
Store-agnostic ortak model katmanini kurmak. Bu katman hem backend contract'ini hem public SDK yuzeyini besleyecek.

## Yazilacak Ana Modeller
- `Store`
- `ProductType`
- `AppActorPackage`
- `AppActorOffering`
- `AppActorOfferings`
- `AppActorCustomerInfo`
- `AppActorEntitlementInfo`
- `AppActorSubscriptionInfo`
- `AppActorNonSubscription`
- `AppActorPurchaseResult`
- `AppActorPurchaseInfo`
- `AppActorError`

## Model Tasarim Kurallari
- Public modeller network DTO olmamali
- Play Billing `ProductDetails` veya `Purchase` tipleri public alana sizmamalı
- iOS tarafiyla kavramsal uyum korunmali
- `store`, `productId`, `basePlanId`, `offerId` gibi alanlar ortak katmanda yer almali

## Paket ve Offering Modelinde Gereken Alanlar
- `store`
- `productId`
- `storeProductId`
- `productType`
- `basePlanId`
- `offerId`
- `localizedPriceString`
- `price`
- `currencyCode`
- `displayName`
- `productName`
- `productDescription`
- `metadata`
- `tokenAmount`
- `position`

## Customer Tarafinda Gereken Alanlar
- active entitlements
- all entitlements
- subscriptions map
- non subscriptions listesi/map'i
- product entitlement mapping
- store state
- compound subscription key

## Teknik Gorevler
- `models/` altinda public data class'lari ekle
- Enum alanlarini backend contract ile uyumlu tut
- Nullability kararlarini acik ver
- `PurchaseResult` durumlarini belirle:
   - success
   - pending
   - userCancelled
   - failed

## Ozel Notlar
- `subscriptionKey` Android compound key mantigini desteklemeli
- `productEntitlements` key'leri opaque kabul edilmeli
- Public model katmaninda StoreKit veya BillingClient tipleri olmamali

## Cikti
- Tam public model seti
- Public result ve error yapisi
- Sonraki backend mapper ve billing adapter fazlari icin stabil hedef

## Bagimliliklar
- Faz 01 public API kararlari sabitlenmis olmali

## Test ve Dogrulama
- Model olusturma/serialization unit testleri
- Equality ve nullability davranislari
- Purchase result durumlari icin kucuk mapper testleri

## Done Kriteri
- Tum temel public modeller yazilmis
- Public API bu modellerle derleniyor
- Sonraki fazlarda DTO -> model map'i icin net hedef var
