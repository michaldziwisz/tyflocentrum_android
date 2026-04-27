# Google Play - przygotowanie publikacji

## Cel

Przygotowac pierwsza publikacje `Tyflocentrum` do Google Play tak, aby:

- store listing byl gotowy po polsku,
- aplikacja miala komplet deklaracji wymaganych przez Play Console,
- release mogl byc zbudowany jako podpisany `Android App Bundle (.aab)`,
- przed submission bylo jasne, jakie rzeczy sa juz gotowe, a jakie trzeba jeszcze domknac.

## Stan repo na 2026-04-26

### Fakty techniczne

- `applicationId`: `org.tyflocentrum.android`
- `minSdk`: `26`
- `targetSdk`: `36`
- `compileSdk`: `36`
- aktualna sciezka publikacji repo: testowe `debug APK` przez GitHub Release
- aktualna sciezka Cast: domyslny receiver Google `DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`
- repo ma juz skonfigurowany `release signing` przez lokalny `keystore.properties`
- lokalny test `bundleRelease` przechodzi poprawnie

### Aktualne uprawnienia z manifestu

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `RECORD_AUDIO`
- `WAKE_LOCK`

### Aktualne funkcje istotne dla Play Console

- brak logowania i kont uzytkownikow
- brak reklam
- brak platnosci i subskrypcji
- brak zintegrowanego FCM - ustawienia push sa na razie tylko lokalna preferencja
- mikrofon jest uzywany tylko do glosowek wysylanych dobrowolnie przez uzytkownika
- formularz kontaktu tekstowego i glosowego wysyla dane do `https://kontakt.tyflopodcast.net/`
- lokalnie przechowywane sa m.in. ulubione, szkic kontaktu, preferencje odtwarzania i pozycje wznowienia

## Co wymaga Google Play

Na podstawie oficjalnych zrodel Google, na dzien 26 kwietnia 2026:

- nowe aplikacje i aktualizacje musza targetowac co najmniej `Android 15 / API 35`,
- nowe aplikacje musza byc publikowane jako `Android App Bundle`,
- store listing musi miec co najmniej:
  - ikone aplikacji,
  - krotki opis,
  - pelny opis,
  - co najmniej 2 screenshoty,
- kazda aplikacja musi uzupelnic sekcje `Data safety`,
- aplikacja korzystajaca z danych wrazliwych lub wrazliwych uprawnien powinna miec polityke prywatnosci w Play Console i w samej aplikacji,
- trzeba uzupelnic `App content`, w tym m.in.:
  - polityke prywatnosci,
  - informacje o reklamach,
  - ankiete klasyfikacji wiekowej,
  - ewentualne instrukcje dostepu dla reviewerow,
  - sekcje `Data safety`.

## Najwazniejsze blokery przed pierwsza publikacja

### 1. Trzeba zachowac i zabezpieczyc upload key

Repo ma juz technicznie gotowy podpisany `release .aab`, ale przed publikacja trzeba bezpiecznie przechowac lokalny upload keystore i jego hasla.

W repo jest tylko konfiguracja:

- [keystore.properties.example](/mnt/d/projekty/tyflocentrum_android/keystore.properties.example)
- `app/build.gradle.kts` czytajacy lokalny `keystore.properties`

Sekrety i realny keystore nie powinny trafiac do Git.

### 2. Brak finalnej ikony aplikacji

W [AndroidManifest.xml](/mnt/d/projekty/tyflocentrum_android/app/src/main/AndroidManifest.xml) aplikacja nadal uzywa systemowego `@android:drawable/sym_def_app_icon`. Przed publikacja trzeba przygotowac:

- launcher icon dla aplikacji,
- osobna ikone do store listingu `512 x 512`,
- finalny branding spójny z nazwa `Tyflocentrum`.

### 3. Brak publicznego URL polityki prywatnosci i strony wsparcia

W repo dodane sa gotowe pliki:

