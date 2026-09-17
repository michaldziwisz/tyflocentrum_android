package net.tyflopodcast.tyflocentrum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regula progowa odswiezania i scalanie nowosci.
 *
 * KAZDA asercja pozytywna ma pare negatywna - bez tego test przechodzilby takze
 * wtedy, gdyby regula zawsze zwracala „tak” (albo zawsze „nie”), czyli nie mierzylby
 * niczego.
 */
class StrategiaOdswiezaniaTest {

    private val teraz = 1_000_000_000L

    // --- Prog swiezosci ---

    @Test
    fun `swieze dane nie generuja ruchu`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(ostatniSukcesMs = teraz - 10_000, ostatniaProbaMs = teraz - 10_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertFalse("Dane sprzed 10 s sa swieze - pobranie marnowaloby bateria", wynik)
    }

    @Test
    fun `stare dane generuja odswiezenie`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(ostatniSukcesMs = teraz - 121_000, ostatniaProbaMs = teraz - 121_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Dane starsze niz prog maja byc odswiezone", wynik)
    }

    @Test
    fun `granica progu jest wlaczna`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(
                ostatniSukcesMs = teraz - StrategiaOdswiezania.PROG_SWIEZOSCI_MS,
                ostatniaProbaMs = teraz - StrategiaOdswiezania.PROG_SWIEZOSCI_MS
            ),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Dokladnie na progu odswiezamy", wynik)
    }

    // --- Karencja po bledzie: sedno ochrony baterii ---

    @Test
    fun `po nieudanej probie obowiazuje karencja`() {
        // Dane stare (bo pobranie sie nie udalo), ale proba byla 5 s temu.
        // Bez tej reguly kazde przelaczenie aplikacji przy braku sieci strzelaloby
        // nowym zadaniem dobijajacym do timeoutu.
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(ostatniSukcesMs = teraz - 3_600_000, ostatniaProbaMs = teraz - 5_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertFalse("5 s po nieudanej probie nie ponawiamy - to byla petla zadan", wynik)
    }

    @Test
    fun `po karencji proba jest ponawiana`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(ostatniSukcesMs = teraz - 3_600_000, ostatniaProbaMs = teraz - 31_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Po karencji wolno sprobowac ponownie", wynik)
    }

    @Test
    fun `pusty ekran bez sieci nie bombarduje serwera`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(ostatniSukcesMs = null, ostatniaProbaMs = teraz - 2_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertFalse(wynik)
    }

    @Test
    fun `brak jakiejkolwiek proby pozwala pobrac`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.POWROT_Z_TLA,
            stan = StanSwiezosci(),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Pierwsze wejscie musi pobrac dane", wynik)
    }

    // --- Zadanie uzytkownika omija progi ---

    @Test
    fun `zadanie uzytkownika omija prog swiezosci`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.ZADANIE_UZYTKOWNIKA,
            stan = StanSwiezosci(ostatniSukcesMs = teraz, ostatniaProbaMs = teraz),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Gdy czlowiek sam odswieza, czekanie jest wylacznie szkoda", wynik)
    }

    @Test
    fun `zadanie uzytkownika omija karencje po bledzie`() {
        val wynik = StrategiaOdswiezania.czyOdswiezyc(
            powod = PowodOdswiezenia.ZADANIE_UZYTKOWNIKA,
            stan = StanSwiezosci(ostatniSukcesMs = null, ostatniaProbaMs = teraz - 1_000),
            trwaPobieranie = false,
            teraz = teraz
        )
        assertTrue("Przycisk Odswiez nie moze byc gluchy po nieudanej probie", wynik)
    }

    @Test
    fun `trwajace pobranie blokuje kazdy powod`() {
        for (powod in PowodOdswiezenia.entries) {
            val wynik = StrategiaOdswiezania.czyOdswiezyc(
                powod = powod,
                stan = StanSwiezosci(),
                trwaPobieranie = true,
                teraz = teraz
            )
            assertFalse("Drugie rownolegle zadanie ($powod) nic nie wnosi", wynik)
        }
    }

    // --- Znaczniki swiezosci ---

    @Test
    fun `nieudana proba nie odmierza wieku danych od nowa`() {
        // Gdyby proba zerowala wiek danych, nieudane pobranie „odmlodziloby” stare
        // tresci i zablokowalo kolejne odswiezenie na dwie minuty.
        val stan = StanSwiezosci()
            .zSukcesem(teraz - 300_000)
            .zProba(teraz)

        assertEquals(300_000L, stan.wiekDanychMs(teraz))
        assertEquals(teraz, stan.ostatniaProbaMs)
    }

    @Test
    fun `sukces odmierza wiek od nowa`() {
        val stan = StanSwiezosci()
            .zSukcesem(teraz - 300_000)
            .zSukcesem(teraz)

        assertEquals(0L, stan.wiekDanychMs(teraz))
    }
}

