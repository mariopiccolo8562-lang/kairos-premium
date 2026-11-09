package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: prima l'indirizzo dei download (oggi il pannello), GitHub solo come riserva se il pannello fallisce */
class UpdFeedTest {
    private val gh = "https://github.com/mariopiccolo8562-lang/kairos-premium/releases/latest/download/kairos-premium.json"
    private val panel = "https://panel-kairos.com/get/premium.json"

    @Test
    fun oggiPannelloPoiGitHub() {
        // l'indirizzo dei download oggi è il pannello: il secondo tentativo, uguale al primo, si salta
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com", "premium", gh))
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com/", "premium", gh))
    }

    @Test
    fun dominioDedicatoPrimaDelPannello() {
        assertEquals(listOf("https://dl.example.com/get/premium.json", panel, gh), UpdFeed.urls("https://dl.example.com/", "premium", gh))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf(panel), UpdFeed.urls("", "premium", ""))
        assertEquals(listOf(panel, gh), UpdFeed.urls("http://dl.example.com", "premium", gh))
    }

    @Test
    fun pannelloCheRispondeGitHubNonSiChiede() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "premium", gh)) { asked.add(it); "pannello" }
        assertEquals("pannello", r)
        assertEquals(listOf(panel), asked) // nessun controllo in più del solito
    }

    @Test
    fun pannelloConErroreRispostaNonValidaOMutoSiUsaGitHub() {
        // null = errore HTTP (es. 502) o file non valido; eccezione = il pannello non risponde (rete, tempo scaduto)
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("tempo scaduto") })
        for (fail in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "premium", gh)) { u -> asked.add(u); if (u == gh) "github" else fail(u) }
            assertEquals("github", r)
            assertEquals(listOf(panel, gh), asked)
        }
    }

    @Test
    fun dominioDedicatoGiuRispondeIlPannello() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://dl.example.com", "premium", gh)) { u -> asked.add(u); if (u == panel) "pannello" else null }
        assertEquals("pannello", r)
        assertEquals(listOf("https://dl.example.com/get/premium.json", panel), asked)
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        // pannello e GitHub non rispondono (o repository privato): come una rete giù, si riprova più tardi
        assertNull(UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "premium", gh)) { null })
    }

    @Test
    fun numeroDellaVersione() {
        assertEquals(10701, UpdFeed.code("1.7.1", 0))
        assertEquals(10701, UpdFeed.code("1.7.1", 99))
        assertEquals(10701, UpdFeed.code("", 10701))
        assertEquals(7, UpdFeed.code("1.7", 7))
    }

    @Test
    fun fileValido() {
        assertTrue(UpdFeed.valid(10701, "https://panel-kairos.com/get/premium.apk"))
        assertFalse(UpdFeed.valid(0, "https://panel-kairos.com/get/premium.apk"))
        assertFalse(UpdFeed.valid(10701, "http://panel-kairos.com/get/premium.apk"))
        assertFalse(UpdFeed.valid(10701, ""))
    }
}
