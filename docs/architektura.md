# Architektura aplikacji Tyflocentrum Android

Dokument dla deweloperów. Opisuje strukturę kodu, przepływ danych i kluczowe
decyzje projektowe. README.md opisuje aplikację od strony użytkownika i wydania,
ten plik - od środka.

## Stos technologiczny

- Język: Kotlin, UI w Jetpack Compose (Material 3).
- Min SDK 26, target/compile SDK 36, JDK 17.
- `applicationId` i `namespace`: `net.tyflopodcast.tyflocentrum`
  (UWAGA: katalogi źródeł leżą pod `org/tyflocentrum/android`, ale **pakiety w
  kodzie to `net.tyflopodcast.tyflocentrum.*`** - ścieżka katalogu nie odpowiada
  pakietowi).
- Sieć: Retrofit 3 + OkHttp 5 (BOM) + kotlinx.serialization (JSON).
- Odtwarzanie: AndroidX Media3 (ExoPlayer + HLS + Cast + Session + Transformer).
- Cast: Google Play Services Cast Framework + mediarouter.
- Trwałe ustawienia: DataStore Preferences.
- Parsowanie HTML (treści WordPress): jsoup.
- Brak DI frameworka - ręczny kontener `AppContainer`.
- Brak ViewModeli - ekrany trzymają stan przez `remember`/`collectAsState`
  bezpośrednio na repozytorium i kontrolerach z `AppContainer`.

## Wejście i cykl życia

- `TyflocentrumApplication` (Application): inicjalizuje
  `Cast.getSingletonInstance(this).initialize()` oraz tworzy `AppContainer`.
- `MainActivity` (FragmentActivity - nie ComponentActivity, bo Cast/mediarouter
  tego wymaga): `enableEdgeToEdge()`, ustawia motyw `TyflocentrumTheme` i osadza
  `TyflocentrumApp(appContainer)`.
- `AppContainer` (core/AppContainer.kt): ręczny graf zależności. Buduje OkHttp z
  cache 20 MB, trzy instancje Retrofit i serwisy:
  - `podcastApi` -> `https://tyflopodcast.net/wp-json/` (WpApiService)
  - `articleApi` -> `https://tyfloswiat.pl/wp-json/` (WpApiService)
  - `contactApi` -> `https://kontakt.tyflopodcast.net/` (ContactApiService)
  Eksponuje `repository`, `preferencesRepository`, `playerController`,
  `castDiagnostics`.

## Warstwy

### core/model
- `Models.kt`: modele WordPress (`WpPostSummary`, `WpPostDetail`,
  `WpRenderedText` z leniwym `plainText` przez jsoup), komentarze (`Comment`,
  `ThreadedComment`, wątkowanie `toThreadedComments()` - sort rosnąco po dacie,
  odpowiedzi pod rodzicami), `Category`, ulubione (`FavoriteItem` jako
  `@Serializable sealed class`: Podcast/Article/Topic/Link), ustawienia
  (`AppSettings`, `PushPreferences`), modele odtwarzacza (`PlayerRequest`,
  `PlayerUiState`), znaczniki/odnośniki/wersje tekstowe show-notes.
  classDiscriminator JSON = `"type"`.
- `ContentParsing.kt`: czyste obiekty parsujące (testowalne bez Androida):
  - `SearchRanking` - ranking wyników wyszukiwania (normalizacja PL, tokeny).
  - `ShowNotesParser` - z komentarzy wyłuskuje "Znaczniki czasu" (kody
    HH:MM:SS / MM:SS -> `ChapterMarker`) i "Odnośniki/Linki" (`RelatedLink`,
    także `mailto:`). Własny mini-parser HTML zachowujący podziały linii.
  - `TextVersionParser` - znajduje link do tekstowej wersji audycji
    (`/tekstowe-wersje-audycji/`, page_id albo slug).
  - `MagazineParser` - numer/rok wydania TyfloŚwiata, pierwszy PDF, kolejność
    spisu treści wg kolejności linków w treści wydania.
  - `PlaybackRatePolicy` - dozwolone prędkości 1x..3x, normalizacja/cykl.

