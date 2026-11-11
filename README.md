# Kairos Premium

App IPTV **nativa in stile Netflix** per **Fire TV, Android TV, Google TV e telefoni**, pensata per essere **fluida anche sulle chiavette datate** (da Android 5.1).
Logo **oro** su nero. Si installa **accanto** a Kairos Pro, Kairos TV e Kairos Lite (pacchetto `com.kairos.premium`) e usa lo **stesso pannello Kairos**: stessi clienti, stesso codice di attivazione `KX..-....`.

![logo](docs/logo-512.png)

## Antibuffering

- La qualità si adatta da sola alla rete quando il flusso ne offre più di una: sale solo dopo 10 s stabili, scende subito e usa il 70% della banda misurata (60% sulle reti lente).
- L'app ricorda la banda del dispositivo e i blocchi recenti: ogni visione parte già con la qualità e la scorta giuste.
- Dopo un blocco riparte solo con una scorta vera (da 2,5 a 8 s, in base ai blocchi recenti): niente stop-and-go.
- Sulle reti lente la scorta minima passa da 15 a 30 s, fino a 90 s sui film.
- I canali Xtream provano prima m3u8 e poi ts; quando il flusso cade si ricollegano da soli.

## Perché è nativa

La prima versione era una pagina web dentro l'app (come Kairos Pro) e sulle chiavette si bloccava. Questa è scritta con le viste Android classiche, come Kairos Lite (stesso motore, già collaudato sulle chiavette vecchie), ma con la grafica stile Netflix:
- liste su disco (SQLite): anche 100.000 titoli non pesano sulla memoria;
- ogni riga legge solo i suoi primi 20 titoli e solo quando sta per comparire;
- immagini caricate alla misura mostrata, in pausa mentre si scorre col tasto tenuto premuto;
- **modalità leggera automatica** sulle chiavette con poca memoria (niente zoom, immagini più piccole);
- nessuna WebView, nessuna animazione, nessuna sfocatura; l'anteprima dei canali parte solo con OK.

## Cosa c'è

- **Home stile Netflix**: schede in alto (Home, Film, Serie TV, In diretta, La mia lista), riquadro grande con un titolo in evidenza (fisso), righe di locandine: Continua a guardare, La mia lista, aggiunti di recente, categorie. «Vedi tutti» in fondo a ogni riga apre la categoria intera.
- **Film** e **Serie TV** con tutte le categorie a righe, scheda del titolo, stagioni ed episodi, episodio successivo automatico.
- **TV in diretta** con guida adesso/dopo e **anteprima leggerissima**: **OK una volta** il canale parte nel riquadro (versione più leggera del canale, al massimo 480p), **OK di nuovo** si apre lo schermo intero. Mentre scorri non parte niente. Si spegne da Impostazioni → Anteprima canali.
- Ricerca, La mia lista (tieni premuto OK su una locandina), profili (anche bambini).
- Lettore **ExoPlayer** con qualità adattiva.
- Dal pannello: attivazione e scadenza, playlist M3U/Xtream, cambio DNS, *Disconnetti*, visioni contemporanee, permessi, profili, collegamento di altre TV, messaggi, VPN, *Errori applicazione*. In *Utenti online* la versione compare come `tv 2.0.0`.
- **Aggiornamenti automatici** dalle release di questo repository.

Non ci sono, di proposito: VLC, registrazioni, multi-schermo, speed test, assistente IA, add-on, trailer.

## Adulti fuori dalla Home

Voce `adult` del controllo remoto (spunta del pannello «Nascondi gli adulti dalla Home»), uguale in tutte le app Kairos:
- Diversa da `on`, anche assente o vuota: nella Home non compare niente per adulti. Spariscono le righe delle categorie per adulti (anche se messe in primo piano dal pannello) e, in ogni riga (riquadro in evidenza, Continua a guardare, La mia lista, Top 10, aggiunti di recente, categorie), i titoli delle categorie per adulti o nascoste e quelli con un nome per adulti. Il riquadro in evidenza sceglie un altro titolo; una riga rimasta vuota non si vede.
- Negli elenchi (Film, Serie TV, TV in diretta, menu delle categorie) le categorie per adulti vanno in fondo, come quelle nascoste, e restano raggiungibili. Ricerca, guida TV e zapping non cambiano; il PIN del controllo genitori resta com'è.
- `on`: tutto come prima, vale solo la lista «Categorie nascoste dalla Home» (`home.hide`). Quella lista vale sempre, in aggiunta al riconoscimento automatico.

