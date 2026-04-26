# Google Play Data safety - draft roboczy

## Uwaga

To jest roboczy draft przygotowany na podstawie aktualnego kodu aplikacji na dzien `2026-04-26`.

Nie traktuj tego jako porady prawnej. Przed submission trzeba jeszcze potwierdzic:

- realna retencje danych po stronie backendu `kontakt.tyflopodcast.net`,
- czy dane z formularza kontaktowego sa usuwane na zadanie,
- czy nazwe lub nick deklarowac jako `Name`,
- finalna interpretacje pola wiadomosci tekstowej w kategorii `Messages`.

## Co wynika z aktualnego kodu

### Dane wysylane poza urzadzenie tylko po jawnej akcji uzytkownika

- `nick lub imię` z formularza kontaktowego
- tresc wiadomosci tekstowej do Tyfloradia
- nagranie glosowe wysylane jako glosowka
- czas trwania glosowki

### Dane przechowywane tylko lokalnie na urzadzeniu

- ulubione
- kontaktowy szkic wiadomosci
- zapamietany nick
- pozycje wznowienia odtwarzania
- preferencje predkosci
- lokalne preferencje push

### Dane niewidoczne jako automatycznie wysylane w aktualnym kodzie

- brak logowania i kont
- brak analityki
- brak reklam
- brak lokalizacji
- brak ksiazki adresowej
- brak identyfikatorow reklamowych
- brak FCM i push tokenow

## Proponowany punkt startowy do formularza

### Czy aplikacja zbiera dane?

`Tak`

Uzasadnienie:

- wysylka formularza tekstowego i glosowego przesyla dane poza urzadzenie.

### Czy aplikacja udostepnia dane stronom trzecim?

Punkt startowy:

- `Nie`, jesli liczysz backend kontaktowy jako wlasna infrastrukture uslugi
- `Do potwierdzenia`, jesli backend kontaktowy formalnie nalezy do innego podmiotu niz deweloper widoczny w Play Console

To wymaga decyzji wlasciciela produktu i zgodnosci z finalna polityka prywatnosci.

## Dane, ktore najpewniej trzeba zadeklarowac

### 1. Name

Proponowany status:

- `Collected`

Powod:

- formularz prosi o `nick lub imię`,
- ta wartosc jest wysylana na backend kontaktowy jako `author`.

### 2. Messages

Proponowany status:

- `Collected`

Powod:

- formularz tekstowy wysyla tresc wiadomosci jako `comment`.

### 3. Audio files

Proponowany status:

- `Collected`

Powod:

- glosowka jest nagrywana lokalnie i wysylana jako plik audio.

## Dane, ktorych na dzis nie widac jako wymagajace deklaracji off-device

- favorites
- playback positions
- playback rate preferences
- push preferences
- cast diagnostics log eksportowany recznie przez uzytkownika

Te rzeczy sa przechowywane lokalnie albo udostepniane tylko przez jawna akcje systemowego `share`, a nie przez automatyczny upload aplikacji.

## Bezpieczenstwo i praktyki

### Czy dane sa szyfrowane w transmisji?

Punkt startowy:

`Tak`

Uzasadnienie:

- kod korzysta z endpointow `https://`.

### Czy uzytkownik moze zazadac usuniecia danych?

Punkt startowy:

- `Do potwierdzenia`

Aplikacja sama nie ma kont i nie przechowuje serwerowego profilu uzytkownika, ale trzeba ustalic procedure dla:

- tekstow wyslanych do radia,
- glosowek,
- ewentualnych danych kontaktowych zapisanych po stronie backendu.

## Co sprawdzic recznie przed zapisaniem formularza

- czy backend kontaktowy archiwizuje imie/nick i tresc wiadomosci
- czy backend przechowuje pliki glosowe stale czy tymczasowo
- czy podmiot publikujacy aplikacje jest tym samym podmiotem, ktory odbiera dane kontaktowe
- czy w finalnym UI aplikacji jest link do polityki prywatnosci

## Powiazane pliki

- [docs/google-play-release.md](/mnt/d/projekty/tyflocentrum_android/docs/google-play-release.md)
- [store/review-notes-pl-PL.md](/mnt/d/projekty/tyflocentrum_android/store/review-notes-pl-PL.md)
- [site/privacy/index.html](/mnt/d/projekty/tyflocentrum_android/site/privacy/index.html)
