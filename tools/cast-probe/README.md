# Cast Probe

Lokalna strona do szybkiego sprawdzania zachowania strumienia Tyfloradia bez fizycznego urządzenia Cast.

## Uruchomienie

Z katalogu repozytorium:

```bash
python3 -m http.server 8123 --directory tools/cast-probe
```

Potem otwórz w przeglądarce:

```text
http://localhost:8123
```

## Co sprawdza

- odtwarzanie MP3 bezpośrednio w elemencie `audio`
- odtwarzanie HLS natywnie albo przez `hls.js`
- log zdarzeń `audio`
- podstawowe testy `HEAD`, `GET`, `Range` i playlisty HLS

## Po co

To nie emuluje pełnej sesji Chromecast, ale pomaga oddzielić:

- problemy samego strumienia
- problemy `CORS` i odpowiedzi HTTP
- problemy specyficzne dla sesji Cast