Riconoscimento in `AdultFilter.kt` (normalizzazione NFKD e le due espressioni del contratto comune: «Adult Swim» resta, per i titoli contano solo le parole forti, quindi «Sex Education» resta), regole in `HomeCfg.kt`. Prove: `AdultFilterTest` (i 94 esempi di `src/test/resources/adult-vectors.json`) e `HomeCfgTest`. Ogni categoria si riconosce una volta sola (cache per nome), i titoli solo quando si carica una riga della Home; niente lavoro in più durante la visione.

## Riquadro della qualità nel lettore

Voce `qbox` del controllo remoto, uguale in tutte le app Kairos, per il riquadro in alto a destra dei canali in diretta («FHD · 1080p · 50 fps»: ~6 s all'apertura del canale, ~4 s a ogni cambio di risoluzione e sempre con la barra informazioni):
- `off`: il riquadro non compare mai, come con il permesso «resInd» del cliente bloccato.
- `noq`: il riquadro resta senza la sigla di qualità: «1080p · 50 fps».
- `noq` e `off`: anche l'etichetta in cima alla barra informazioni perde la sigla («LIVE» invece di «FHD · LIVE»; su film e serie sparisce).
- `full`, vuota, assente o qualsiasi altro valore: come prima.

Il permesso «resInd» bloccato lo nasconde sempre. Non cambiano i nomi dei canali, gli avvisi, la qualità adattiva, e i dati mandati al pannello (risoluzione e FPS in «Online ora»). Una voce cambiata durante la visione vale dal prossimo aggiornamento del riquadro. Regole in `QBox.kt`, prove in `QBoxTest`.

## Messa in funzione (una volta sola)

1. **Il repository deve essere pubblico**: altrimenti Downloader e l'aggiornamento automatico non possono scaricare l'APK.
2. **Chiave di firma**: in *Settings → Secrets and variables → Actions → New repository secret* crea `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS` (file `LEGGIMI-SEGRETI.txt`).
   > ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti.
3. Facoltativo, in *Variables*: `TV_APP_NAME` (nome sotto l'icona, predefinito «Kairos TV»), `KAIROS_SERVER_URL` (se il pannello cambia indirizzo; predefinito quello su workers.dev), `KAIROS_DL_BASE` (indirizzo dei download e degli aggiornamenti; predefinito `https://store-k.download`).

## Pubblicare una versione

**Da sola:** cambia il numero in `versione.txt` su `main` (per esempio da `1.7.4` a `1.7.5`). La compilazione di quella modifica crea il tag `v1.7.5` e pubblica la release, senza passare da GitHub. La regola sta in `.github/scripts/versione.sh` (provata a ogni compilazione da `versione_test.sh`): pubblica solo da `main` e solo se la versione è nuova e più alta dell'ultima pubblicata; con un numero non valido, più basso o già pubblicato esce solo l'APK di prova. Le altre modifiche a `main` non pubblicano nulla.

**A mano, come prima:** *Releases → Draft a new release* → tag `v2.0.0` (poi `v2.0.1`, `v2.1.0`…) → *Publish*. Dopo pochi minuti alla release sono allegati `kairos-premium.apk` e `kairos-premium.json`.

- Link fisso per **Downloader** e per lo store:
  `https://github.com/mariopiccolo8562-lang/kairos-premium/releases/latest/download/kairos-premium.apk`
- Le app installate chiedono gli aggiornamenti a `https://store-k.download/get/premium.json` (indirizzo dei download `DL_BASE` in `app/build.gradle.kts`, variabile `KAIROS_DL_BASE`), poi al pannello `https://panel-kairos.com/get/premium.json`; scaricano l'APK dal link che c'è dentro (se non riesce, dalle basi successive) e propongono l'aggiornamento da sole. Solo se nessuno dei due risponde leggono `kairos-premium.json` dell'ultima release (riserva finché il repository è pubblico).

Numerazione: correzioni sulla terza cifra, cambiamenti grossi sulla versione maggiore.

## Struttura

| Cartella | Cosa contiene |
|---|---|
| `app/src/main/java/com/kairos/tv/` | Codice: `BrowseActivity` (Home/Film/Serie stile Netflix), `LiveActivity`, `DetailActivity`, `PlayerActivity`, `Sync`/`Db` (liste su disco), `Panel` (pannello Kairos) |
| `app/src/main/res/` | Grafica: colori Netflix, layout, logo |
| `docs/` | Logo (SVG e PNG) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