### core/network
- `TyfloRepository.kt`: jedyny punkt dostępu do danych + bogate **cache w
  pamięci** (mapy per ekran/typ/kategoria/id; komentarze, liczniki, show-notes,
  wersje tekstowe, wydania). Wzorzec `peek*` (z cache) / `store*` / `fetch*`.
  - `WpApiService` - REST WP v2 (posts, pages, categories, comments).
  - `ContactApiService` - `kontakt.tyflopodcast.net/json.php` (dostępność TP,
    ramówka radia, kontakt tekstowy i głosowy multipart).
  - **Publikacja komentarza** (`publishComment`) NIE idzie przez REST (wymaga
    auth), tylko emuluje **legacy formularz WordPress**: pobiera stronę wpisu,
    czyta `form#commentform` + nonce Akismet, POST bez podążania za przekierowa-
    niem, interpretuje 302/treść jako sukces/moderacja/spam. To celowy hack pod
    publiczne komentarze bez logowania.

### core/playback (serce aplikacji, najbardziej złożone)
- `PlayerController.kt` (~1000 linii): łączy `ExoPlayer` (lokalny) i
  `RemoteCastPlayer` w jeden `CastPlayer`, wystawia `MediaSession` i
  `StateFlow<PlayerUiState>`. Obsługuje: wznawianie pozycji (DataStore),
  prędkość (globalnie/per-odcinek), zapis pozycji co 5 s, media buttony.
  - **Live (Tyforadio) traktowane inaczej niż VOD**: pauza live = STOP (nie
    pauza), prędkość zawsze 1x, brak przycisków przewijania, MIME HLS.
  - **Cast live handoff/recovery**: rozbudowana maszyna stanów na wypadek, gdy
    sesja Cast wstaje wolno albo po starcie odtwarzania. `PendingLiveHandoff`
    (kolejkuje przeniesienie na odbiornik, max wiek 20 s), `scheduleLiveCast
    Recovery` (retry z backoffem 0/0.75/2/5/15/30 s), `CastStartupTrace`
    (telemetria etapów startu Cast). To odpowiedź na realne problemy z
    odbiornikami (patrz historia commitów: 1412f61, 046fda8, 1fc025a, 884077c).
- `LiveAwareMediaItemConverter.kt`: dla pozycji live przebudowuje `MediaInfo` na
  `STREAM_TYPE_LIVE` z osobnym URL/MIME do Cast (extras `cast_stream_url`/
  `cast_mime_type`); stały URL dla Cast: `https://radio.tyflopodcast.net/`.
- `CastOptionsProvider.kt`: domyślny odbiornik medialny Google
  (`DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`). Zarejestrowany w manifeście jako
  OPTIONS_PROVIDER. Wcześniej istniał opcjonalny custom receiver - uproszczono
  (commit e62845f).
- `CastDiagnosticsLogger.kt`: ring-buffer 500 wpisów, **tylko w buildach DEBUG**;
  eksportowalny snapshot. To źródło logów typu `grzegorz_log.txt` w repo
  (diagnostyka Cast na realnym sprzęcie, np. LG webOS TV).
- `PlaybackService.kt`: `MediaSessionService` (foreground, mediaPlayback),
  współdzieli jedną `MediaSession` z `PlayerController`.

### core/recording
- `VoiceRecorderController.kt`: nagrywanie wiadomości głosowych (MediaRecorder,
  AAC/MP4 mono 44.1k), limit 20 min, dogrywanie kolejnych segmentów i ich
  **łączenie przez Media3 Transformer**, podgląd (MediaPlayer), wysyłka jako
  multipart do `contactApi.sendVoiceContact`.

### core/storage
- `AppPreferencesRepository.kt`: DataStore (`tyflocentrum.preferences_pb`).
  Ustawienia, ulubione (JSON), wersje robocze kontaktu/komentarza, prędkość
  globalna i per-URL, pozycje wznawiania per-URL. Klucze dynamiczne (rate/resume)
  liczone jako skrót SHA-256(URL) (20 znaków hex) - by URL nie wyciekał wprost.

