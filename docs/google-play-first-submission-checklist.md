# Google Play - checklista pierwszej publikacji

## 1. Tozsamosc aplikacji

- [ ] nazwa `Tyflocentrum` jest zarezerwowana w Play Console
- [ ] `applicationId` w repo zgadza sie z wpisem w Play Console: `net.tyflopodcast.tyflocentrum`
- [ ] aplikacja jest ustawiona jako bezplatna
- [ ] `versionCode` zostal zwiekszony na wersje publikacyjna

## 2. Techniczne readiness

- [ ] skonfigurowano keystore release
- [ ] skonfigurowano podpisywanie `release`
- [ ] `./gradlew bundleRelease` przechodzi lokalnie lub w CI
- [ ] testowy `aab` daje sie wrzucic do `Internal testing`
- [ ] aplikacja ma finalna launcher icon, nie systemowe `sym_def_app_icon`
- [ ] target API pozostaje zgodny z aktualnym wymogiem Play

## 3. Listing sklepu

- [ ] uzupelniono tytul aplikacji
- [ ] uzupelniono krotki opis
- [ ] uzupelniono pelny opis
- [ ] uzupelniono support email
- [ ] uzupelniono support URL
- [ ] uzupelniono privacy policy URL
- [ ] wybrano finalna kategorie
- [ ] uzupelniono tagi z aktualnej listy Play Console

## 4. Assety

- [ ] ikona sklepu `512 x 512 PNG`
- [ ] feature graphic `1024 x 500`
- [ ] minimum 2 screenshoty telefonu
- [ ] screenshoty nie zawieraja prywatnych danych
- [ ] screenshoty nie zawieraja debug overlay, powiadomien ani ramek urzadzenia
- [ ] screenshoty sa rzeczywistymi zrzutami Androida, nie Windowsa

## 5. Prywatnosc i polityki

- [ ] polityka prywatnosci jest publicznie dostepna pod HTTPS
- [ ] polityka prywatnosci wymienia aplikacje lub podmiot z listingu Play
- [ ] polityka prywatnosci opisuje formularz tekstowy, glosowki i dane lokalne
- [ ] w aplikacji jest link do polityki prywatnosci
- [ ] `Data safety` wypelniono na podstawie realnego zachowania aplikacji i backendu
- [ ] `Ads` ustawiono na `No`, jesli to nadal prawda
- [ ] wypelniono `Content rating`
- [ ] wypelniono `Target audience`

## 6. Data safety - szczegolnie do potwierdzenia

- [ ] potwierdzono, czy tekst kontaktowy deklarowac jako `Messages`
- [ ] potwierdzono, czy pole `nick lub imie` deklarowac jako `Name`
- [ ] potwierdzono, ze glosowki deklarowane sa jako `Audio files`
- [ ] potwierdzono, jakie dane backend kontaktowy przechowuje i jak dlugo
- [ ] potwierdzono, czy trzeba oferowac procedure usuniecia danych wyslanych przez formularz kontaktowy

## 7. Review notes

- [ ] przygotowano notatki dla review
- [ ] opisano, ze logowanie nie jest wymagane
- [ ] opisano, ze mikrofon jest opcjonalny
- [ ] opisano, ze brak Chromecasta nie blokuje review
- [ ] opisano, ze kontakt z radiem zalezy od dostepnosci po stronie backendu

## 8. Kategoria i polityka News & Magazine

- [ ] podjeto swiadoma decyzje o kategorii
- [ ] jesli wybrano `Wiadomości i czasopisma`, sprawdzono czy aplikacja wpada w deklaracje `News & Magazine`
- [ ] jesli aplikacja jest in-scope, wypelniono deklaracje `News & Magazine`

## 9. Test koncowy przed wysylka

- [ ] dziala odtwarzanie podcastow
- [ ] dziala Tyfloradio
- [ ] dziala formularz tekstowy
- [ ] dziala glosowka po nadaniu zgody na mikrofon
- [ ] dziala wysylka komentarzy i czytanie komentarzy
- [ ] TalkBack przechodzi glowne sciezki bez krytycznych problemow
- [ ] build z Play instaluje sie i uruchamia poprawnie
