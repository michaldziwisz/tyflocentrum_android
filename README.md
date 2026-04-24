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
- `Release APK` pozwala ręcznie opublikować testowe wydanie jako GitHub Release,
- `Deploy Cast Receiver` publikuje opcjonalny custom Web Receiver do GitHub Pages.

Uwaga:

- workflow wydawniczy publikuje instalowalny `debug APK`,
- produkcyjne wydanie podpisane do dystrybucji w Google Play wymaga osobnej konfiguracji kluczy i podpisywania.

## Custom Cast Receiver

Aplikacja może opcjonalnie używać własnego Web Receivera dla Tyfloradia zamiast
domyślnego receivera Google. Jeśli `TYFLO_CAST_APP_ID` nie jest ustawione,
build automatycznie wraca do `DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`.

### Konfiguracja lokalna

Przed buildem ustaw `TYFLO_CAST_APP_ID`:

```properties
TYFLO_CAST_APP_ID=YOUR_RECEIVER_APP_ID
```

Możesz to dodać do:

- `gradle.properties` w projekcie,
- `~/.gradle/gradle.properties`,
- albo zmiennej środowiskowej `TYFLO_CAST_APP_ID`.

### GitHub Actions

Jeśli chcesz, aby publiczne buildy z GitHub Actions używały custom receivera,
ustaw w repozytorium zmienną `Actions` o nazwie `TYFLO_CAST_APP_ID`.

### GitHub Pages

Pliki receivera są w katalogu `cast-receiver/`. Workflow `Deploy Cast Receiver`
publikuje je do GitHub Pages, skąd można podpiąć URL do Google Cast SDK
Developer Console jako `Custom Web Receiver`.
