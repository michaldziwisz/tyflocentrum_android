# Screenshoty do Google Play

## Minimalny zestaw

Google Play wymaga co najmniej `2` screenshotów, ale dla `Tyflocentrum` warto przygotować od razu `4` albo `5`.

## Proponowany zestaw dla `pl-PL`

- `01-nowosci-phone.png` - ekran `Nowości`
- `02-podcast-player-phone.png` - odtwarzacz podcastu
- `03-artykul-phone.png` - widok artykułu
- `04-tyfloradio-phone.png` - ekran Tyfloradia
- `05-kontakt-glosowka-phone.png` - ekran głosówki lub kontaktu tekstowego

## Jak robić screenshoty

- używaj prawdziwych screenshotów aplikacji Android,
- nie używaj screenshotów z Windows,
- nie pokazuj prywatnych danych,
- ukryj zbędne powiadomienia i elementy debugowe,
- nie dodawaj ramek urządzeń ani nakładek marketingowych,
- trzymaj spójny styl i ten sam język interfejsu.

## Zrzuty przez adb

Jeśli telefon jest podłączony przez USB i widoczny w `adb`, screenshot można zapisać bezpośrednio do katalogu sklepowego, na przykład:

```bash
adb exec-out screencap -p > store/assets/screenshots/pl-PL/01-nowosci-phone.png
```

Praktyczny flow:

1. Otwórz docelowy ekran na telefonie.
2. Upewnij się, że nie ma prywatnych danych ani powiadomień.
3. Wykonaj `adb exec-out screencap -p > <plik>.png`.
4. Powtórz dla kolejnych ekranów.

## Parametry praktyczne

- format: `PNG` albo `JPEG`
- orientacja: pion `9:16` albo poziom `16:9`
- wymiary zgodne z aktualnymi wymaganiami Play

## Kolejność rekomendowana

1. `Nowości`
2. `Podcast i odtwarzacz`
3. `Artykuł`
4. `Tyfloradio`
5. `Kontakt`
