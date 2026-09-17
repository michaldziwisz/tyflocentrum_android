package net.tyflopodcast.tyflocentrum.core

/**
 * Wynik scalenia swiezo pobranej pierwszej strony z lista, ktora uzytkownik czyta.
 */
data class WynikScalenia<T>(
    val elementy: List<T>,
    /** Liczba wpisow, ktorych wczesniej NIE bylo na liscie. */
    val liczbaNowych: Int,
    /** Klucz elementu, ktory przed scaleniem byl pierwszy - do zakotwiczenia listy. */
    val kotwica: String?
) {
    val maNowe: Boolean get() = liczbaNowych > 0
}

/**
 * Reguly scalania - jedyne miejsce, w ktorym decydujemy, co zobaczy (i uslyszy)
 * osoba czytajaca liste TalkBackiem, wiec musi dac sie sprawdzic testem bez UI.
 *
 * ZASADA NADRZEDNA: nie ruszamy tego, co uzytkownik ma pod palcem. Nowe wpisy trafiaja
 * NAD dotychczasowe, a kolejnosc i tozsamosc istniejacych elementow zostaje bez zmian.
 * Dlatego scalanie NIE sortuje calosci od nowa: pelne przesortowanie moglo wstawic wpis
 * w SRODEK czytanej listy (artykul datowany wczesniej niz ostatnio wczytane pozycje),
 * a to dla czytnika ekranu jest gorsze niz brak nowosci - element pod kursorem
 * zmienilby sasiedztwo.
 */
object ScalanieNowosci {

    fun <T> scal(
        biezace: List<T>,
        swieze: List<T>,
        identyfikator: (T) -> String,
        komparator: Comparator<T>
    ): WynikScalenia<T> {
        val kotwica = biezace.firstOrNull()?.let(identyfikator)

        // Pierwsze wejscie: nie ma czego scalac ani czego chronic.
        if (biezace.isEmpty()) {
            return WynikScalenia(
                elementy = swieze.sortedWith(komparator),
                liczbaNowych = 0,
                kotwica = null
            )
        }

        val znaneId = biezace.mapTo(HashSet(), identyfikator)
        val nowe = swieze.filter { identyfikator(it) !in znaneId }

        if (nowe.isEmpty()) {
            return WynikScalenia(elementy = biezace, liczbaNowych = 0, kotwica = kotwica)
        }

        // Wewnatrz samej porcji nowych trzymamy porzadek malejaco po dacie, ale
        // nie dotykamy porzadku reszty listy.
        val noweUporzadkowane = nowe.sortedWith(komparator)

        return WynikScalenia(
            elementy = noweUporzadkowane + biezace,
            liczbaNowych = noweUporzadkowane.size,
            kotwica = kotwica
        )
    }

    /**
     * Komunikat dla czytnika ekranu, z polska odmiana liczebnika.
     *
     * Tekst jest czescia dostepnosci, nie kosmetyka: „Nowe tresci: 1” brzmi jak surowy
     * log, a nie jak zdanie.
     */
    fun komunikatONowych(liczba: Int): String? {
        if (liczba <= 0) return null
        val rzeczownik = when {
            liczba == 1 -> "nowa treść"
            liczba % 100 in 12..14 -> "nowych treści"
            liczba % 10 in 2..4 -> "nowe treści"
            else -> "nowych treści"
        }
        return "$liczba $rzeczownik na górze listy"
    }
}
