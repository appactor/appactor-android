# Phase 06 - Offerings Flow

## Amac
Backend'den gelen offerings contract'ini Google Play ProductDetails ile birlestirip SDK'nin kullanabilecegi nihai offerings sonucunu uretmek.

## Kapsam
- backend offerings fetch
- cache-first davranis
- ETag/304 handling
- Play urun detaylarini enrich etme
- uyumsuz ya da eksik urunleri filtreleme

## Akis
1. Cache uygun mu kontrol et
2. Gerekirse backend offerings cagir
3. Donen package/product referanslarini grupla
4. Sadece `play_store` urunlerini billing adapter'a gonder
5. ProductDetails sonucunu package modelleriyle birlestir
6. Eksik urunleri logla ve filtrele
7. Son offerings sonucunu cache'e yaz

## Teknik Gorevler
- `managers/OfferingsManager` yaz
- `cache/OfferingsCacheStore` ile bagla
- ETag support ekle
- `productId + basePlanId + offerId` kombinasyonundan dogru Play urununu bul
- Backend'in dondugu siralama ve package metadata'sini koru

## Ozel Durumlar
- Backend package var ama Play urunu yok
- Sadece one-time urun var
- `offerId` bos olabilir
- Ayni package icin birden fazla varyant olabilir

## Cikti
- `getOfferings()` calisan hale gelir
- Cache'den hizli donus + network refresh mantigi oturur

## Bagimliliklar
- Faz 03 backend contract layer
- Faz 04 cache
- Faz 05 billing adapter

## Test ve Dogrulama
- offerings decode + enrich testleri
- ETag 304 testleri
- eksik product details durum testleri
- siralama ve metadata korunumu testleri

## Done Kriteri
- SDK kullaniciya enrich edilmis offerings donebiliyor
- Cache ve network akisi kontrollu calisiyor
- Play product details ile backend contract tutarli esleniyor
