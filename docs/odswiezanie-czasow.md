# Odświeżanie metadanych bez restartu

## Przyczyna i granice

Nowości już na bazie 672dc55c uruchamiały unieważnienie metadanych przy ręcznym odświeżaniu. Nie należy przenosić na Androida diagnozy iOS polegającej na braku takiego sygnału. Potwierdzone regresjami JVM problemy Androida to wyczyszczenie dobrej etykiety przed odpowiedzią oraz możliwość nadpisania nowego czasu przez spóźnioną odpowiedź inline z paginacji. Na pozostałych ekranach nie było ręcznej akcji aktualizującej metadane, a powrót z tła ponownie korzystał z dodatniego cache do upływu TTL.

## Rozwiązanie

* Wspólny przycisk „Odśwież” na liście podcastów i artykułów (także kategorii), wyszukiwaniu, ulubionych oraz spisie artykułów numeru aktualizuje wyłącznie czasy już obecnej listy. Nie przestawia wyników, filtrów, wierszy ani odtwarzacza. Nowości zachowują swój istniejący przycisk odświeżający listę.
* Cache jest oznaczany nową generacją zamiast usuwany. W trakcie żądania zostają dobre etykiety; poprawna odpowiedź z brakiem lub błędnymi metadanymi usuwa czas. Awaria transportu nie jest wycofaniem informacji i nie odnawia jej TTL.
* Bilet generacji jest pobierany przed HTTP listy, a nie po jego zakończeniu. Numery operacji chronią także przed późną odpowiedzią inline po nowszym pobraniu partiami. Nieaktualna operacja nie rozpoczyna dalszych porcji.
* Jeden efekt Compose obsługuje listę. Zegar tylko wygasza etykiety. Wejście i powrót mogą ponownie sprawdzić dodatni wynik po 120 s; brak lub błąd zachowuje dotychczasową karencję 60 s. Ręczna akcja omija te cache, ale nie Retry-After. Nie zmieniono 24-godzinnego TTL informacji ani walidatorów treści.
* HTTP 429/503 w metadanych wstrzymuje kolejne porcje tego źródła co najmniej na 60 s albo do późniejszego Retry-After. To ograniczenie jest wspólne dla maksymalnie trzech źródeł; nie wprowadzono automatycznej pętli ponowień. Chroni także inne porcje po odpowiedzi bez nagłówka lub z minioną datą.
* Globalny cache pozostaje ograniczony do 512 rekordów. Ekran zachowuje rekordy wyłącznie własnej załadowanej listy; usunięcie z cache nie wygasza aktualnego czasu widocznego wiersza. Te rekordy zapobiegają ponownemu pobraniu wypartego cache przy szybkim powrocie. Porcje nadal mają maksymalnie 50 ID.

## Testy

`ContentTimeRefreshRegressionTest`: dwa testy RED na bazie i GREEN po naprawie, w tym rzeczywisty Retrofit/OkHttp oraz opóźniona odpowiedź inline.

`ContentTimeRefreshStoreTest`: jawne fazy missing, ready, nowsze ready, błędne i wycofane; trzy źródła, stałe ID/modified_gmt, progi wieku, zachowanie TTL po błędzie, Retry-After sekundy/data, spóźnione porcje, anulowanie, szybkie odświeżenia, 620 rekordów przy cache 512 i odpowiedź inline po nowym batch.

`ContentTimeRefreshScreenTest`: rzeczywiste ekrany aplikacji, singleton AppContainer/DataStore/odtwarzacz w procesie testowym, ten sam ekran/repo/store wewnątrz każdego scenariusza. Podmieniane są wyłącznie repo i store na te same klasy z kontrolowanym transportem HTTP; test nie kontaktuje się z produkcją. Stan serwera zmienia jawna faza, nie licznik żądań. Kliknięcie odświeżania idzie przez Android AccessibilityNodeInfo.ACTION_CLICK. Mierzone są etykiety wizualne, nazwy AX, tożsamość wiersza Compose i AX, akcje, kotwica pierwszego wiersza oraz brak TYPE_ANNOUNCEMENT. Przy ręcznym odświeżeniu sprawdzany jest także fokus dostępności.

Powroty używają ActivityScenario CREATED/RESUMED bez recreate, z przesunięciem zegara klienta metadanych. System może po ON_STOP nadać inne window ID, dlatego po powrocie sprawdzana jest stałość wiersza Compose i nowa nazwa AX, a nie równość całego AccessibilityNodeInfo ze starego okna. Test nie jest pomiarem fizycznego TalkBacka/Jeshuo ani odtwarzania rzeczywistego dźwięku.

Pełne polecenie runnera:

    ./gradlew --no-daemon --continue testDebugUnitTest compileDebugKotlin assembleDebug lintDebug assembleDebugAndroidTest
    ./gradlew --no-daemon connectedDebugAndroidTest

Nie uruchamiać instalacji testów na telefonie użytkownika. Instrumentacja przeznaczona jest dla izolowanego emulatora CI.
