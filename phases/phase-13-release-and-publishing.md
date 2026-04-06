# Phase 13 - Release and Publishing

## Amac
Android SDK'yi v1 olarak yayinlanabilir hale getirmek: versioning, changelog, publish konfigurasyonu, consumer kurallari, release checklist ve son kalite kontrol.

## Kapsam
- semantic versioning
- publish metadata
- Maven publish hazirligi
- Proguard/consumer rules
- changelog
- release verification

## Yapilacaklar
1. Version strategy belirle
   - `1.0.0` ya da belirlenen release version
2. SDK metadata hazirla
   - artifact id
   - group id
   - package namespace
3. Publish plugin/config hazirla
4. `consumer-rules.pro` ve gerekiyorsa keep rules ekle
5. README ve entegrasyon ornegi hazirla
6. Release checklist yaz

## Release Checklist
- tum testler yesil
- example build yesil
- manual purchase smoke test tamam
- restore smoke test tamam
- changelog guncel
- version bump yapildi
- publish artifact dogrulandi

## Ek Teknik Gorevler
- signing ve repository hedefi belirle
- dokumantasyon icin minimum entegrasyon snippet'leri ekle
- coroutine, billing ve network bagimliliklarinin public API'ye sizmadigini kontrol et

## Cikti
- Yayinlanabilir Android SDK
- Release notlari
- Consumer entegrasyon dokumani

## Bagimliliklar
- Onceki fazlarin uygulama seviyesinde tamamlanmis olmasi gerekir

## Riskler
- Publish son anda degisen artifact koordinatlari
- Proguard kurallarinin eksik kalmasi
- Example ve gercek entegrasyon arasinda farkli davranis cikmasi

## Test ve Dogrulama
- release build
- local publish dry-run
- sample integration smoke test

## Done Kriteri
- Android SDK artifact olarak yayinlanmaya hazir
- Versiyonlama ve release sureci dokumante
- v1 release karari verilebilir
