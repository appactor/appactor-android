# Phase 00 - Foundation and Project Setup

## Amac
Android SDK gelistirmesi icin stabil, tekrar uretilebilir ve publish'e uygun bir temel kurmak. Bu faz sonunda proje yapisi, Gradle ayarlari, moduller ve temel build akisi netlesmis olmali.

## Hedef Kapsam
- `appactor-android` Android Library modulunun ana SDK modulu olarak sabitlenmesi
- `app` modulunun example/demo uygulama olarak konumlanmasi
- Gradle Kotlin DSL, namespace, minSdk, compileSdk, targetSdk, Java/Kotlin hedefleri
- Ilk build'in sorunsuz alinmasi
- Ilk dokumantasyon klasor yapisinin olusmasi

## Bu Fazda Yapilacaklar
1. Proje modullerini sabitle:
   - `:appactor-android` -> asil SDK
   - `:app` -> example uygulama
2. Modul bagimliliklarini netlestir:
   - `app` icinde `implementation(project(":appactor-android"))`
3. SDK modulunde ilk publish-friendly alanlari planla:
   - namespace
   - consumer Proguard rules
   - versioning strategy
4. Gradle seviyesinde ortak kararlar:
   - `compileSdk = 36`
   - `minSdk = 24`
   - Kotlin/JVM target
5. Klasor standardini olustur:
   - `api`
   - `models`
   - `backend`
   - `billing`
   - `cache`
   - `storage`
   - `pipeline`
   - `managers`
   - `internal/bootstrap`
   - `logging`
6. Ilk README ve faz dokumanlari icin yer belirle:
   - `phases/`

## Teknik Gorevler
- `settings.gradle.kts` icinde modullerin acik ve duzgun tanimli oldugunu dogrula
- `app/build.gradle.kts` icinde SDK bagimliligini ekle
- `appactor-android/build.gradle.kts` icinde library plugin, namespace ve release build ayarlarini netlestir
- Yerel build komutu ile `:app:assembleDebug` basarili olacak hale getir
- IDE ve terminal build ortaminda Java/JBR kullanımını not et

## Cikti
- Derlenen iki modullu Android proje yapisi
- SDK ve example ayrimi net bir repo
- Sonraki fazlar icin stabil temel

## Bagimliliklar
- Android Studio proje olusturma tamamlaniyor olmali
- Local Android SDK ve JDK/JBR kurulu olmali

## Riskler
- Compose bagimliliklari compileSdk'i yukari cekebilir
- Farkli makinalarda JDK/JBR uyumsuzlugu olabilir
- Erken asamada modulleri yanlis adlandirmak sonraki publish isini zorlastirir

## Test ve Dogrulama
- `:app:assembleDebug`
- `:appactor-android:assemble`
- IDE sync basarili olmali

## Done Kriteri
- Example app SDK modulunu consume ediyor
- Proje temiz sync oluyor
- En az bir debug build basarili
- Sonraki fazlar icin klasor ve modul temeli oturmus oluyor
