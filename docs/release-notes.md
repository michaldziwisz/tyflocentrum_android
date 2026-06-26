# Release notes — Tyflocentrum

Notatki wersji do Google Play. Format gotowy do wklejenia w Play Console
(„Informacje o tej wersji") albo automatycznego odczytu z
`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.

Limit Google Play: 500 znaków na język.

---

## 1.0.6 (versionCode 7)

### pl-PL (do wklejenia / fastlane changelogs/7.txt)

Ta wersja skupia się na dostępności dla czytników ekranu.

• Pola i przyciski w całej aplikacji są teraz poprawnie odczytywane przez czytniki ekranu — zarówno TalkBack, jak i Jeshuo. Wcześniej część etykiet (np. pól kontaktu, głosówki, komentarzy) była pomijana przez Jeshuo.
• Tyfloradio na żywo: czytnik nie odczytuje już w kółko elementu pod palcem podczas słuchania.

Dziękujemy za zgłoszenia. Piszcie, jeśli coś jeszcze nie jest dostępne.

### Wariant krótki (gdyby trzeba ciaśniej)

Poprawki dostępności: pola i przyciski są teraz poprawnie odczytywane przez czytniki ekranu (TalkBack i Jeshuo). Naprawiono też zachowanie czytnika podczas słuchania Tyfloradia na żywo.

### Co technicznie się zmieniło (kontekst, NIE do sklepu)

- Pola tekstowe przepisane na natywny EditText (AndroidView) — nazwa pola
  trafia na właściwy węzeł dostępności, czytany przez oba czytniki.
- Wszystkie przyciski/elementy klikalne dostały nazwę wprost na klikalnym
  węźle (helper `Modifier.semanticButton`), bo czytniki nie-scalające drzewa
  (Jeshuo) czytały dotąd pusty węzeł.
- Zakładki nawigacji, chipy filtrów i przełączniki: dodany stan
  (zaznaczone / włączone) oraz rola.
- Tyfloradio na żywo: `PlayerController` nie aktualizuje już pozycji co 500 ms
  dla streamu live, co eliminowało ciągłe rekompozycje i powtarzany odczyt.
- Zweryfikowane zrzutami drzewa dostępności na urządzeniu pod Jeshuo i TalkBack.

---

## Jak to wgrać

1. Plik wydania: `app/build/outputs/bundle/release/app-release.aab`
   (versionName 1.0.6, versionCode 7, podpisany kluczem upload).
2. Play Console → wybrana ścieżka → Utwórz nowe wydanie → prześlij `.aab`.
3. W polu „Informacje o tej wersji" wklej tekst z sekcji pl-PL powyżej
   (albo pozostaw fastlane, jeśli używasz automatu).
