
## Odświeżanie treści po powrocie aplikacji z tła

Zgłoszenie: „po zrzuceniu do app switchera i wywołaniu stamtąd czasem nie dociąga
nowych treści”.

Przyczyna: ekran Nowości nie miał żadnej reakcji na powrót aplikacji na wierzch.
`LaunchedEffect(Unit)` nie powtarza się po powrocie z app switchera (kompozycja nie
została zniszczona), a strażnik `items.isEmpty()` i tak nic by nie pobrał, bo dane
były. Do tego cache ekranowy w `TyfloRepository` (singleton żyjący tyle, co proces)
nie miał żadnego znacznika czasu, więc raz zapisana lista wyglądała na wieczną.
Stąd „czasem”: gdy system ubił proces w tle, zimny start pokazywał świeże treści.

Co się zmieniło:

- `StrategiaOdswiezania` — próg 120 s liczony od wieku danych **oraz** karencja 30 s
  po nieudanej próbie. Dwa znaczniki, nie jeden: przy martwej sieci sam wiek
  ostatniego *sukcesu* nigdy się nie odświeża, więc każdy powrót do aplikacji
  strzelałby żądaniem dobijającym do timeoutu. Żądanie użytkownika omija oba progi.
- `ScalanieNowosci` — nowe wpisy dokleja NAD dotychczasowe, bez sortowania całości od
  nowa i bez ruszania kolejności istniejących elementów. Po scaleniu `scrollToItem`
  przywraca dokładnie tę pozycję, na której stał użytkownik (indeks przesunięty o
  liczbę nowych wpisów, z zachowanym offsetem), żeby TalkBack nie zgubił miejsca.
- Ogłoszenie „N nowych treści na górze listy” idzie po scaleniu z opóźnieniem 1,2 s —
  w chwili powrotu do aplikacji TalkBack ogłasza swoje rzeczy i natychmiastowy
  komunikat zostałby zagłuszony. Brak nowości = brak ogłoszenia.
- **Przycisk „Odśwież”** w pasku ekranu Nowości. Wcześniej ekran nie miał ŻADNEJ drogi
  odświeżenia (ani gestu, ani przycisku), więc po nieudanym zimnym starcie nie było
  jak ponowić. Przycisk jest ważniejszy od gestu pociągnięcia, bo gest bywa dla
  czytnika ekranu trudny, a przycisk znajduje się zwykłym przeglądaniem.
- `NewsScreenCache` przenosi znaczniki świeżości razem z danymi, czyli działa jako
  „pokaż natychmiast, potem dociągnij”, a nie „pokaż i pomiń pobranie”.
- Odświeżenie pierwszej strony wysyła `Cache-Control: no-cache` (parametr
  `pomijCache`). Nagłówek NIE jest przypięty na stałe w `@Headers`: serwisy nie
  wysyłają ETag ani Last-Modified, więc cache i tak idzie do sieci, a stały nagłówek
  zablokowałby odpowiedzi 304, gdyby walidatory kiedyś się pojawiły.

Dowody: `app/src/test/kotlin/.../core/StrategiaOdswiezaniaTest.kt` — 18 asercji, każda
pozytywna z parą negatywną. Zmierzone: 20 testów, 0 błędów, 0 pominiętych. Kontrola
ważności: cofnięcie karencji po błędzie wywala dokładnie 2 asercje, więc test nie jest
atrapą.
