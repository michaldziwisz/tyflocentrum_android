package net.tyflopodcast.tyflocentrum.core

/**
 * Powod, dla ktorego pytamy o odswiezenie listy.
 *
 * Rozroznienie jest istotne, bo wymagania sa sprzeczne: automat MUSI oszczedzac
 * bateria, a jawne zadanie uzytkownika ma zadzialac zawsze i natychmiast.
 */
enum class PowodOdswiezenia {
    /** Aplikacja wrocila na wierzch (Lifecycle.Event.ON_RESUME). */
    POWROT_Z_TLA,

    /** Uzytkownik wszedl na ekran, ktory byl juz wypelniony danymi. */
    WEJSCIE_NA_EKRAN,

    /** Jawne zadanie: przycisk „Odswiez”. */
    ZADANIE_UZYTKOWNIKA
}

/**
 * Znaczniki czasu jednego strumienia danych.
 *
 * DWA znaczniki, nie jeden - i to jest sedno ochrony baterii. Pierwsza wersja tej
 * reguly patrzyla wylacznie na wiek ostatniego UDANEGO pobrania. Wygląda poprawnie
 * i jest pulapka: przy martwej sieci znacznik sukcesu nigdy sie nie odswieza, wiec
 * KAZDY powrot do aplikacji wystrzeliwal kolejne zadanie dobijajace do timeoutu.
 * Regula majaca chronic bateria zamieniala slaby zasieg w petle nieudanych polaczen.
 */
data class StanSwiezosci(
    val ostatniSukcesMs: Long? = null,
    val ostatniaProbaMs: Long? = null
) {
    fun zProba(teraz: Long): StanSwiezosci = copy(ostatniaProbaMs = teraz)

    fun zSukcesem(teraz: Long): StanSwiezosci =
        copy(ostatniSukcesMs = teraz, ostatniaProbaMs = teraz)

    fun wiekDanychMs(teraz: Long): Long? = ostatniSukcesMs?.let { teraz - it }
}

/**
 * Czysta, bezstanowa regula progowa - swiadomie wydzielona z warstwy Compose, bo
 * „kiedy wolno pobrac” jest decyzja produktowa, a nie szczegolem UI, i musi dac sie
 * zmierzyc testem jednostkowym bez uruchamiania urzadzenia ani emulatora.
 */
object StrategiaOdswiezania {
    /**
     * Minimalny wiek danych, przy ktorym automat siega do sieci.
     *
     * 120 s to kompromis: krotkie przelaczenie do innej aplikacji (odczyt kodu 2FA,
     * odpisanie na wiadomosc) nie generuje ruchu, a tresci w tych serwisach pojawiaja
     * sie w odstepach godzinowych, wiec dwie minuty opoznienia sa niewidoczne.
     */
    const val PROG_SWIEZOSCI_MS: Long = 120_000

    /** Karencja po nieudanej probie - chroni przed petla zadan przy slabej sieci. */
    const val PROG_PO_BLEDZIE_MS: Long = 30_000

    fun czyOdswiezyc(
        powod: PowodOdswiezenia,
        stan: StanSwiezosci,
        trwaPobieranie: Boolean,
        teraz: Long,
        progSwiezosciMs: Long = PROG_SWIEZOSCI_MS,
        progPoBledzieMs: Long = PROG_PO_BLEDZIE_MS
    ): Boolean {
        // Drugie rownolegle zadanie nie doda informacji, a zaplaci bateria
        // i ryzykiem wyscigu o stan listy.
        if (trwaPobieranie) return false

        // Czlowiek nacisnal - zadne progi go nie dotycza.
        if (powod == PowodOdswiezenia.ZADANIE_UZYTKOWNIKA) return true

        val sukces = stan.ostatniSukcesMs
        val proba = stan.ostatniaProbaMs

        // Nigdy nic nie pobrano: jedynym ogranicznikiem jest karencja po bledzie,
        // bo bez niej pusty ekran bez sieci bombardowalby serwer przy kazdym powrocie.
        if (sukces == null) {
            return proba == null || teraz - proba >= progPoBledzieMs
        }

        // Dane swieze - nie ruszamy sieci, niezaleznie od liczby powrotow.
        if (teraz - sukces < progSwiezosciMs) return false

        // Dane stare, ale ostatnia proba mogla wlasnie polec. Odczekaj.
        if (proba != null && teraz - proba < progPoBledzieMs) return false

        return true
    }
}