- [site/index.html](/mnt/d/projekty/tyflocentrum_android/site/index.html)
- [site/privacy/index.html](/mnt/d/projekty/tyflocentrum_android/site/privacy/index.html)

oraz workflow:

- [.github/workflows/deploy-site.yml](/mnt/d/projekty/tyflocentrum_android/.github/workflows/deploy-site.yml)

ale trzeba je jeszcze wystawic pod publicznym HTTPS URL, na przyklad przez GitHub Pages.

Docelowe URL-e proponowane do Play Console:

- `https://michaldziwisz.github.io/tyflocentrum_android/`
- `https://michaldziwisz.github.io/tyflocentrum_android/privacy/`

### 4. Brak linku do polityki prywatnosci wewnatrz aplikacji

To jest jeszcze do zrobienia w samym Androidzie. Dokumentacja Google dla `App content` i `User Data` wskazuje, ze przy aplikacjach korzystajacych z danych lub uprawnien wrazliwych link do polityki prywatnosci ma byc dostepny nie tylko w Play Console, ale tez w aplikacji.

W praktyce najlepiej dodac w `Ustawieniach` pozycje:

- `Polityka prywatności`
- `Wsparcie`

### 5. Brak finalnych assetow sklepowych

Repo ma juz przygotowana strukture katalogow i instrukcje, ale nie ma jeszcze:

- finalnej ikony sklepowej,
- feature graphic `1024 x 500`,
- finalnych screenshotow Androida dla `pl-PL`.

Szczegoly sa w [store/assets/README.md](/mnt/d/projekty/tyflocentrum_android/store/assets/README.md).

## Rekomendowany przebieg pierwszej publikacji

### 1. Utworz aplikacje w Play Console

- zarezerwuj nazwe `Tyflocentrum`,
- potwierdz `applicationId = org.tyflocentrum.android`,
- ustaw aplikacje jako bezplatna.

### 2. Przygotuj publiczne strony

- wystaw [site/index.html](/mnt/d/projekty/tyflocentrum_android/site/index.html) pod publicznym URL,
- wystaw [site/privacy/index.html](/mnt/d/projekty/tyflocentrum_android/site/privacy/index.html) pod publicznym URL,
- jesli trzeba, wlacz `GitHub Pages` zrodlo `GitHub Actions`,
- wpisz finalne URL-e do `store/listing-pl-PL.md`, jesli beda inne niz GitHub Pages.

### 3. Ustal kategorie sklepu

Dla `Tyflocentrum` sa dwie realne opcje:

- `Muzyka i dźwięk` - bezpieczniejsza operacyjnie, bo eksponuje podcasty, radio i odtwarzanie,
- `Wiadomości i czasopisma` - blizsza warstwie artykulow i aktualnosci, ale moze uruchomic deklaracje `News & Magazine`.

Wniosek z polityki Google:

- jesli aplikacja jest w kategorii `News & Magazine` albo opisuje sie jako `news` / `magazine`, wchodzi dodatkowa deklaracja,
- ale media apps, ktore streamuja lub oferuja mieszany zakres tresci, moga byc poza zakresem tej polityki.

Pragmatyczna rekomendacja na start:

- zaczac od `Muzyka i dźwięk`,
- nie pozycjonowac listingu jako czysta aplikacja newsowa,
- w opisie podkreslic, ze to dostep do podcastow, Tyfloradia i artykulow.

To jest wniosek z polityki i obecnego ksztaltu aplikacji, nie literalny cytat z jednego dokumentu.

### 4. Uzupelnij store listing

Gotowe teksty sa w [store/listing-pl-PL.md](/mnt/d/projekty/tyflocentrum_android/store/listing-pl-PL.md).

Do uzupelnienia w Console:

- nazwa aplikacji,
- krotki opis,
- pelny opis,
- support email,
- support URL,
- privacy policy URL,
- kategoria,
- tagi z aktualnej listy Play Console.

### 5. Przygotuj sekcje `App content`

Do przejscia:

