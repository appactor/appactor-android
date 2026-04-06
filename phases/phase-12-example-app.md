# Phase 12 - Example App

## Amac
SDK'nin gercek kullanimini gosteren, hem gelistirme sirasinda smoke test olarak kullanilan hem de daha sonra dokumantasyon ornegi olabilecek bir Android example uygulamasi hazirlamak.

## Kapsam
- configure ekranı
- identify/login/logout aksiyonlari
- offerings listeleme
- package satin alma
- restore ve sync aksiyonlari
- customer info ekranı
- log/debug gosterimi

## Ekranlar
1. Configuration screen
2. Offerings screen
3. Customer info screen
4. Debug/log screen

## Teknik Gorevler
- `app` modulunu example olarak duzenle
- basit state holder/ViewModel yaz
- SDK callback ve suspend API kullanimini goster
- hata ve result durumlarini ekranda okunur goster

## Ozel Hedefler
- Sadece guzel gorunsun diye degil, SDK davranisini dogrulamak icin kullanilacak
- Purchase ve restore flow'lari burada kolay tetiklenebilir olmali
- Farkli appUserId ile login/logout denenebilir olmali

## Cikti
- Uctan uca calisan demo uygulama
- Manuel QA icin arac
- Gelecekte README ornekleri icin referans

## Bagimliliklar
- Faz 06, 07, 08 en azindan calisir durumda olmali

## Test ve Dogrulama
- Example build her zaman yesil olmali
- Manual smoke test senaryolari tanimlanmali:
  - offerings yukleniyor mu
  - satin alma basliyor mu
  - restore tetikleniyor mu
  - customer bilgisi guncelleniyor mu

## Done Kriteri
- Example app SDK'nin temel akisini gosterebiliyor
- QA veya gelistirici birkac tik ile kritik akisları test edebiliyor