class ScalanieNowosciTest {

    private data class Wpis(val id: String, val kolejnosc: Int)

    private val komparator = compareByDescending<Wpis> { it.kolejnosc }

    private fun scal(biezace: List<Wpis>, swieze: List<Wpis>) =
        ScalanieNowosci.scal(biezace, swieze, { it.id }, komparator)

    @Test
    fun `nowe wpisy laduja na gorze`() {
        val wynik = scal(
            biezace = listOf(Wpis("b", 2), Wpis("c", 1)),
            swieze = listOf(Wpis("a", 3), Wpis("b", 2))
        )

        assertEquals(listOf("a", "b", "c"), wynik.elementy.map { it.id })
        assertEquals(1, wynik.liczbaNowych)
        assertEquals("b", wynik.kotwica)
    }

    @Test
    fun `brak nowych nie rusza listy ani nie oglasza`() {
        val biezace = listOf(Wpis("b", 2), Wpis("c", 1))
        val wynik = scal(biezace = biezace, swieze = listOf(Wpis("b", 2)))

        assertEquals("Lista bez nowosci musi zostac ta sama", biezace, wynik.elementy)
        assertEquals(0, wynik.liczbaNowych)
        assertFalse(wynik.maNowe)
        assertNull("Bez nowosci NIE oglaszamy nic", ScalanieNowosci.komunikatONowych(wynik.liczbaNowych))
    }

    @Test
    fun `kolejnosc istniejacych elementow nie zmienia sie`() {
        // Wpis „z” ma date ze srodka listy, ale skoro uzytkownik juz czyta te liste,
        // nie wolno go wcisnac w srodek - trafia na gore razem z reszta nowych.
        val biezace = listOf(Wpis("b", 5), Wpis("c", 1))
        val wynik = scal(biezace = biezace, swieze = listOf(Wpis("z", 3)))

        assertEquals(listOf("z", "b", "c"), wynik.elementy.map { it.id })
        assertEquals(
            "Ogon listy musi pozostac nietkniety, bo tam siedzi kursor czytnika",
            biezace,
            wynik.elementy.drop(1)
        )
    }

    @Test
    fun `pusta lista biezaca przyjmuje calosc uporzadkowana`() {
        val wynik = scal(
            biezace = emptyList(),
            swieze = listOf(Wpis("c", 1), Wpis("a", 3))
        )

        assertEquals(listOf("a", "c"), wynik.elementy.map { it.id })
        assertEquals("Pierwsze wypelnienie listy to nie nowosci do ogloszenia", 0, wynik.liczbaNowych)
        assertNull(wynik.kotwica)
    }

    // --- Komunikat dla czytnika ekranu ---

    @Test
    fun `odmiana liczebnika po polsku`() {
        assertEquals("1 nowa treść na górze listy", ScalanieNowosci.komunikatONowych(1))
        assertEquals("3 nowe treści na górze listy", ScalanieNowosci.komunikatONowych(3))
        assertEquals("5 nowych treści na górze listy", ScalanieNowosci.komunikatONowych(5))
        assertEquals("12 nowych treści na górze listy", ScalanieNowosci.komunikatONowych(12))
        assertEquals("22 nowe treści na górze listy", ScalanieNowosci.komunikatONowych(22))
    }

    @Test
    fun `zero nowych nie daje komunikatu`() {
        assertNull(ScalanieNowosci.komunikatONowych(0))
    }
}
