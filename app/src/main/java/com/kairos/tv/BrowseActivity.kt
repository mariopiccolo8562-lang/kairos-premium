package com.kairos.tv

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * Home, Film, Serie TV e La mia lista in stile Netflix: riquadro grande in alto e righe di locandine.
 * Pensata per le chiavette datate:
 * - il riquadro grande è FISSO (non cambia immagine a ogni locandina: niente lavoro mentre ci si muove);
 * - ogni riga legge dal database solo i suoi primi 20 titoli, e solo quando sta per comparire;
 * - le immagini sono caricate alla misura mostrata e si fermano mentre si scorre col tasto tenuto premuto;
 * - nessuna animazione, nessuna sfocatura, nessun video di anteprima.
 */
open class BrowseActivity : BaseActivity() {
    /** "home" | "vod" | "series" | "list" */
    protected open val kind: String get() = intent.getStringExtra("kind") ?: "vod"

    private lateinit var rows: RecyclerView
    private lateinit var sync: TextView
    private val adapter = RowsAdapter()
    private val pool = RecyclerView.RecycledViewPool()
    private var hero: Db.Card? = null
    private var posterW = 0
    private var posterH = 0
    private var recentW = 0
    private var recentH = 0
    private var seq = 0
    private var loadedSig = ""
    private var built = false
    private var focusDone = false
    private val vers = HashMap<String, Int>()
    private val syncL: () -> Unit = { onSync() }

    class Row(val key: String, val title: String, val type: Int, val table: String = "", val cat: String = "") {
        @Volatile var cards: List<Db.Card>? = null
        var recent: List<JSONObject> = emptyList()
        var loading = false
        var more = false
        var count = 0
        var top = false
        /** categorie i cui titoli non entrano nella riga (nascoste dalla Home dal pannello) */
        var skip: List<String> = emptyList()
    }

    companion object {
        const val T_HERO = 0
        const val T_POSTERS = 1
        const val T_RECENT = 2
        const val LIMIT = 20
        private const val V_CARD = 10
        private const val V_MORE = 11
        private const val V_RECENT = 12
        private const val V_TOP = 13
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        clock(findViewById(R.id.clock))
        sync = findViewById(R.id.sync)
        posterW = resources.getDimensionPixelSize(R.dimen.poster_w)
        posterH = resources.getDimensionPixelSize(R.dimen.poster_h)
        recentW = resources.getDimensionPixelSize(R.dimen.recent_w)
        recentH = resources.getDimensionPixelSize(R.dimen.recent_h)
        pool.setMaxRecycledViews(V_CARD, 40)

        rows = findViewById(R.id.rows)
        rows.fast()
        // la riga selezionata non finisce mai sotto la barra delle schede (che resta sopra, semitrasparente)
        rows.setPadding(rows.paddingLeft, Ui.dp(this, 64), rows.paddingRight, rows.paddingBottom)
        rows.clipChildren = false
        rows.setItemViewCacheSize(if (App.lite) 2 else 4)
        adapter.setHasStableIds(true)
        rows.adapter = adapter

        tabs()
        findViewById<View>(R.id.search).setOnClickListener { open(SearchActivity::class.java) }
        val av = findViewById<TextView>(R.id.avatar)
        av.setOnClickListener { open(SettingsActivity::class.java) }
        av.setOnFocusChangeListener { v, has -> (v.background as? android.graphics.drawable.GradientDrawable)?.setStroke(if (has) Ui.dp(this, 3) else 0, Color.WHITE) }
        for (t in listOf("vod", "series")) vers[t] = Sync.ver(t)
        Sync.listen(syncL)
        build()
    }

