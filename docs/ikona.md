# Ikona aplikacji — wspólna identyfikacja wizualna z wersją Windows

Ikona TyfloCentrum na Androida pochodzi z ikony wersji Windows
(`TyfloCentrumWindows`, plik `src/TyfloCentrum.Windows.App/Assets/AppIcon.ico`),
żeby obie aplikacje wyglądały jak jedna rodzina. W repozytorium Windows nie ma
źródła wektorowego, więc materiałem wyjściowym jest największy dostępny kafel
rastrowy: `Assets/Square310x310Logo.png`.

## Co składa się na ikonę

| plik | rola |
|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` | ikona adaptacyjna (Android 8+) |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | wariant okrągły, ta sama treść |
| `res/values/ic_launcher_colors.xml` | granat tła `#02091D` z wersji Windows |
| `res/mipmap-*/ic_launcher_foreground.png` | symbol na przezroczystym tle, warstwa 108 dp |
| `res/mipmap-*/ic_launcher_monochrome.png` | sylwetka symbolu pod Material You (Android 13+) |
| `res/mipmap-*/ic_launcher.png` | bitmapa zapasowa dla launcherów bez ikon adaptacyjnych |
| `res/mipmap-*/ic_launcher_round.png` | jak wyżej, obcięta do koła |
| `store/assets/icon/tyflocentrum-play-icon-512.png` | ikona do Google Play |

Tło jest kolorem, nie bitmapą — jednolity granat nie potrzebuje pliku na każdą
gęstość i nie ma na czym stracić jakości.

## Dlaczego symbol jest mniejszy niż na Windows

Android pokazuje z warstwy 108 dp tylko środek: maska systemowa wycina 72 dp,
a gwarantowany dla każdego kształtu maski (również okrągłej) jest **okrąg**
o średnicy 66 dp. Symbol z Windows zajmuje 75 procent szerokości kafla, więc
przeniesiony 1:1 zostałby przycięty. Dlatego w warstwie `foreground` ograniczamy
**przekątną** symbolu do średnicy strefy gwarantowanej — po skalowaniu zajmuje
około 49 procent szerokości warstwy i nie jest obcinany na żadnym launcherze.

Bitmapy zapasowe (`ic_launcher.png`) zachowują natomiast proporcje 1:1 z Windows:
granatowy squircle plus symbol na 75 procentach szerokości. Tam maski systemowej
nie ma, więc nie ma czego chronić.

## Odtworzenie ikony

Wymagane: `pillow`, `numpy`, `scipy`.

```bash
python3 tools/wyodrebnij_symbol.py   # kafel Windows -> symbol z kanałem alfa
python3 tools/generuj_warstwy.py     # symbol -> wszystkie warstwy i gęstości
python3 tools/test_ikony.py          # weryfikacja plików, kod wyjścia 1 gdy błąd
python3 tools/test_rekonstrukcji.py  # czy symbol wiernie odtwarza kafel Windows
```

Po zbudowaniu APK warto sprawdzić także to, co realnie zostało spakowane:

```bash
unzip -qo app/build/outputs/apk/debug/app-debug.apk 'res/mipmap-*' -d /tmp/apk_check
python3 tools/sprawdz_apk.py
```

`wyodrebnij_symbol.py` wymaga repozytorium `TyfloCentrumWindows` obok tego
projektu (ścieżka na początku pliku). Jeśli go nie ma, symbol jest już zapisany
w `tools/symbol_zrodlowy.png` i wystarczą dwa pozostałe kroki.

## Weryfikacja

`tools/test_ikony.py` sprawdza rozmiary warstw, mieszczenie się treści w strefie
gwarantowanej, jednolitość koloru warstwy monochrome, krycie bitmap zapasowych
oraz to, że `ic_launcher.png` i `ic_launcher_round.png` **nie są identyczne**
(przed tą zmianą były bajt w bajt takie same, czyli wariant okrągły nie istniał).

Test ma trzy kontrole ważności — celowo błędne dane muszą go oblać. Bez nich
„zielony" wynik nie znaczyłby nic. Wykrył realnie dwa błędy w generatorze:
wpisanie symbolu w kwadrat zamiast w okrąg strefy oraz dziurę w kanale alfa
kafla po `paste()` z maską.

Czego test **nie** sprawdza: jak ikona wygląda na konkretnym launcherze i czy
kontrast symbolu wobec tła jest wystarczający po pokolorowaniu przez Material
You. To wymaga urządzenia.

## Ikona w karcie sklepu to OSOBNY zasób — wydanie wersji jej nie zmienia

Najważniejsza pułapka tego obszaru, złapana dzień po wydaniu 1.0.7. Ikona
aplikacji istnieje w Google Play w **dwóch niezależnych miejscach**:

| gdzie widać | skąd pochodzi | co ją aktualizuje |
|---|---|---|
| pulpit telefonu, lista aplikacji | `res/mipmap-*` w APK/AAB | wydanie nowej wersji |
| karta sklepu, wyniki wyszukiwania w Play | grafika 512x512 w listingu | **wyłącznie** `edits.images` |

Wydanie 1.0.7 podmieniło pierwszą i nie tknęło drugiej, bo `fastlane/Fastfile`
ma `skip_upload_images: true`. Skutek dla użytkownika: w sklepie i na karcie
aplikacji stara ikona, mimo że po instalacji na pulpicie jest nowa. Zgłoszenie
brzmi wtedy „ikona się nie zmieniła", choć wydanie było poprawne.

Stan listingu odczytujemy i zmieniamy osobno:

```bash
python3 tools/stan_ikony_listingu.py                      # co wisi w sklepie (tylko odczyt)
python3 tools/wgraj_ikone_listingu.py <plik.png>          # PRÓBA, nic nie zatwierdza
python3 tools/wgraj_ikone_listingu.py <plik.png> --zapisz # zapis do karty sklepu
```

`--zapisz` jest wymagane świadomie: karta sklepu jest widoczna publicznie, więc
przypadkowe uruchomienie narzędzia nie może jej zmienić. Po zapisie narzędzie
czyta stan z **nowej** edycji, bo odczyt z edycji, która zapisywała, pokazałby
zamiar, a nie skutek.

Dowodem zmiany jest suma SHA-256 grafiki zwracana przez API (oraz nowy adres
`lh3.googleusercontent.com`), nie sam brak błędu przy wysyłce.

Wniosek na przyszłość: po każdej zmianie identyfikacji wizualnej sprawdź OBA
miejsca. „Ikona wydana" bez odczytu listingu jest twierdzeniem o połowie zasobów.
