# Tyflocentrum Android

Androidowa wersja aplikacji Tyflocentrum przygotowana na podstawie natywnej aplikacji iOS.

## Zakres

Aplikacja zawiera:

- aktualności łączące podcasty i artykuły,
- listy i kategorie podcastów,
- listy i kategorie artykułów,
- wyszukiwarkę treści,
- odtwarzacz audio z wznawianiem i zmianą prędkości,
- ulubione,
- dostęp do Tyfloradia,
- kontakt tekstowy i głosowy,
- przegląd numerów i spisu treści TyfloŚwiata.

## Dostępność

Projekt został przygotowany z naciskiem na:

- zgodność z TalkBack,
- czytelne etykiety elementów interaktywnych,
- logiczną nawigację klawiaturą i fokus,
- wygodne formularze z komunikatami błędów,
- wsparcie dla osób słabowidzących i użytkowników technologii asystujących.

## Wymagania

- JDK 17
- Android SDK z platformą 36

## Build

```bash
./gradlew assembleDebug
./gradlew lintDebug
```

APK debug:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Repozytorium zawiera workflowy GitHub Actions:

- `Android CI` uruchamia build i lint przy pushu do `main` oraz dla pull requestów,
- `Release APK` pozwala ręcznie opublikować testowe wydanie jako GitHub Release.

Uwaga:

- workflow wydawniczy publikuje instalowalny `debug APK`,
- produkcyjne wydanie podpisane do dystrybucji w Google Play wymaga osobnej konfiguracji kluczy i podpisywania.
