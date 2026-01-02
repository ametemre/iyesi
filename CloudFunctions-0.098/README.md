# CloudFunctions Dosyaları - Branch 0.098

Bu klasör, `0.098` branch'ine ait CloudFunctions ile ilgili Java dosyalarını içermektedir.

## Dosyalar

1. **CloudFunctionsClient.java**
   - Cloud Functions istemci sınıfı
   - Base URL ve endpoint yapılandırması için template
   - Konum: `app/src/main/java/com/kurmez/iyesi/kurmes/utilities/clients/CloudFunctionsClient.java`

2. **CFHelper.java**
   - Cloud Functions istemci yardımcı sınıfı
   - Firebase ID Token ve App Check token yönetimi
   - HTTP ve Callable endpoint desteği
   - Role yönetimi ve kullanıcı listeleme fonksiyonları
   - Konum: `app/src/main/java/com/kurmez/iyesi/kurmes/utilities/helper/CFHelper.java`

3. **CFObligations.java**
   - Founded akışındaki Cloud Functions sorumluluklarını yöneten yardımcı katman
   - Pending companion kontrolü
   - "soul_inneed" isteği gönderimi ve retry mekanizması
   - Hata kodu ayrıştırma
   - Konum: `app/src/main/java/com/kurmez/iyesi/kurmes/utilities/helper/CFObligations.java`

4. **CFClient.java**
   - Cloud Functions HTTP istemcisi
   - Firebase ID Token + App Check header ekleme
   - GET/POST/PATCH/DELETE yardımcı metodları
   - Async ve sync API desteği
   - Konum: `app/src/main/java/com/kurmez/iyesi/kurmes/utilities/helper/net/CFClient.java`

## Kullanım

Bu dosyalar Firebase Cloud Functions ile iletişim kurmak için kullanılır. Her dosya farklı bir sorumluluğa sahiptir:

- **CFHelper**: Temel Cloud Functions işlemleri ve token yönetimi
- **CFClient**: HTTP tabanlı Cloud Functions çağrıları
- **CFObligations**: Özel iş mantığı (pending companion, soul_inneed)
- **CloudFunctionsClient**: İstemci template'i

## Notlar

- Tüm dosyalar `origin/0.098` branch'inden alınmıştır
- Dosyalar Android projesi için Java ile yazılmıştır
- Firebase Authentication ve App Check entegrasyonu içerir
