# Phase 01 - Public API Surface

## Amac
Android SDK'nin dis dunyaya sundugu public API'yi baslangicta dogru tanimlamak. Bu fazda method isimleri, temel modeller ve async davranislar netlesir.

## Ana Karar
Public API sade, store-agnostic ve Flutter/bridge icin uygun olmali. iOS tarafiyla mental model ayni kalmali ama Android tarafinda Kotlin'e dogal oturmali.

## Hedef Public API
- `AppActor.configure(...)`
- `AppActor.identify(...)`
- `AppActor.login(...)`
- `AppActor.logout()`
- `AppActor.reset()`
- `AppActor.getOfferings(...)`
- `AppActor.getCustomerInfo(...)`
- `AppActor.purchase(...)`
- `AppActor.restorePurchases()`
- `AppActor.syncPurchases()`

## Bu Fazda Verilecek Kararlar
1. API singleton/object mi olacak?
   - Oneri: `object AppActor`
2. Sonuclar `suspend` ile mi donecek, callback ile mi?
   - Oneri: internal coroutines + public suspend API
3. Listener/callback ihtiyaci:
   - `onCustomerInfoUpdated`
   - `onPurchaseResult`
4. Threading contract:
   - SDK ic mantik background coroutine'lerde calisir
   - public API main-safe olur
5. Error modeli:
   - tek bir `AppActorError` hiyerarsisi

## Teknik Gorevler
- `api/AppActor.kt` icin taslak sinif/object ac
- Configuration modeli tanimla
- Option set veya config options modelini tanimla
- Purchase method imzasi icin hangi girdi kullanilacak karar ver:
   - `purchase(activity, packageModel)`
   - opsiyonel `purchase(activity, AppActorPurchaseParams)` ama sadece explicit direct Play target icin
- `restore` ve `sync` farkini API seviyesinde netlestir

## Dikkat Edilecek Tasarim Kurallari
- Public API Play Billing tiplerini dogrudan expose etmesin
- DTO veya raw network modelleri public package'a sizmasin
- Public tipler Flutter bridge ve gelecekte baska wrapper'lar icin stabil olsun
- `Activity` gerektiren methodlar sadece billing launch katmaninda bunu istesin
- `purchase(activity, packageModel)` primary satin alma yolu olmali
- direct purchase sadece typed target ile calismali; ciplak `productId` ile purchase desteklenmemeli

## Cikti
- Public API taslagi
- Method imzalari
- Callback/observer stratejisi
- Error ve result yuzeyi

## Bagimliliklar
- Faz 00 tamamlanmis olmali
- iOS SDK'daki yeni store-agnostic contract referans alinmali

## Test ve Dogrulama
- Public API icin derleme testi
- Minimal smoke test: `configure()` ve `getCustomerInfo()` suspend olarak mock client ile cagirilabilmeli

## Done Kriteri
- Public API isimleri artik sik degismeyecek kadar net
- Android ekibi ve gelecekteki Flutter bridge bu API uzerinden plan yapabilir durumda
