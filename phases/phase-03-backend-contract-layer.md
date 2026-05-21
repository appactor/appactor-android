# Phase 03 - Backend Contract Layer

## Amac
Backend'in hazir Android Billing v1 contract'ini Android SDK tarafinda tuketecek network ve DTO katmanini kurmak.

## Endpoint Kapsami
- `identify`
- `getOfferings`
- `getCustomer`
- `postGoogleReceipt`
- `postGoogleRestore`
- gerekiyorsa `login/logout/reset` ile ilgili endpointler

## Yazilacak Katmanlar
- `backend/client`
- `backend/dto`
- `backend/mappers`
- `backend/auth`
- `backend/signature` varsa response verification katmani

## Teknik Kararlar
1. HTTP stack secimi
   - Oneri: OkHttp + Kotlin serialization veya Moshi
2. JSON parse stratejisi
   - Unknown key tolerant olmali
3. Auth
   - API key header yapisi
4. `requestId`, ETag, cache header handling
5. Hata siniflandirmasi:
   - transport error
   - backend permanent error
   - backend retryable error

## DTO Tarafinda Desteklenecek Alanlar
- offerings icinde:
   - `store`
   - `productId`
   - `storeProductId`
   - `basePlanId`
   - `offerId`
   - `displayName`
- customer icinde:
   - `store`
   - `basePlanId`
   - `offerId`
   - `subscriptionKey`
- receipt response icinde:
   - `status`
   - `acknowledgePurchase`
   - `consumePurchase`
   - `retryAfterSeconds`
   - `customer`
   - `requestId`

## Yapilacaklar
- Endpoint bazli request/response DTO'lari yaz
- DTO -> public model mapper'lari yaz
- Idempotency key stratejisini receipt request modellerine ekle
- `ok | retryable_error | permanent_error` handling modellerini ekle
- Signature verification kullanilacaksa header parse katmani ac

## Cikti
- Backend ile konusan client
- Tam DTO seti
- Mapper katmani

## Bagimliliklar
- Faz 02 modelleri hazir olmali
- Backend contract dokumani sabit olmalı

## Riskler
- Nullability farklari crash'e sebep olabilir
- Legacy alanlar varsa backward compatibility ihtiyaci dogabilir
- Error class mapping'i ilk denemede eksik kalabilir

## Test ve Dogrulama
- Fixture tabanli JSON decode testleri
- DTO -> model mapper testleri
- Receipt response classification testleri

## Done Kriteri
- Backend fixture JSON'lari Android SDK tarafinda sorunsuz decode oluyor
- Public modellere donusum tamam
- Hata tipleri ve request modelleri net
