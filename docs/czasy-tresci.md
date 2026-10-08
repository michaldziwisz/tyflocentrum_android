# Czasy treści na listach

Informacja przy dacie i w pojedynczej dostępnej nazwie wiersza obejmuje Nowości,
katalog podcastów i artykułów, ich kategorie, wyniki wyszukiwania, ulubione oraz
artykuły numerów TyfloŚwiata. Nie zmienia klucza wiersza ani akcji otwarcia,
odtwarzania, kopiowania i ulubionych. Numery czasopisma, PDF, tematy i linki
nie otrzymują czasu całej publikacji.

## Źródła i zachowanie

Tyflopodcast dostarcza opcjonalny obiekt `tyflocentrum` w istniejącym listowym
WP REST. Dla starszych ulubionych i cache aplikacji odczytywane jest wyłącznie
`id,modified_gmt,tyflocentrum` z batch `include`. Ułamkowe sekundy są zaokrąglane
w górę. Akceptowana jest wersja 1, status ready i zakres (0, 2147483647].

TyfloŚwiat korzysta z `https://tyflocentrum.tyflo.eu.org/v1/metadata`.
Osobne klucze źródło/typ/ID rozdzielają posts i pages. Partia zawiera do 50
unikalnych dodatnich ID. Odpowiedź jest dopasowywana po ID, nigdy po kolejności;
zduplikowany rekord zostaje odrzucony. Czytanie wymaga fresh, schema_version 1,
text_status ready oraz zgodności reading_minutes z ceil(word_count/200).
Brak `modified_gmt` w context=embed jest dozwolony. Znana nowsza data źródłowa
unieważnia poprzedni czas, także gdy ekran Nowości zachowuje obiekt wiersza.

`ContentTimeStore` przechowuje do 512 wyników w pamięci procesu. Dodatnie czasy
czytania wygasają po 24 godzinach od checked_at, a audio najpóźniej po 24 godzinach
od odpowiedzi WP. Nie są odmładzane przez odczyt starego ulubionego. Cache nieznanego
wyniku trwa minutę. Aktywne partie są deduplikowane. Własny timeout 2,5 s oznacza
brak czasu; anulowanie wywołującego jest propagowane. Brak automatycznych retry.
Żaden GET metadanych nie blokuje pierwszego renderowania listy, artykułu ani audio.

Efekt Compose działa na całej liście, nie pojedynczym wierszu. Wygaszenie aktualizuje
etykietę również bez przewijania. Zegar nie ponawia żądań; ponowny odczyt następuje
przy zmianie listy, jej unieważnieniu lub wznowieniu ekranu. Powrót z tła uwzględnia
bieżący zegar. Nie zmieniono istniejącej polityki odświeżania treści. Unieważnienie
usuwa tokeny poprzednich partii, więc ich późne odpowiedzi nie nadpisują nowych.

Nie ma migracji DataStore, usuwania ani przestawiania ulubionych. Opcjonalne pola
JSON są zachowane tolerancyjnie, lecz stara zapisana wartość nie otrzymuje nowego
terminu ważności tylko dlatego, że odczytano ulubione.

## Weryfikacja

`./gradlew testDebugUnitTest compileDebugKotlin lintDebug assembleDebug`
sprawdza pełny kod i testy. `./gradlew connectedDebugAndroidTest` mierzy prawdziwe
węzły Compose: pojedynczą etykietę na klikalnym wierszu, zachowane akcje, stabilny
identyfikator węzła po uzupełnieniu oraz dostępną listę podczas oczekiwania na metadane.
Instrumentacja używa zwykłej Application, bez inicjalizacji Cast/odtwarzacza.
Nie jest to pomiar fizycznego TalkBacka lub Jeshuo. Takiego testu nie wykonano.

Wspólne fixture zawierają 50 przypadków. Dodatkowe testy obejmują transport Retrofit
z rejestrem URL i atrapą HTTP, 503/429/uszkodzony JSON, typy opcjonalnych obiektów,
anulowanie, timeout, wyścig z odświeżeniem, TTL, pojemność cache oraz stare ulubione.
Rejestr URL list nie zawiera pobierania pełnych tekstów ani audio.
