# Wczytywanie artykułów i stron Tyfloświata

## Zachowanie

- `ArticleDetailScreen` udostępnia „Spróbuj ponownie” po błędzie pobierania i przy pustej treści.
- Ręczne ponowienie pomija cache repozytorium i wysyła `Cache-Control: no-cache`.
- Wpisy i strony mają wspólną politykę: maksymalnie dwie próby, 12 sekund na próbę i 30 sekund na całą operację. Ponawiane są błędy transportu i wybrane przejściowe statusy HTTP, nie błędy identyfikatora, TLS/certyfikatu ani trwałe statusy HTTP.
- `Retry-After` w sekundach i jako data HTTP nie jest skracany. Opóźnienie wykraczające poza pozostały budżet kończy operację bez przedwczesnego ponowienia. Duże liczby nie przepełniają mnożenia.
- Timeout należący do pobierania daje zwykły błąd dostępny do ponowienia. Anulowanie zadania użytkownika, także przez limit czasu jego wywołania, pozostaje anulowaniem. Nie rozpoznajemy go po tekście wyjątku.
- Przed zapisem do cache sprawdzany jest identyfikator odpowiedzi. Kontrola wersji żądania i zapis odbywają się pod wspólną krótką blokadą. Starsza odpowiedź nie nadpisuje nowszego wyniku.
- Stan ekranu jest przypisany do identyfikatora artykułu i jego źródła. Anulowane zadanie nie uruchamia callbacków wyniku, błędu ani sprzątania nowszego żądania.
- Już wyświetlony, niepusty artykuł nie jest ponownie pobierany wyłącznie dlatego, że wcześniej użyto ręcznego ponowienia.

Renderer pozostaje natywnym `AccessibleHtmlText` opartym na TextView. Nie przenosimy mechanizmu WebKita z iOS. Nie zmieniamy pobierania podcastów ani powiadomień push. Po akceptacji poprawki wydanie Google Play 1.0.9 (versionCode 10) wysłano na produkcję. API potwierdziło `completed` i pełny zakres dystrybucji. Dowody oraz ograniczenia tego odczytu opisuje [raport wydania](wydanie-1.0.9.md).

## Weryfikacja

Uruchomiono pełny zestaw:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' --console=plain
```

Wynik: 41 testów, zero błędów i pominięć. W tym 17 testów repozytorium i cztery testy bezpiecznego przekazywania wyniku do ekranu. Lint: zero błędów, 17 ostrzeżeń. Zbudowano `app/build/outputs/apk/debug/app-debug.apk`, pakiet `net.tyflopodcast.tyflocentrum.debug`, wersja 1.0.8 (9). Nie instalowano ani nie publikowano APK.

Testy wywołują rzeczywiste metody repozytorium i helper wykorzystywany przez ekran. Atrapa transportu zachowuje oryginalną Continuation oraz kontekst i wirtualny zegar korutyny. Zagnieżdżony `runBlocking` nie nadaje się do tej atrapy: blokuje zegar i uniemożliwia rzetelny test timeoutu. Test wyścigu cache rzeczywiście zawiesza pierwsze pobranie przez `delay`, a test ekranu kończy stare żądanie dopiero po rozpoczęciu nowszego i sprawdza stan pośredni.

Nie wykonano testu na fizycznym urządzeniu z TalkBack ani Jeshuo. Zbudowany APK i testy JVM nie stanowią takiego pomiaru.