    /* ---------------- schede in alto ---------------- */
    private fun tabs() {
        findViewById<TextView>(R.id.tabCats).run {
            visibility = if (kind == "list") View.GONE else View.VISIBLE
            setOnClickListener { openDrawer(this) }
            setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusFirstVisible(); true } else false }
        }
        val map = linkedMapOf(R.id.tabHome to "home", R.id.tabFilm to "vod", R.id.tabSeries to "series", R.id.tabLive to "live", R.id.tabList to "list")
        for ((id, k) in map) {
            val t = findViewById<TextView>(id)
            t.isActivated = k == kind
            t.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusFirstVisible(); true } else false
            }
            t.setOnClickListener {
                if (k == kind) { focusFirst(); return@setOnClickListener }
                when (k) {
                    "home" -> open(HomeActivity::class.java)
                    "live" -> open(LiveActivity::class.java)
                    else -> open(BrowseActivity::class.java) { putExtra("kind", k) }
                }
                // le schede non si accumulano: da Film a Serie si torna comunque alla Home con INDIETRO
                if (kind != "home") finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        fillAccount()
        Sync.start()
        showSync()
        // di ritorno: se nel frattempo è cambiato qualcosa (es. la Home dal pannello) si ricostruisce
        if (built) { if (sig() != loadedSig) build() else refreshDynamic() }
    }

    override fun onDestroy() {
        App.main.removeCallbacks(heroR)
        super.onDestroy()
        Sync.unlisten(syncL)
    }

    /** sfondo grande liberato per il lettore: torna appena la schermata si rivede (senza rifare la dissolvenza) */
    private var bgFreed: String? = null

    override fun releaseBig() {
        val url = bgUrl ?: return
        val iv = bgImg ?: return
        Ui.release(iv)
        bgFreed = url
    }

    override fun onStart() {
        super.onStart()
        val url = bgFreed ?: return
        bgFreed = null
        if (url != bgUrl) return // nel frattempo è arrivato un altro titolo in evidenza
        val iv = bgImg ?: return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val d = bgDiv()
        Ui.img(iv, url, sw / d, sh / d)
    }

    /** misura dello sfondo grande: metà schermo, un terzo in modalità leggera, un quarto in «Leggerissima» */
    private fun bgDiv(): Int = if (App.max) 4 else if (App.lite) 3 else 2

    override fun onStateChanged() {
        fillAccount()
        if (sig() != loadedSig) build()
    }

    private fun sig() = K.activeIds().joinToString(",") + "|" + K.kids + "|" + Store.profileId + "|" + HomeCfg.sig()

    private fun fillAccount() {
        val s = Panel.state ?: return
        val pr = K.profile()
        Ui.avatar(findViewById(R.id.avatar), pr?.name ?: s.name.ifEmpty { "Io" }, pr?.avatar ?: "a1", 22)
    }

    /* ---------------- aggiornamento delle liste in sottofondo ---------------- */
    private fun onSync() {
        showSync()
        var changed = false
        for (t in listOf("vod", "series")) { val v = Sync.ver(t); if (vers[t] != v) { vers[t] = v; changed = true } }
        if (changed && !isFinishing) build()
    }

    private fun showSync() {
        when {
            Sync.running -> { sync.visibility = View.VISIBLE; sync.text = "Aggiornamento delle liste… ${Sync.pct}%" }
            Sync.error.isNotEmpty() && !built -> { sync.visibility = View.VISIBLE; sync.text = "Liste non aggiornate: ${Sync.error}" }
            else -> sync.visibility = View.GONE
        }
    }

    /* ---------------- righe ---------------- */
    private fun build() {
        loadedSig = sig()
        val pls = K.activeIds()
        val kids = K.kids
        val k = kind
        val my = ++seq
        bg {
            val list = ArrayList<Row>()
            val tables = when (k) { "vod" -> listOf("vod"); "series" -> listOf("series"); else -> listOf("vod", "series") }
            // categorie e Home dal pannello (HomeCfg): i titoli delle categorie nascoste non vanno in evidenza né tra gli aggiunti di recente
            val cats = HashMap<String, List<Db.Cat>>()
            val hide = HashMap<String, List<String>>()
            if (k != "list") for (t in tables) {
                val cs = Db.cats(t, pls, kids)
                cats[t] = cs
                hide[t] = cs.filter { HomeCfg.hidden(it.name) }.map { it.id }
            }
            var h: Db.Card? = null
            if (k != "list") {
                val ft = if (k == "series") "series" else "vod"
                h = Db.featured(ft, pls, kids, hide[ft].orEmpty())
                if (h == null && k == "home") h = Db.featured("series", pls, kids, hide["series"].orEmpty())
            }
            list.add(Row("hero", "", T_HERO))
            if (k == "home") {
                val rec = Store.recent()
                if (rec.isNotEmpty()) list.add(Row("recent", "Continua a guardare", T_RECENT).also { it.recent = rec })
            }
            // categorie in primo piano (dal pannello): subito dopo «Continua a guardare», prima di tutte le altre righe
            val promoted = HashSet<String>()
            if (k == "home") for ((t, c) in HomeCfg.top(tables.flatMap { t -> cats[t].orEmpty().map { t to it } })) {
                promoted.add(c.id)
                list.add(catRow(t, c))
            }
            for (t in tables) {
                val name = if (t == "vod") "Film" else "Serie TV"
                if (Store.favs(t).isNotEmpty() || k == "list") list.add(Row("fav$t", if (tables.size > 1) "La mia lista · $name" else "La mia lista", T_POSTERS, t, Db.FAV))
            }
            if (k != "list") {
                tables.firstOrNull()?.let { t -> list.add(Row("top$t", if (t == "vod") "Top 10 · Film più nuovi" else "Top 10 · Serie più nuove", T_POSTERS, t, Db.NEW).also { it.top = true; it.skip = hide[t].orEmpty() }) }
                for (t in tables) list.add(Row("new$t", if (t == "vod") "Film aggiunti di recente" else "Serie TV aggiunte di recente", T_POSTERS, t, Db.NEW).also { it.skip = hide[t].orEmpty() })
                for (t in tables) {
                    val cs = cats[t].orEmpty()
                    // Home: le solite prime 5, senza le nascoste e senza quelle già in primo piano; Film e Serie: tutte, le nascoste in fondo
                    val show = if (k == "home") cs.filter { it.id !in promoted && !HomeCfg.hidden(it.name) }.take(5) else HomeCfg.order(cs)
                    for (c in show) list.add(catRow(t, c))
                }
            }
            // le prime righe arrivano già piene: la schermata compare completa
            list.filter { it.type == T_POSTERS }.take(3).forEach { r -> r.cards = Db.cards(r.table, r.cat, pls, kids, Store.favs(r.table), LIMIT, r.skip) }
            ui {
                if (isFinishing || my != seq) return@ui
                hero = h
                adapter.items = list
                adapter.notifyDataSetChanged()
                built = true
                showSync()
                if (!focusDone) { focusDone = true; rows.postDelayed({ if (!isFinishing) focusFirst() }, 150) }
            }
        }
    }

    /** riga di una categoria («Vedi tutti» in fondo se ha più titoli di quelli mostrati) */
    private fun catRow(t: String, c: Db.Cat) = Row("c$t:" + c.id, c.name, T_POSTERS, t, c.id).also { it.more = c.count > LIMIT; it.count = c.count }

    /** di ritorno da un film: «Continua a guardare» e La mia lista aggiornati senza ricostruire tutto */
    private fun refreshDynamic() {
        val list = adapter.items
        if (kind == "home") {
            val rec = Store.recent()
            val i = list.indexOfFirst { it.type == T_RECENT }
            if (i >= 0 && rec.isNotEmpty()) { list[i].recent = rec; adapter.notifyItemChanged(i) }
            else if ((i >= 0) != rec.isNotEmpty()) { build(); return }
        }
        list.forEachIndexed { i, r -> if (r.cat == Db.FAV) { r.cards = null; r.loading = false; adapter.notifyItemChanged(i) } }
        val hasFav = list.any { it.cat == Db.FAV }
        if (!hasFav && (Store.favs("vod").isNotEmpty() || Store.favs("series").isNotEmpty())) build()
    }

    private fun loadRow(r: Row, pos: Int) {
        if (r.loading || r.cards != null) return
        r.loading = true
        val pls = K.activeIds()
        val kids = K.kids
        val favs = Store.favs(r.table)
        val my = seq
        bg {
            val c = Db.cards(r.table, r.cat, pls, kids, favs, LIMIT, r.skip)
            ui {
                if (isFinishing || my != seq) return@ui
                r.cards = c
                r.loading = false
                val i = adapter.items.indexOf(r)
                if (i >= 0) adapter.notifyItemChanged(i)
            }
        }
    }

    /** dalle schede in alto GIÙ: al riquadro grande se si vede, altrimenti alla prima riga visibile */
    private fun focusFirstVisible() {
        val lm = rows.layoutManager as? LinearLayoutManager
        if (lm == null || lm.findFirstVisibleItemPosition() <= 0) { focusFirst(); return }
        val p = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition()
        val rh = rows.findViewHolderForAdapterPosition(p) as? RowH
        val v = rh?.list?.let { l -> (l.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.let { l.findViewHolderForAdapterPosition(it)?.itemView } ?: l.getChildAt(0) }
        if (v != null) v.requestFocus() else focusFirst()
    }

    private fun focusFirst() {
        val h = rows.findViewHolderForAdapterPosition(0)?.itemView
        val play = h?.findViewById<View>(R.id.play)
        if (play != null && play.visibility == View.VISIBLE && play.isShown) { play.requestFocus(); return }
        for (i in 1 until adapter.itemCount) {
            val v = (rows.findViewHolderForAdapterPosition(i) as? RowH)?.list?.getChildAt(0) ?: continue
            v.requestFocus(); return
        }
        findViewById<View>(if (kind == "list") R.id.tabList else R.id.tabHome).requestFocus()
    }

    /** la scheda attiva in alto (Home, Film…) prende il cursore */
    private fun focusTab() {
        val id = when (kind) { "vod" -> R.id.tabFilm; "series" -> R.id.tabSeries; "list" -> R.id.tabList; else -> R.id.tabHome }
        findViewById<View>(id).requestFocus()
    }

    /** SU dalla prima riga quando sopra non c'è il riquadro grande */
    private fun upFromRow(card: View): Boolean {
        val rowView = (card.parent as? View)?.parent as? View ?: return false
        val pos = rows.getChildAdapterPosition(rowView)
        val firstRow = adapter.items.indexOfFirst { it.type != T_HERO }
        if (pos == firstRow && hero == null) { focusTab(); return true }
        return false
    }

    /** in fondo alle righe INDIETRO riporta in cima; in cima chiude (la Home chiede conferma) */
    protected fun backToTop(): Boolean {
        if (closeDrawer()) return true
        val lm = rows.layoutManager as? LinearLayoutManager ?: return false
        if (lm.findFirstVisibleItemPosition() <= 0 && !rows.canScrollVertically(-1)) return false
        rows.scrollToPosition(0)
        rows.postDelayed({ if (!isFinishing) focusFirst() }, 120)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!backToTop()) super.onBackPressed()
    }

    /* ---------------- azioni ---------------- */
    private fun openCard(c: Db.Card, autoplay: Boolean = false) {
        open(DetailActivity::class.java) { putExtra("table", c.table); putExtra("id", c.id); if (autoplay) putExtra("autoplay", true) }
    }

    private fun toggleFav(c: Db.Card) {
        if (c.k.isEmpty()) return
        val on = Store.toggleFav(c.table, c.k)
        Ui.toast(if (on) "Aggiunto a La mia lista" else "Tolto da La mia lista")
        refreshDynamic()
    }

    private fun meta(c: Db.Card): String {
        val r = c.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.ITALY, "%.1f", if (it > 10) it / 10 else it) }
        return listOfNotNull(c.year.ifEmpty { null }, r).joinToString("  •  ")
    }

    /* ---------------- adapter delle righe ---------------- */
    private inner class HeroH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val kindT: TextView = v.findViewById(R.id.kind)
        val title: TextView = v.findViewById(R.id.title)
        val metaT: TextView = v.findViewById(R.id.meta)
        val age: TextView = v.findViewById(R.id.age)
        val cast: TextView = v.findViewById(R.id.cast)
        val plot: TextView = v.findViewById(R.id.plot)
        val play: TextView = v.findViewById(R.id.play)
        val info: TextView = v.findViewById(R.id.info)
    }

    private inner class RowH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val list: RecyclerView = v.findViewById(R.id.list)
        val cards = CardAdapter()
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: MutableList<Row> = ArrayList()
            set(v) { field = ArrayList(v) }

        override fun getItemCount() = items.size
        override fun getItemViewType(i: Int) = if (items[i].type == T_HERO) 0 else 1
        override fun getItemId(i: Int): Long = items[i].key.hashCode().toLong()

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            if (t == 0) {
                val h = HeroH(inf.inflate(R.layout.item_hero, p, false))
                val sh = resources.displayMetrics.heightPixels
                val sw = resources.displayMetrics.widthPixels
                h.itemView.layoutParams.height = (sh * 0.62f).toInt()
                (h.img.layoutParams as ViewGroup.MarginLayoutParams).width = (sw * 0.66f).toInt()
                for (b in listOf(h.play, h.info)) {
                    b.compoundDrawables[0]?.mutate()?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    b.setOnFocusChangeListener { x, has -> (x as TextView).compoundDrawables[0]?.setColorFilter(if (has) Color.BLACK else Color.WHITE, PorterDuff.Mode.SRC_IN) }
                }
                for (b in listOf(h.play, h.info)) b.setOnKeyListener { _, code, e ->
                    if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) { focusTab(); true } else false
                }
                h.play.setOnClickListener { hero?.let { openCard(it, autoplay = it.table == "vod") } }
                h.info.setOnClickListener { hero?.let { openCard(it) } }
                return h
            }
            val h = RowH(inf.inflate(R.layout.item_row, p, false))
            h.list.layoutManager = LinearLayoutManager(p.context, RecyclerView.HORIZONTAL, false).also { it.initialPrefetchItemCount = 6 }
            h.list.fast(horizontal = true)
            // ogni riga tiene pronte solo poche locandine fuori dallo schermo: le altre lasciano l'immagine alla cache con il tetto
            h.list.setItemViewCacheSize(if (App.lite) 2 else 4)
            h.list.setRecycledViewPool(pool)
            h.list.adapter = h.cards
            // la locandina selezionata si ingrandisce un poco: non deve essere tagliata sopra e sotto
            h.list.clipChildren = false
            (h.itemView as ViewGroup).clipChildren = false
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val r = items[i]
            if (h is HeroH) { bindHero(h); return }
            h as RowH
            h.title.text = r.title
            val empty = (r.type == T_POSTERS && r.cards?.isEmpty() == true && r.cat != Db.FAV) ||
                (r.type == T_RECENT && r.recent.isEmpty())
            h.itemView.layoutParams.height = if (empty) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            if (r.type == T_POSTERS && r.cat == Db.FAV && r.cards?.isEmpty() == true) h.title.text = r.title + "  ·  vuota: tieni premuto OK su una locandina per aggiungerla"
            if (h.cards.row !== r) { h.cards.row = r; h.list.scrollToPosition(0) }
            h.cards.notifyDataSetChanged()
            if (r.type == T_POSTERS && r.cards == null) loadRow(r, i)
        }

        // riga uscita dallo schermo (non solo scostata): le sue locandine lasciano la memoria, tornano dalla cache se si risale
        override fun onViewRecycled(h: RecyclerView.ViewHolder) {
            when (h) {
                is HeroH -> Ui.release(h.img)
                is RowH -> for (n in 0 until h.list.childCount) {
                    when (val c = h.list.getChildViewHolder(h.list.getChildAt(n))) {
                        is CardH -> Ui.release(c.img)
                        is RecentH -> Ui.release(c.img)
                    }
                }
            }
        }
    }

    private var heroH: HeroH? = null
    private var pendingHero: Db.Card? = null
    private var bgUrl: String? = null
    private val bgImg by lazy { findViewById<ImageView>(R.id.bgImg) }
    private val heroR = Runnable {
        val c = pendingHero ?: return@Runnable
        if (isFinishing || c === hero) return@Runnable
        hero = c
        heroH?.let { if (it.bindingAdapterPosition != RecyclerView.NO_POSITION) bindHero(it) else setBg(c.img) } ?: setBg(c.img)
    }

    /** il titolo selezionato diventa la copertina grande, dopo una breve pausa (scorrendo veloce non carica niente) */
    private fun focusCard(c: Db.Card) {
        pendingHero = c
        App.main.removeCallbacks(heroR)
        App.main.postDelayed(heroR, if (App.lite) 650L else 380L)
    }

    private fun setBg(url: String?) {
        if (url == bgUrl) return
        bgUrl = url
        val iv = bgImg ?: return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        if (url.isNullOrEmpty()) { Ui.img(iv, null, 0, 0); return }
        // l'anteprima c'è in ogni modalità: più piccola in modalità leggera e «Leggerissima» (senza dissolvenza), mai tolta
        if (App.lite) { val d = bgDiv(); Ui.img(iv, url, sw / d, sh / d); return }
        iv.animate().cancel()
        iv.alpha = 0f; iv.scaleX = 1f; iv.scaleY = 1f; iv.pivotX = sw.toFloat(); iv.pivotY = 0f
        Ui.img(iv, url, sw / 2, sh / 2)
        iv.animate().alpha(1f).setDuration(450).withEndAction {
            iv.animate().scaleX(1.06f).scaleY(1.06f).setDuration(14_000).setInterpolator(android.view.animation.LinearInterpolator()).start()
        }.start()
    }

    private fun bindHero(h: HeroH) {
        heroH = h
        h.img.visibility = View.GONE
        h.itemView.findViewById<View>(R.id.fadeL)?.visibility = View.GONE
        val c = hero
        if (c == null) {
            // niente titolo in evidenza (La mia lista, o liste non ancora caricate): solo lo spazio per le schede in alto
            h.itemView.layoutParams.height = Ui.dp(this, 6)
            for (v in listOf(h.img, h.kindT, h.title, h.metaT, h.plot, h.play, h.info, h.age, h.cast)) v.visibility = View.GONE
            setBg(null)
            return
        }
        h.itemView.layoutParams.height = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        for (v in listOf(h.kindT, h.title, h.metaT, h.play, h.info)) v.visibility = View.VISIBLE
        h.kindT.text = if (c.table == "series") "SERIE TV" else "FILM"
        h.title.text = c.name
        h.metaT.text = meta(c)
        h.plot.text = c.plot
        h.plot.visibility = if (c.plot.isBlank()) View.GONE else View.VISIBLE
        h.play.text = if (c.table == "series") "Guarda" else "Riproduci"
        h.age.visibility = View.GONE
        h.cast.visibility = View.GONE
        if (!App.lite) for (v in listOf(h.title, h.metaT, h.plot)) { v.alpha = 0f; v.animate().alpha(1f).setDuration(320).start() }
        val m = Meta.cached(c)
        if (m != null) { applyMeta(h, c, m); return }
        // copertina orizzontale, generi e cast dal pannello; se tardano resta la locandina
        App.main.postDelayed({ if (hero === c && Meta.cached(c) == null) setBg(c.img) }, 1200)
        Meta.get(c) { info ->
            if (isFinishing || hero !== c) return@get
            val hh = heroH?.takeIf { it.bindingAdapterPosition != RecyclerView.NO_POSITION }
            if (info == null) setBg(c.img) else if (hh != null) applyMeta(hh, c, info) else setBg(info.backdrop.ifEmpty { c.img })
        }
    }

    private fun applyMeta(h: HeroH, c: Db.Card, m: Meta.Info) {
        val r = m.rating.ifEmpty { c.rating }.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.ITALY, "%.1f", if (it > 10) it / 10 else it) }
        h.metaT.text = listOfNotNull(c.year.ifEmpty { m.year }.ifEmpty { null }, m.genre.ifEmpty { null }, m.dur.ifEmpty { null }, r).joinToString("  •  ")
        if (m.plot.isNotEmpty()) { h.plot.text = m.plot; h.plot.visibility = View.VISIBLE }
        h.age.text = m.age
        h.age.visibility = if (m.age.isEmpty()) View.GONE else View.VISIBLE
        h.cast.text = "Con " + m.cast
        h.cast.visibility = if (m.cast.isEmpty()) View.GONE else View.VISIBLE
        setBg(m.backdrop.ifEmpty { c.img })
    }

    /* ---------------- menu laterale delle categorie ---------------- */
    private val drawer by lazy { findViewById<View>(R.id.drawer) }
    private val drawerShade by lazy { findViewById<View>(R.id.drawerShade) }
    private val drawerList by lazy { findViewById<RecyclerView>(R.id.drawerList) }
    private var drawerBack: View? = null
    private var drawerItems: List<Pair<String, Db.Cat>> = emptyList()

    private fun openDrawer(from: View) {
        if (kind == "list" || drawer.visibility == View.VISIBLE) return
        drawerBack = from
        val pls = K.activeIds()
        val kids = K.kids
        val tables = when (kind) { "vod" -> listOf("vod"); "series" -> listOf("series"); else -> listOf("vod", "series") }
        findViewById<TextView>(R.id.drawerTitle).text = when (kind) { "vod" -> "Film"; "series" -> "Serie TV"; else -> "Film e Serie TV" }
        bg {
            // dal pannello: categorie in primo piano per prime, quelle nascoste dalla Home in fondo (vedi HomeCfg)
            val items = HomeCfg.order(tables.flatMap { t -> Db.cats(t, pls, kids).map { t to it } }) { it.second.name }
            ui {
                if (isFinishing || items.isEmpty()) return@ui
                drawerItems = items
                if (drawerList.layoutManager == null) drawerList.layoutManager = LinearLayoutManager(this)
                drawerList.adapter = DrawerAdapter()
                drawer.visibility = View.VISIBLE
                drawerShade.visibility = View.VISIBLE
                if (!App.lite) {
                    drawer.translationX = -Ui.dp(this, 340).toFloat()
                    drawer.animate().translationX(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                    drawerShade.alpha = 0f
                    drawerShade.animate().alpha(1f).setDuration(260).start()
                }
                drawerList.post { drawerList.layoutManager?.findViewByPosition(0)?.requestFocus() ?: drawerList.requestFocus() }
            }
        }
    }

    private fun closeDrawer(): Boolean {
        if (drawer.visibility != View.VISIBLE) return false
        if (App.lite) { drawer.visibility = View.GONE; drawerShade.visibility = View.GONE }
        else {
            drawer.animate().translationX(-drawer.width.toFloat()).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
            drawerShade.animate().alpha(0f).setDuration(180).withEndAction { drawerShade.visibility = View.GONE }.start()
        }
        (drawerBack?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.tabCats))?.requestFocus()
        return true
    }

    private inner class DrawerAdapter : RecyclerView.Adapter<DrawerAdapter.H>() {
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun getItemCount() = drawerItems.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_cat, p, false))
            h.itemView.setOnClickListener {
                val (table, c) = drawerItems.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                closeDrawer()
                open(VodActivity::class.java) { putExtra("kind", table); putExtra("cat", c.id) }
            }
            h.itemView.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false else when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { closeDrawer(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> h.bindingAdapterPosition == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> h.bindingAdapterPosition >= itemCount - 1
                    else -> false
                }
            }
            return h
        }

        override fun onBindViewHolder(h: H, i: Int) {
            val (table, c) = drawerItems[i]
            h.name.text = if (kind == "home") (if (table == "vod") "Film · " else "Serie · ") + c.name else c.name
            h.count.text = if (c.count >= 0) Ui.num(c.count) else ""
        }
    }

    /* ---------------- locandine di una riga ---------------- */
    private inner class CardH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val rank: TextView? = v.findViewById(R.id.rank)
        val badge: TextView? = v.findViewById(R.id.badge)
    }

    private inner class RecentH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val title: TextView = v.findViewById(R.id.title)
        val left: TextView = v.findViewById(R.id.left)
    }

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var row: Row? = null

        override fun getItemCount(): Int {
            val r = row ?: return 0
            if (r.type == T_RECENT) return r.recent.size
            val n = r.cards?.size ?: 0
            if (r.top) return n.coerceAtMost(10)
            return n + if (r.more && n > 0) 1 else 0
        }

        override fun getItemViewType(i: Int): Int {
            val r = row ?: return V_CARD
            return if (r.type == T_RECENT) V_RECENT else if (i >= (r.cards?.size ?: 0)) V_MORE else if (r.top) V_TOP else V_CARD
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            if (t == V_RECENT) {
                val h = RecentH(inf.inflate(R.layout.item_recent, p, false))
                h.itemView.findViewById<View>(R.id.box).clipToOutline = true
                h.itemView.setOnClickListener { val o = h.recentItem() ?: return@setOnClickListener; Play.resume(this@BrowseActivity, o) }
                h.itemView.setOnLongClickListener { val o = h.recentItem(); if (o != null) { Store.removeRecent(o.optString("id")); refreshDynamic() }; true }
                h.itemView.setOnKeyListener { _, code, e ->
                    e.action == KeyEvent.ACTION_DOWN && ((code == KeyEvent.KEYCODE_DPAD_RIGHT && h.bindingAdapterPosition >= itemCount - 1) || (code == KeyEvent.KEYCODE_DPAD_LEFT && h.bindingAdapterPosition == 0))
                }
                Ui.zoom(h.itemView, 1.06f)
                return h
            }
            val h = CardH(inf.inflate(if (t == V_TOP) R.layout.item_top else R.layout.item_card, p, false))
            h.itemView.findViewById<View>(R.id.box).clipToOutline = true
            h.rank?.paint?.let { it.style = android.graphics.Paint.Style.STROKE; it.strokeWidth = Ui.dp(p.context, 3).toFloat() }
            h.itemView.setOnFocusChangeListener { _, has -> if (has) h.card()?.let { focusCard(it) } }
            h.itemView.setOnClickListener {
                val r = row ?: return@setOnClickListener
                val i = h.bindingAdapterPosition
                val cards = r.cards ?: return@setOnClickListener
                if (i in cards.indices) openCard(cards[i])
                else if (i == cards.size) open(VodActivity::class.java) { putExtra("kind", r.table); putExtra("cat", r.cat) }
            }
            h.itemView.setOnLongClickListener { h.card()?.let { toggleFav(it) }; true }
            h.itemView.setOnKeyListener { _, code, e ->
                when {
                    code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP -> { h.card()?.let { toggleFav(it) }; true }
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT -> h.bindingAdapterPosition >= itemCount - 1
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT -> if (h.bindingAdapterPosition == 0) { openDrawer(h.itemView); true } else false
                    e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP -> upFromRow(h.itemView)
                    else -> false
                }
            }
            Ui.zoom(h.itemView, 1.07f)
            return h
        }

        private fun RecentH.recentItem(): JSONObject? { val r = row ?: return null; val i = bindingAdapterPosition; return r.recent.getOrNull(i) }
        private fun CardH.card(): Db.Card? { val r = row ?: return null; return r.cards?.getOrNull(bindingAdapterPosition) }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val r = row ?: return
            when (h) {
                is RecentH -> {
                    val o = r.recent.getOrNull(i) ?: return
                    val title = o.optString("title")
                    h.title.text = title
                    h.ph.text = title
                    val dur = o.optLong("dur")
                    val pos = o.optLong("pos")
                    h.bar.progress = if (dur > 0) (pos * 100 / dur).toInt() else 0
                    h.left.text = if (dur > 0) Ui.minutes(dur - pos) else ""
                    Ui.img(h.img, o.optString("img"), recentW, recentH)
                }
                is CardH -> {
                    val cards = r.cards ?: return
                    if (i >= cards.size) {
                        h.ph.text = "Vedi tutti\n" + Ui.num(r.count)
                        Ui.img(h.img, null, 0, 0)
                        return
                    }
                    val c = cards[i]
                    h.ph.text = c.name
                    h.rank?.text = (i + 1).toString()
                    h.badge?.let { b ->
                        b.visibility = if (r.cat == Db.NEW) View.VISIBLE else View.GONE
                        b.text = if (c.table == "series") "NUOVA SERIE" else "NUOVO FILM"
                    }
                    if (App.max) Ui.img(h.img, c.img, posterW * 2 / 3, posterH * 2 / 3) else Ui.img(h.img, c.img, posterW, posterH)
                }
            }
        }

        // locandina uscita dallo schermo: la richiesta si annulla (niente download per righe già passate)
        override fun onViewRecycled(h: RecyclerView.ViewHolder) {
            when (h) {
                is CardH -> Ui.release(h.img)
                is RecentH -> Ui.release(h.img)
            }
        }
    }
}
