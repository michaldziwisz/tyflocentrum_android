# Wydanie Google Play 1.0.9 (10)

## Stan

Po akceptacji poprawki przez Michała podpisany AAB został wysłany na ścieżkę produkcyjną Google Play. Niezależny odczyt API z 20 września 2026, 14:10:19 UTC, potwierdził:

- pakiet `net.tyflopodcast.tyflocentrum`;
- `track=production`, `name=1.0.9`, `versionCodes=[10]`;
- `status=completed` i brak `userFraction`, czyli pełny zakres dystrybucji;
- polską notatkę zmian zgodną z `fastlane/metadata/android/pl-PL/changelogs/10.txt`;
- przyjęty AAB o SHA-256 `ac41268a4d9b47d36b49948d42f9ff9c9ef3da6fa21c843bc906e29a36ac8b04`.

Odczyt nie dowodzi natychmiastowej dostępności aktualizacji na konkretnym telefonie. Sklep może potrzebować czasu na udostępnienie zmian. Edycję używaną do odczytu porzucono bez publikowania dodatkowych zmian.

## Zakres

Wydanie zawiera scaloną poprawkę ładowania artykułów z PR #2. Przygotowanie wersji zmieniło tylko numer, notatkę sklepową i dokumentację; `app/src` pozostało identyczne. Powiadomienia push nie weszły do wydania. Nie instalowano APK na telefonie ani nie modyfikowano lokalnego katalogu z niewydanymi pracami nad push.

## Dowody

- [PR #3](https://github.com/michaldziwisz/tyflocentrum_android/pull/3), commit `a48d212189085c13edd5d114cdbc699fb52acc8c`, merge `d9e548b186b5e7f4726d25ee1431aa3d62bb2292`. Drzewa commita i merge są identyczne.
- Po zmianie numeru ponownie wykonano `testDebugUnitTest`: **41 zaliczonych testów**, zero błędów i pominięć. Wyniki policzono z XML, z kontrolą unikatowości przypadków.
- [Android CI 35515104391](https://github.com/michaldziwisz/tyflocentrum_android/actions/runs/35515104391): budowa APK i lint zakończone powodzeniem. Ten workflow nie wykonuje testów jednostkowych; ich wynik pochodzi z osobnego uruchomienia lokalnego.
- [Walidacja Play 35515237137](https://github.com/michaldziwisz/tyflocentrum_android/actions/runs/35515237137): podpisany AAB, `validate_only=true`, sukces. Istniejący lane waliduje ścieżkę `internal`, ale niczego na niej nie publikuje. Po walidacji API nie zawierało jeszcze builda 10.
- [Publikacja 35515451040](https://github.com/michaldziwisz/tyflocentrum_android/actions/runs/35515451040): commit `d9e548b186b5e7f4726d25ee1431aa3d62bb2292`, `track=production`, `validate_only=false`, `release_status=completed`. Log potwierdza aktualizację ścieżki, polską notatkę i sukces wysyłki.

Przed budową API sklepu potwierdziło produkcyjną 1.0.8 (9) oraz zajęte numery 1–9. Nowy numer 10 nie kolidował z poprzednim wydaniem.

## Równoległe wydanie iOS

Sprawdzony build iOS 1.0.2 (3) zgłoszono do App Store bez przebudowy. Stan odczytu: `WAITING_FOR_REVIEW`, `releaseType=AFTER_APPROVAL`. Po akceptacji Apple publikacja nastąpi automatycznie. Szczegóły są w `docs/article-recovery-release.md` repozytorium iOS.