- `Privacy policy`
- `Ads` = `No`
- `Data safety`
- `Content rating`
- `Target audience`
- `Access instructions` - jesli review wymaga wyjasnien
- opcjonalnie `News & Magazine declaration`, jesli wybierzesz taka kategorie lub taki positioning

Draft odpowiedzi dla `Data safety` jest w [store/data-safety-pl-PL.md](/mnt/d/projekty/tyflocentrum_android/store/data-safety-pl-PL.md).

### 6. Przygotuj assety

Na start potrzeba minimum:

- ikona `512 x 512 PNG`,
- feature graphic `1024 x 500 PNG/JPEG`,
- minimum 2 screenshoty.

Repo ma przygotowane opisy i katalogi:

- [store/assets/icon/README.md](/mnt/d/projekty/tyflocentrum_android/store/assets/icon/README.md)
- [store/assets/feature-graphic/README.md](/mnt/d/projekty/tyflocentrum_android/store/assets/feature-graphic/README.md)
- [store/assets/screenshots/README.md](/mnt/d/projekty/tyflocentrum_android/store/assets/screenshots/README.md)

### 7. Utrzymuj podpisywanie i `bundleRelease`

Docelowy build do Play:

```bash
./gradlew bundleRelease
```

Repo jest juz przygotowane pod ten krok. Do utrzymania zostaje:

- bezpieczne przechowywanie upload keystore,
- dodanie danych podpisu do bezpiecznej konfiguracji lokalnej lub CI,
- zweryfikowanie, ze `versionCode` rośnie przy kazdym wydaniu.

### 8. Wrzuc pierwsza wersje na `Internal testing`

To jest najbezpieczniejsza sciezka na start:

- upload `aab`,
- szybki test instalacji z Google Play,
- walidacja `Data safety`, polityki prywatnosci i store listingu,
- sprawdzenie, czy review nie ma pytan o mikrofon, radio albo kontakt z redakcja.

### 9. Dopiero potem przejdz na `Closed testing` albo `Production`

Przed publiczna publikacja warto domknac:

- review notes,
- support URL,
- finalne screenshoty,
- test instalacji z Play,
- finalna decyzje o kategorii.

## Rzeczy, ktore warto wpisac reviewerowi

Gotowy szkic jest w [store/review-notes-pl-PL.md](/mnt/d/projekty/tyflocentrum_android/store/review-notes-pl-PL.md).

Najwazniejsze rzeczy do wyjasnienia:

- aplikacja nie wymaga logowania,
- mikrofon jest opcjonalny i sluzy tylko do glosowki,
- brak reklam i zakupow,
- brak wymagania posiadania Chromecasta do podstawowego testu,
- kontakt z radiem moze zalezec od aktualnej dostepnosci audycji po stronie backendu.

## Weryfikacja przed submission

Uzyj checklisty:

- [docs/google-play-first-submission-checklist.md](/mnt/d/projekty/tyflocentrum_android/docs/google-play-first-submission-checklist.md)

## Zrodla oficjalne

- Create and set up your app: https://support.google.com/googleplay/android-developer/answer/9859152
- Prepare your app for review: https://support.google.com/googleplay/android-developer/answer/9859455
- Data safety form: https://support.google.com/googleplay/android-developer/answer/10787469
- User Data policy: https://support.google.com/googleplay/android-developer/answer/9888076
- Preview assets: https://support.google.com/googleplay/android-developer/answer/1078870
- Preview assets details and dimensions: https://support.google.com/googleplay/android-developer/answer/9866151
- Target API requirements: https://support.google.com/googleplay/android-developer/answer/11926878
- Android App Bundles in Play: https://support.google.com/googleplay/android-developer/answer/9859152
- News & Magazine declaration: https://support.google.com/googleplay/android-developer/answer/16189314
- News & Magazines policy: https://support.google.com/googleplay/android-developer/answer/15909032
- Requirements for news and magazine apps: https://support.google.com/googleplay/android-developer/answer/10523915
