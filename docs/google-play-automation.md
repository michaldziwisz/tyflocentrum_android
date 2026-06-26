# Automatyczna publikacja do Google Play (fastlane + GitHub Actions)

Ten dokument opisuje JEDNORAZOWĄ konfigurację automatycznego wydawania
podpisanego AAB do Google Play. Po jej wykonaniu wydanie sprowadza się do
wypchnięcia taga `vX.Y.Z` albo kliknięcia „Run workflow" na GitHub.

Pliki w repo, które to obsługują:
- `fastlane/Appfile` — identyfikator aplikacji.
- `fastlane/Fastfile` — lane'y: `deploy`, `deploy_internal`, `validate`.
- `Gemfile` — fastlane.
- `.github/workflows/release-play.yml` — workflow CI.
- `fastlane/metadata/android/pl-PL/changelogs/<versionCode>.txt` — release notes
  czytane automatycznie (już istnieje dla versionCode 7).

---

## Co trzeba zrobić RAZ (w przeglądarce + GitHub)

### Krok 1. Service Account w Google Cloud

Service Account to „robot", w którego imieniu CI rozmawia z Google Play API.

1. Wejdź na https://console.cloud.google.com/ i zaloguj się kontem, które ma
   dostęp do Twojego konta Google Play.
2. U góry, po prawej od logo „Google Cloud", jest selektor projektu (przycisk
   z nazwą bieżącego projektu). Kliknij go → w oknie kliknij „NOWY PROJEKT"
   (prawy górny róg okna). Nazwa np. `tyflocentrum-play`. Kliknij „UTWÓRZ"
   i poczekaj, aż projekt zostanie wybrany.
3. Włącz API publikacji: w polu wyszukiwania na górze wpisz
   „Google Play Android Developer API", wejdź w pierwszy wynik, kliknij
   przycisk „WŁĄCZ" (ENABLE).
4. Utwórz konto usługi: w wyszukiwarce wpisz „Service Accounts", wejdź, kliknij
   „+ UTWÓRZ KONTO USŁUGI" (u góry). Podaj nazwę np. `play-publisher`,
   kliknij „UTWÓRZ I KONTYNUUJ", kolejne kroki (role) pomiń klikając
   „DALEJ" → „GOTOWE". (Role w Google Cloud nie są tu potrzebne — uprawnienia
   nadasz w Play Console w kroku 2.)
5. Pobierz klucz JSON: na liście kont usługi kliknij właśnie utworzone konto →
   zakładka „KLUCZE" (KEYS) → „DODAJ KLUCZ" → „Utwórz nowy klucz" → typ „JSON"
   → „UTWÓRZ". Przeglądarka pobierze plik `.json`. To jest sekret — zapisz go
   bezpiecznie, NIE wrzucaj do repo.
6. Skopiuj adres e-mail konta usługi — wygląda jak
   `play-publisher@tyflocentrum-play.iam.gserviceaccount.com`. Będzie potrzebny
   w kroku 2.

### Krok 2. Nadanie uprawnień w Google Play Console

1. Wejdź na https://play.google.com/console/ i zaloguj się.
2. W menu po lewej (na samym dole) wejdź w „Użytkownicy i uprawnienia"
   (Users and permissions).
3. Kliknij „Zaproś nowego użytkownika" (Invite new users), prawy górny róg.
4. W polu adres e-mail wklej adres konta usługi z kroku 1.6.
5. Nadaj uprawnienia. Najprościej na start: w sekcji uprawnień aplikacji wybierz
   aplikację Tyflocentrum i zaznacz uprawnienia:
   - „Wyświetlanie informacji o aplikacji" (View app information),
   - „Zarządzanie wersjami produkcyjnymi" / „Zarządzanie wersjami testowymi"
     (Manage production / testing releases) — czyli „Release to testing tracks"
     i „Release apps to production".
   Możesz nadać uprawnienia tylko dla tej jednej aplikacji (account-level nie
   jest konieczny).
6. Kliknij „Zaproś użytkownika" / „Zapisz". Konto usługi nie wymaga
   potwierdzenia mailowego — działa od razu.

UWAGA: pierwsze wydanie danej aplikacji MUSI być wgrane RĘCZNIE przez Play
Console (Google wymaga, by aplikacja istniała i miała pierwszą wersję, zanim
API może publikować). Masz już gotowy AAB 1.0.6 — wgraj go raz ręcznie na
„Testy wewnętrzne", a kolejne wydania pójdą automatem.

### Krok 3. Sekrety w GitHub

W repo na GitHub: „Settings" → w menu po lewej „Secrets and variables" →
„Actions" → przycisk „New repository secret". Dodaj kolejno (Name = dokładnie
jak niżej, Secret = wartość):

1. `PLAY_SERVICE_ACCOUNT_JSON`
   Wartość: CAŁA zawartość pobranego pliku JSON (otwórz plik, zaznacz wszystko,
   skopiuj, wklej).

2. `ANDROID_KEYSTORE_BASE64`
   Wartość: keystore zakodowany base64. Wygeneruj lokalnie w WSL:
   ```bash
   base64 -w0 keys/tyflocentrum-upload.jks
   ```
   Skopiuj cały jednolinijkowy wynik i wklej jako wartość sekretu.

3. `KEYSTORE_STORE_PASSWORD` — wartość `storePassword` z `keystore.properties`.
4. `KEYSTORE_KEY_ALIAS` — wartość `keyAlias` (czyli `upload`).
5. `KEYSTORE_KEY_PASSWORD` — wartość `keyPassword`.

---

## Jak wydawać (po konfiguracji)

### Wariant 1: bezpieczny dry-run (zalecany pierwszy raz)

GitHub → zakładka „Actions" → workflow „Release to Google Play" →
„Run workflow" → track: `internal`, „Tylko walidacja" = zaznaczone (true) →
„Run workflow". To zbuduje AAB i SPRAWDZI połączenie z Play API bez publikacji.
Zielony znaczek = wszystko gotowe.

### Wariant 2: realne wydanie z taga

1. Podbij wersję w `app/build.gradle.kts` (`versionCode` +1, `versionName`).
2. Dodaj changelog: `fastlane/metadata/android/pl-PL/changelogs/<nowy_versionCode>.txt`
   (limit 500 znaków).
3. Commit, a potem tag i push:
   ```bash
   git tag v1.0.7
   git push origin v1.0.7
   ```
   Push taga `v*` automatycznie uruchamia workflow, który publikuje na
   PRODUKCJĘ (rollout 100%). Jeśli chcesz najpierw na ścieżkę testową, użyj
   ręcznego uruchomienia (Wariant 3) z wybranym trackiem.

### Wariant 3: ręczne wydanie na wybraną ścieżkę

„Actions" → „Release to Google Play" → „Run workflow" → wybierz track
(`internal` / `alpha` / `beta` / `production`), „Tylko walidacja" = false →
„Run workflow".

---

## Bezpieczeństwo

- Keystore i klucz service accountu NIGDY nie trafiają do repo — tylko do
  GitHub Secrets. Workflow odtwarza je na czas builda i kasuje po zakończeniu.
- `.gitignore` blokuje `keys/`, `keystore.properties`, `*-play-sa.json`,
  `play-service-account*.json`.
- Trzymaj OFFLINE kopię zapasową `keys/tyflocentrum-upload.jks` i haseł. Utrata
  klucza upload = utrata możliwości aktualizacji aplikacji w sklepie.
- Domyślna ścieżka to `internal` — produkcja wymaga świadomego wyboru.
