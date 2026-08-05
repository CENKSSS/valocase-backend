# Veritabanından level'i elle değiştirebilme — tasarım dokümanı

**Durum:** öneri / onay bekliyor. Bu doküman ne yapılacağını anlatır, hiçbir kod değişikliği içermez.
**Tarih:** 2026-08-02
**İstek:** `accounts.level` kolonuna DataGrip'ten 45 yazınca oyunun da 45 görmesi; `total_xp` girilen level'e göre kendiliğinden ayarlanmalı.

---

## 1. Mevcut durum (neden şu an çalışmıyor)

Sunucuda tek doğruluk kaynağı `accounts.total_xp`. `level` ve `current_level_xp` sadece birer cache.

| Gerçek | Kanıt |
|---|---|
| Level her okumada `total_xp`'den türetiliyor | [`ProgressionService.levelOf`](../src/main/java/com/cenk/valocase/progression/service/ProgressionService.java#L127) → `levelForXp(account.getTotalXp())` |
| Kod tabanında `account.getLevel()` çağrısı **hiç yok** | Level tüketen 4 yer de `levelOf`/`buildView` kullanıyor: [`WalletController:35`](../src/main/java/com/cenk/valocase/wallet/web/WalletController.java#L35), [`CatalogService:130`](../src/main/java/com/cenk/valocase/catalog/service/CatalogService.java#L130), [`CaseOpeningService:86`](../src/main/java/com/cenk/valocase/caseopening/service/CaseOpeningService.java#L86), [`BattleLobbyService:234`](../src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java#L234) |
| Elle yazılan level ilk XP kazanımında siliniyor | [`applyDerivedFields`](../src/main/java/com/cenk/valocase/progression/service/ProgressionService.java#L226) `level` ve `current_level_xp`'yi `total_xp`'den yeniden yazıyor |
| Level yazan sadece 2 akış var | `grantCaseOpenXp` çağrıları: [`CaseOpeningService:177`](../src/main/java/com/cenk/valocase/caseopening/service/CaseOpeningService.java#L177) (kasa açma, +5 XP) ve [`BattleLobbyService:827`](../src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java#L827) (PvP battle) |
| Şema Flyway'in, Hibernate sadece doğruluyor | `spring.jpa.hibernate.ddl-auto=validate`, `application.properties:19` |
| Projede Spring Security **yok** | `pom.xml` içinde `spring-boot-starter-security` bağımlılığı yok; hiçbir controller kimlik doğrulaması yapmıyor |

Eşik tablosu ([`LEVEL_THRESHOLDS`](../src/main/java/com/cenk/valocase/progression/service/ProgressionService.java#L52)): level 1–15 sabit tablo (level 15 = 1350 XP), 15'ten sonrası düz +100 XP/level. Üst sınır yok.

---

## 2. Değerlendirilen seçenekler

| # | Yaklaşım | Karar | Gerekçe |
|---|---|---|---|
| **A** | **PostgreSQL trigger:** `level` elle değişince `total_xp` otomatik hesaplansın | **Seçildi** | İstenen davranışın tam karşılığı — DataGrip'te hücreye 45 yazıp kaydetmek yeterli. Java tarafında davranış değişmiyor, `total_xp` doğruluk kaynağı olarak kalıyor. Tek migration + tek IT. |
| B | Java'da `level`'i doğruluk kaynağı yapmak | Elendi | `ProgressionService`'in tamamının, XP birikim semantiğinin (level içi artık XP), `CaseOpenProgressionResponse`'un ve 4 çağrı noktasının yeniden yazılması demek. Level atlarken artık XP'nin ne olacağı tanımsız hale gelir. |
| C | Admin REST endpoint (`PATCH /admin/accounts/{id}/level`) | Elendi | Projede kimlik doğrulama katmanı yok — endpoint herkese açık olurdu, oyuncu kendi level'ini 45 yapabilirdi. Önce Spring Security + admin kimlik doğrulama eklemek gerekir; bu ayrı ve çok daha büyük bir iş. |
| D | Okuma anında uzlaştırma (stored level ≠ türetilmiş level ise stored'u kabul et) | Elendi | Her okumayı belirsiz hale getirir, XP barını bozar (`current_level_xp` ile `total_xp` çelişir), iki kaynak arasında kalıcı bir tutarsızlık yaratır. |

Seçenek C ileride admin paneli istenirse yine gündeme gelir; A onu engellemez, aynı eşik fonksiyonunu paylaşırlar.

---

## 3. Seçilen tasarım (Seçenek A)

### 3.1 Mantık

`accounts` tablosuna `BEFORE UPDATE` trigger'ı eklenir. Trigger **yalnızca** şu koşulda ateşlenir:

> `level` değişti **ve** `total_xp` değişmedi

Bu, "insan eliyle sadece level kolonuna dokunuldu" imzasıdır. Trigger o satırda `total_xp`'yi o level'in eşiğine, `current_level_xp`'yi 0'a çeker.

Backend'in kendi yazmaları bu koşula **hiç uymaz**: `grantCaseOpenXp` her zaman `total_xp`'yi artırır (kasa açma +5, battle +N), yani `total_xp` daima değişir → trigger susar. Yani oyun akışı hiçbir şekilde etkilenmez.

### 3.2 Yeni migration: `V79__manual_level_edit.sql`

İki nesne oluşturur.

**(a) Eşik fonksiyonu** — `ProgressionService.totalXpForLevel` ile birebir aynı matematik:

```sql
CREATE OR REPLACE FUNCTION account_total_xp_for_level(p_level integer)
RETURNS bigint
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    -- ProgressionService.LEVEL_THRESHOLDS ile birebir aynı olmak zorunda.
    thresholds bigint[] := ARRAY[0,40,95,160,250,350,465,610,775,860,945,1050,1155,1250,1350];
BEGIN
    IF p_level IS NULL OR p_level < 1 THEN
        RAISE EXCEPTION 'level must be at least 1, got %', p_level;
    END IF;
    IF p_level <= 15 THEN
        RETURN thresholds[p_level];
    END IF;
    RETURN thresholds[15] + (p_level - 15)::bigint * 100;
END;
$$;
```

`bigint` dönüşü kasıtlı: level `INTEGER` tavanında (2147483647) bile sonuç ~2.1e11, `total_xp` (BIGINT) taşmaz.

**(b) Trigger:**

```sql
CREATE OR REPLACE FUNCTION accounts_apply_manual_level()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.total_xp         := account_total_xp_for_level(NEW.level);
    NEW.current_level_xp := 0;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_manual_level_sets_xp
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    WHEN (NEW.level IS DISTINCT FROM OLD.level
          AND NEW.total_xp IS NOT DISTINCT FROM OLD.total_xp)
    EXECUTE FUNCTION accounts_apply_manual_level();
```

Tasarım notları:
- `BEFORE UPDATE` + `NEW` üzerinde değişiklik → ikinci bir UPDATE yok, dolayısıyla trigger özyinelemesi yok.
- `WHEN` koşulu trigger'ı satır seviyesinde filtreler; koşul tutmayan güncellemelerde fonksiyon hiç çağrılmaz (performans etkisi sıfıra yakın).
- `level < 1` yazılırsa `RAISE EXCEPTION` ile UPDATE reddedilir; `accounts.level` bozuk bir değere düşemez.
- `INSERT` kapsam dışı: yeni hesaplar zaten level 1 / 0 XP ile doğuyor.

### 3.3 Kullanım (migration sonrası)

DataGrip'te `level` hücresine `45` yazıp kaydetmek yeterli. Trigger `total_xp`'yi 4350, `current_level_xp`'yi 0 yapar. Aynısı SQL ile:

```bash
psql "$DATABASE_URL" -c "UPDATE accounts SET level = 45 WHERE display_name = 'cenk';"
```

Level'i geri düşürmek de çalışır (`level = 3` → `total_xp = 95`); XP kaybı bilinçli ve beklenen sonuçtur.

---

## 4. Değişecek dosyalar

| Dosya | İşlem | Neden |
|---|---|---|
| `src/main/resources/db/migration/V79__manual_level_edit.sql` | **Yeni** | Bölüm 3.2'deki fonksiyon + trigger. Sıradaki numara V79 (son migration V78). |
| [`ProgressionService.java`](../src/main/java/com/cenk/valocase/progression/service/ProgressionService.java#L28) | Javadoc güncelle | 28–40. satırlardaki blok "level kolonunu elle düzenlemek hiçbir şeyi değiştirmez" diyor. Bu artık **yanlış** olacak. Yeni davranış ve trigger'a atıf yazılmalı. |
| [`ProgressionService.resyncDerivedFields`](../src/main/java/com/cenk/valocase/progression/service/ProgressionService.java#L153) | **Sil** | İki sebep: (1) production'da hiçbir çağıranı yok — ölü kod, sadece testten çağrılıyor; (2) işlevi trigger'ın tam tersi (elle yazılan level'i `total_xp`'den geri ezmek). Kalırsa ileride birinin onu çağırması manuel düzenlemeyi sessizce geri alır. |
| `ProgressionServiceTest.java` | `resyncDerivedFields_repairsAHandEditedLevelColumn` testini kaldır (199–206) | Silinen metoda ait. Diğer testler (özellikle `totalXpForLevel_*`, 181–195) aynen kalır. |
| `src/test/java/com/cenk/valocase/migration/V79ManualLevelEditIT.java` | **Yeni** | Bölüm 5. |

Değişmeyecekler: `Account.java`, controller'lar, DTO'lar, `CaseOpeningService`, `BattleLobbyService`, catalog/unlock mantığı. Şema (kolon tipleri/nullability) değişmediği için `ddl-auto=validate` etkilenmez.

---

## 5. Test planı

Yeni IT, `V51CaseCategoryMigrationIT` kalıbını izler (`@SpringBootTest` + `@Testcontainers` + `postgres:16-alpine` + `JdbcTemplate`). Kapsanacak vakalar:

1. **Sözleşme testi (en kritik):** level 1–100 için SQL `account_total_xp_for_level(n)` ile Java `ProgressionService.totalXpForLevel(n)` aynı değeri döndürmeli. Eşik tablosunun iki yerde yaşamasının tek gerçek panzehiri budur — biri değişip diğeri değişirse test kırmızıya döner.
2. **Elle düzenleme çalışıyor:** `UPDATE accounts SET level = 45` → satırda `total_xp = 4350`, `current_level_xp = 0`; ardından `progressionService.levelOf(account)` = 45.
3. **Oyun akışı bozulmuyor:** manuel düzenlemeden sonra bir kasa açılışı → level 45 korunuyor, `total_xp` 4355 oluyor (trigger ateşlenmiyor).
4. **Trigger normal XP kazanımına karışmıyor:** level atlatan bir XP grant'i sonrası `total_xp` grant'in yazdığı değerde kalıyor (trigger tarafından eşiğe yuvarlanmıyor).
5. **Geçersiz level reddediliyor:** `SET level = 0` → exception, satır değişmemiş.
6. **Level düşürme:** `SET level = 3` → `total_xp = 95`.
7. **Alakasız güncelleme etkilenmiyor:** sadece `last_seen_at` UPDATE'i `total_xp`'ye dokunmuyor.

Çalıştırma: `mvn verify` (failsafe eklentisi IT'leri koşuyor). Docker yoksa yerel PostgreSQL 16 ile tam suite koşulabiliyor.

---

## 6. Riskler ve karşı önlemler

| Risk | Şiddet | Karşı önlem |
|---|---|---|
| **Eşik tablosu iki yerde** (Java + SQL) — biri değişip diğeri değişmezse sessiz sapma | Yüksek | Test #1 bunu derhal yakalar. Ayrıca SQL dosyasına ve `LEVEL_THRESHOLDS` üstüne karşılıklı yorum notu düşülür. |
| **Bayat entity yazması:** oyuncu online iken level elle değiştirilirse, o oyuncunun açık transaction'ındaki `Account` nesnesi eski `total_xp`'yi taşır; sonraki kayıt manuel düzenlemeyi ezer | Orta | `Account`'ta `@Version` (optimistic locking) yok, dolayısıyla DB bunu tespit edemez. Pratik önlem: **düzenlemeyi oyuncu offline iken yap** (`last_seen_at`'e bak) ve düzenleme sonrası oyuncudan uygulamayı yeniden açmasını iste. Kalıcı çözüm (`@Version` eklemek) tüm yazma yollarını ve retry davranışını etkilediği için ayrı bir iş olarak değerlendirilmeli. |
| Trigger'ın oyun akışına karışması | Düşük | `WHEN` koşulu yapısal olarak engelliyor; testler #3 ve #4 bunu doğruluyor. |
| İstemci eski level'i cache'liyor | Düşük | İstemci bootstrap/wallet çağrısını yeniden yapmalı; yeni değer o çağrıda gelir. |
| Migration'ın prod'da geri alınması | Düşük | Geri alma tek satır: `DROP TRIGGER accounts_manual_level_sets_xp ON accounts;`. Fonksiyonlar veri değiştirmez, kalmaları zararsız. Migration hiçbir mevcut satıra dokunmadığı için veri kaybı riski yok. |

### Opsiyonel sertleştirme (bu işin kapsamında değil, ayrı karar)

Bu sistemde XP hiçbir zaman azalmıyor (`grantCaseOpenXp` yalnızca ekliyor). Aynı trigger'a "`total_xp` geriye gidemez" kuralı eklenerek bayat yazma riski tümüyle kapatılabilir. Ancak bu, level düşürme senaryosunu da kısıtlar ve trigger'ı karmaşıklaştırır — önce ana çözüm devreye alınıp gözlemlenmesi öneriliyor.

---

## 7. Kapsam dışı

- Admin paneli veya HTTP admin endpoint'i (kimlik doğrulama katmanı gerektirir).
- `total_xp`, cüzdan, envanter, skin veya battle verisine dokunan herhangi bir değişiklik.
- Eşik tablosunun kendisinin değiştirilmesi (level 45'in kaç XP ettiği aynen kalıyor).
- Level'i doğrudan `total_xp` ile birlikte elle yazma imkânı (zaten çalışıyor, kaldırılmıyor).

---

## 8. Onaydan sonra uygulama sırası

1. `V79__manual_level_edit.sql` yazılır.
2. `V79ManualLevelEditIT` yazılır, önce kırmızı olduğu görülür.
3. `ProgressionService` javadoc'u güncellenir, `resyncDerivedFields` ve testi silinir.
4. Tam suite koşulur (`mvn verify`), 272+ testin yeşil olduğu doğrulanır.
5. Prod deploy'da Flyway V79'u otomatik uygular; sonrasında `cenk` hesabında `UPDATE accounts SET level = 45` ile uçtan uca doğrulanır.