### ui
- `TyflocentrumApp.kt`: `NavHost` + `AppRoutes` (wszystkie trasy i buildery URL z
  enkodowaniem; pusty segment kodowany jako `__null__`). `LocalAppContainer`
  udostępnia kontener kompozytom.
- `common/CommonComposables.kt` (~1100 linii): rusztowania i komponenty
  współdzielone - `AppScreenScaffold` (z dolnym paskiem nawigacji root +
  `MiniPlayerBar`), `Announcement` (liveRegion dla TalkBack), `StatePane`
  (ładowanie/błąd/pusto), `AccessibleHtmlText`/`LinkifiedPlainText` (renderowanie
  treści WP w sposób dostępny, parsowanie bloków HTML), `ContentListItem`,
  `CastRouteButton`, `ToggleRow`, `FilterChipRow`.
- `screens/`: 21 ekranów w 3 plikach:
  - `FeedScreens.kt`: News, PodcastsHome, ArticlesHome, PodcastList, ArticleList,
    Search, Magazine.
  - `DetailScreens.kt`: PodcastDetail, PodcastTextVersion, ArticleDetail,
    PodcastComments, Favorites, Settings.
  - `PlayerAndContactScreens.kt`: RadioHome, Player, PlayerChapterMarkers,
    PlayerRelatedLinks, MagazineIssue, ContactMenu, ContactText, ContactVoice.
- `theme/Theme.kt`: motyw Material 3.

## Dostępność (cel nadrzędny projektu)
- Aplikacja jest budowana pod TalkBack/osoby niewidome (autor jest niewidomy).
- Wzorce: jawne `contentDescription`/`semantics`, `liveRegion` dla ogłoszeń,
  `heading()`, `stateDescription`, dedykowane etykiety akcji (np. "Pauza"/
  "Odtwarzaj" na ikonie mini-playera), oddzielne ekrany znaczników/odnośników
  zamiast gęstych widoków. Najwięcej semantyki: ekrany odtwarzacza/kontaktu i
  CommonComposables. Przy każdej zmianie UI sprawdzać etykiety pod czytnik.

## Testy
- `app/src/test` (JUnit4, czysta JVM): `CommentThreadingTest`,
  `ShowNotesParserTest`. Testują logikę z `core/model` bez Androida.
- Brak testów instrumentalnych mimo skonfigurowanego runnera.

## Build, CI, wydania
- `./gradlew assembleDebug`, `./gradlew lintDebug`. Debug APK:
  `app/build/outputs/apk/debug/app-debug.apk`.
- Podpis release: opcjonalny `keystore.properties` w root (storeFile/store
  Password/keyAlias/keyPassword). Bez niego release nie jest podpisany.
- GitHub Actions: Android CI (build+lint), Release APK (ręczne wydanie debug
  APK jako GitHub Release), Deploy Site (Pages: strona wsparcia + polityka
  prywatności).
- Materiały Google Play: docs/google-play-*.md, store/*, site/privacy/.
- Wersja (build.gradle.kts): versionCode 6 / versionName 1.0.5 (stan przy
  pisaniu dokumentu - sprawdzaj w pliku).

## Narzędzia pomocnicze
- `tools/cast-probe/`: lokalna strona HTML do diagnozy strumienia Tyforadia
  (MP3/HLS, HEAD/GET/Range, hls.js) bez fizycznego Chromecasta.
- `cast-receiver/`: katalog (obecnie pusty) - placeholder po uproszczeniu
  custom receivera do domyślnego.

## Backendy zewnętrzne (źródła prawdy)
- `tyflopodcast.net/wp-json` - podcasty (posts) + strony (pages, wersje
  tekstowe), komentarze.
- `tyfloswiat.pl/wp-json` - artykuły (posts) + czasopismo TyfloŚwiat
  (pages/`czasopismo/`).
- `kontakt.tyflopodcast.net/json.php` - dostępność Tyflocentrum, ramówka radia,
  kontakt tekstowy/głosowy.
- `radio.tyflopodcast.net` - strumień Tyforadia (HLS live; stały URL dla Cast).
- Pobieranie pliku odcinka: `tyflopodcast.net/pobierz.php?id=<id>&plik=0`.
