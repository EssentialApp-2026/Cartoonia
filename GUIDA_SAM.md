# SAM 2.1 (telefono) e SAM per PC 2.0.3

SAM vive sul tuo Redmi Note 14 Pro: il riconoscimento della voce, i comandi, l'intelligenza artificiale e le
risposte girano tutti sul processore del telefono. **Internet lo usa solo in due casi**: col Wi-Fi, per scaricare
aggiornamenti e modelli (Kiwix e Hugging Face); e, dalla 1.16, **quando gli chiedi tu di cercare sul web** (anche coi
dati). Il cervello resta sempre sul telefono: su internet vanno solo le parole da cercare. Nessun account, nessuna
chiave, nessuna API a pagamento.

| Parte | Dove gira | Online? |
|---|---|---|
| Ascolto della parola "SAM" e dei comandi (Vosk, italiano) | sul telefono (file della voce, importato una volta) | mai |
| Esecuzione dei comandi (torcia, sveglie, chiamate, app…) | sul telefono | mai |
| Cervello IA per chiacchierare (scelto in base al tuo telefono, LiteRT-LM) | sul telefono | mai |
| Voce di SAM | sintesi vocale del telefono, **solo voci offline** | mai |
| Riconoscere la tua voce (modello delle voci di Vosk, dentro l'app) | sul telefono | mai |
| Leggere i file e guardare con la fotocamera | sul telefono (il cervello IA) | mai |
| Aggiornamenti: la Wikipedia nuova e il cervello nuovo | scaricati da internet, **solo col Wi-Fi** | solo per scaricarli |
| Ricerca sul web e ricerca approfondita (1.16) | cerca su DuckDuckGo/Wikipedia, il riassunto lo fa il cervello sul telefono | **solo quando lo chiedi** |

## Telefono 2.1 — cervelli online con più account che si danno il cambio

Oltre al cervello del telefono SAM può usare i **cervelli online** con i tuoi account: Groq, Google Gemini, OpenRouter,
Mistral, Cerebras, OpenAI, Anthropic Claude, DeepSeek o un altro servizio compatibile con OpenAI.

1. Apri ⚙︎ › **4+ · Cervelli online** › **Gestisci gli account online** › **+ Aggiungi**.
2. Scegli il servizio e incolla la chiave API (il link sotto la chiave apre la pagina dove prenderla). Il modello si
   può scegliere dall'elenco del servizio con **Scegli il modello dall'elenco**; **Prova** controlla che funzioni.
3. Metti gli account nell'ordine che preferisci con ↑ ↓.

Come funziona: SAM prova gli account dall'alto verso il basso. Quando uno finisce i token, supera il limite al minuto,
ha la chiave sbagliata o il servizio non risponde, lo mette **in pausa per il tempo giusto** (pochi secondi per i limiti
al minuto, almeno un'ora per quelli del giorno) e passa **da solo** al successivo; lo vedi nella riga di stato. Se sono
tutti in pausa, o non c'è internet, risponde il **cervello del telefono** come prima. Gli account in pausa tornano da soli
(oppure **Riattiva tutti**).

Tre modi, nella stessa pagina: *Online, poi il cervello del telefono* (consigliato), *Solo online*, *Solo il cervello
del telefono*. Comandi, ricordi, voce, sveglie e chiamate restano sul telefono; le foto («cosa vedi?») e la conversazione
con un'altra IA usano sempre il cervello del telefono. Le chiavi sono cifrate e non escono dal telefono; le domande
fatte ai cervelli online invece vanno al servizio scelto.

Usa account tuoi su servizi diversi: aprire tanti account gratuiti sullo stesso servizio per aggirarne i limiti è
vietato dalle sue regole e può portare al blocco.

## Telefono 2.0.4 e PC 2.0.3 — corretto il seguito dei file lunghi (la pagina bianca)
Un file vero («gestione lista spesa») si apriva bianco. Cosa era successo: il primo pezzo si fermava a metà del CSS; nel secondo
pezzo il cervello, invece del codice, cominciava con i suoi ragionamenti in inglese («The user wants me to continue…») e poi
apriva un blocco nuovo. SAM incollava i ragionamenti dentro il file e, al momento di salvare, teneva solo il primo blocco:
il seguito vero andava perso. Il `<style>` restava aperto e il browser non mostrava niente.
- Dal pezzo nuovo adesso entra nel file **solo il codice**: via i ragionamenti e le parole prima del blocco, via quello che c'è dopo.
- Se il seguito **ripete le ultime righe** già scritte, o riscrive per intero la riga rimasta a metà, i doppioni si tolgono.
- Al cervello viene mostrato anche l'inizio del file e gli si chiede il seguito in un blocco, senza spiegazioni.
- Una pagina con `<style>` o `<script>` non chiusi conta come non finita. I pezzi sono al massimo 12 (prima 8).
- Collaudato sul file vero (185 controlli automatici), non con un cervello vero.

## Telefono 2.0.3 e PC 2.0.2 — file completi, e in HTML
- **I file non restano più a metà.** Il cervello scrive un pezzo alla volta (circa 2000 parole-pezzo sul telefono, 4000 sul PC):
  prima, arrivato al limite, SAM salvava il file così com'era, tronco. Ora controlla se è finito (il blocco di codice è chiuso,
  la pagina ha `</html>`, non ci sono graffe aperte, il documento finisce con un punto) e, se non lo è, **continua da solo** dal
  punto in cui era arrivato, fino a 8 pezzi. Sullo schermo: «Non è finito: continuo a scrivere (parte 2)…».
  Se dopo 8 pezzi non è ancora completo lo salva e te lo dice. Se si interrompe per un errore non salva un file tronco:
  lo tiene in sospeso e «continua» lo riprende.
- **Tutto in HTML.** «scrivimi un programma che…» senza dire il linguaggio adesso produce **un file .html unico** (HTML, CSS e
  JavaScript dentro), che si apre subito nel browser. Prima era Python. Se vuoi altro, dillo: «in Python», «in MQL5», «in Kotlin».
  Anche i **documenti** («scrivimi un documento su…») si salvano come pagina **.html** (titolo, titoletti, elenchi; chiara o scura
  come il telefono; si stampa bene). «…in PDF» continua a dare il PDF.
- Sul telefono il file HTML si apre da solo se SAM è sullo schermo; altrimenti dalla notifica o con «apri il file».
- Non provato con un cervello vero: il controllo e l'unione dei pezzi sì (173 controlli automatici), la qualità del seguito
  dipende dal cervello.

## Telefono 2.0.2 e PC 2.0.1 — si ricorda cosa stava facendo, e non si interrompe mentre scrive
- **Si ricorda il lavoro lasciato a metà.** Se viene fermato mentre lavora (lo fermi tu, si blocca, Android lo chiude, chiudi il
  programma sul PC) si segna cosa stava facendo. «**cosa stavi facendo?**» te lo dice; «**continua**» (o «riprendi da dove eri
  rimasto», «continua il lavoro») lo riprende. Al riavvio lo scrive da solo: «Quando mi sono fermato stavo lavorando a…».
  - Sul telefono, se stava scrivendo un **programma o un documento**, riprende **da dove era arrivato** (quello già scritto resta,
    il cervello scrive solo il seguito). Le altre cose (una risposta, una ricerca, le slide) le rifà da capo. Sul PC rifà da capo.
  - Il lavoro in sospeso si tiene tre giorni. «continua» da solo vale come «riprendi il lavoro» solo se c'è un lavoro in sospeso
    (altrimenti resta «continua a leggere» o la musica).
- **Mentre scrive non chiede più «ci sei ancora?»** (telefono). Era un errore: dopo aver parlato tornava in ascolto anche se stava
  ancora scrivendo, dopo 45 secondi chiedeva «ci sei ancora?» e la scrittura si interrompeva. Ora resta al lavoro finché ha finito.
- **Non viene più fermato dopo 10 minuti** se sta ancora scrivendo (telefono): «bloccato» adesso vuol dire che il cervello non
  scrive niente da 4 minuti, non che il lavoro è lungo.
- Telefono 2.0.1: il tasto **Skill** è tornato con la scritta.
- Non provato su telefono e Windows veri; la ripresa «da dove era arrivato» dipende da come il cervello continua il testo.

## Telefono 2.0 e PC 2.0 — professionale e autogestito
SAM adesso si tiene in ordine da solo, e ha un aspetto nuovo su telefono e PC.

### Si gestisce da solo
| Cosa | Come lo usi | Cosa fa |
|---|---|---|
| **Controllo** | «**controllati**», «funziona tutto?», «fai la diagnosi»; oppure il **pallino** in alto (verde, giallo, rosso) | Guarda microfono, voce, cervello (il file è intero? è carico?), permessi, ricordi, copie, spazio, errori recenti. Dice prima i problemi, poi gli avvisi |
| **Riparazione** | «**riparati**», «sistemati»; tasto **Ripara** nello Stato | Riaccende il microfono, ricarica il cervello, toglie le righe rovinate dai ricordi (dopo averne fatto una copia), fa la copia se manca, fa pulizia. Se il file del cervello è rovinato chiede prima di riscaricarlo. Sul PC, se il cervello principale non parte usa un altro di quelli che hai |
| **Copie di sicurezza** | «**fai una copia**», «che copie hai?», «**ripristina la copia di ieri**» (o «dell'altro ieri», «del 3 novembre», «di 3 giorni fa», «l'ultima») | Ogni giorno, da solo, uno zip con ricordi, mente, conversazione (sul PC anche le impostazioni). Tiene 7 giorni, poi una a settimana per 5 settimane. Prima di ripristinare chiede conferma e salva com'è adesso: «ripristina la copia di prima» torna indietro |
| **Registro** | «**cosa è andato storto?**», «mostrami il registro», «perché ti sei chiuso?» | Segna errori, chiusure improvvise e le riparazioni fatte; a voce riassume gli ultimi 7 giorni |
| **Pulizia e spazio** | «**fai pulizia**», «quanto spazio occupi?» | Toglie solo i suoi file temporanei vecchi. I tuoi file e i modelli non li tocca |
| **Versione** | «che versione sei?», «cosa c'è di nuovo?» | |

- **Manutenzione di ogni giorno**: quando è fermo fa copia, pulizia e controllo; se trova un problema serio te lo scrive.
  Si spegne dalle impostazioni (telefono: «11 · Manutenzione»; PC: Impostazioni › Manutenzione).
- **Modalità sicura**: se si chiude male tre volte di fila in dieci minuti, riparte senza caricare il cervello (i comandi
  funzionano) e te lo dice. «riparati» lo ricarica.
- **Dopo una chiusura improvvisa** al riavvio lo dice e lo trovi nel registro.
- Dove stanno: telefono, copie in `Android/data/com.essentialapp.sam/files/copie`; PC, `C:\Users\<tu>\SAM\copie` e
  `registro.tsv`. Le copie del telefono spariscono se disinstalli l'app: per tenerle fuori usa «Salva una copia» nei Ricordi.

### Grafica nuova
- **Telefono**: in alto icone disegnate (Skill, Mente, Stato, Ferma, Configurazione) al posto delle emoji; lo stato su una
  riga sua; a conversazione vuota un benvenuto con frasi da toccare; barra in basso con graffetta, microfono e invio tondi.
  Lo **Stato** (il pallino) mostra i controlli uno per uno con Ripara e «Altro…» (copia, ripristina, registro, pulizia).
  La **configurazione** è a schede, con in cima «Per cominciare» (cosa manca per partire) e in fondo Manutenzione e Informazioni.
- **PC**: tutto scuro e uniforme (prima le impostazioni erano chiare); pallino dello stato in alto; proposte sopra la barra
  finché non scrivi; **Impostazioni in pagine**: Modelli, Cervello, Voce e nome, Scheda video, Documenti e telefono,
  Manutenzione, Informazioni. La prima volta una **presentazione in tre passi** (nome, modelli, come si usa).

### Installazione vera su Windows
- `Computer\SAM_Installa.exe`: doppio clic, Avanti, Installa. Niente amministratore. Mette SAM nel menu Start e sul
  desktop, e compare in Impostazioni di Windows › App per disinstallarlo.
- Va in `C:\Users\<tu>\AppData\Local\Programs\SAM`. Ricordi, impostazioni e modelli restano in `C:\Users\<tu>\SAM`:
  installare sopra o disinstallare non li tocca. La vecchia cartella SAM-PC estratta a mano non serve più.
- Windows può dire «Windows ha protetto il PC» (il programma non è firmato): «Ulteriori informazioni» › «Esegui comunque».

### Collaudo (cosa è stato provato e cosa no)
- Provato qui: 128 controlli automatici su frasi, registro, copie, ripristino, controllo di salute, pulizia e guardia degli
  avvii; la finestra del PC, le sette pagine delle impostazioni, lo Stato e la presentazione guardate una per una; i comandi
  di cura scritti nella finestra del PC; l'archivio dell'installazione verificato file per file.
- **Non provato**: il telefono vero (la grafica nuova del telefono non l'ho potuta vedere), Windows vero, e
  l'installazione eseguita su Windows. Se qualcosa non torna, dimmelo con una foto dello schermo.

## Telefono 1.23 e PC 1.8 — la mente, fatta come quella umana
I pilastri di OpenJarvis (memoria, agenti, strumenti, apprendimento) sono entrati dentro SAM, organizzati come le facoltà di
una mente umana. Tutto resta sul dispositivo. Il pannello **🧠** (telefono, in alto) o **Mente** (PC) mostra cosa c'è dentro.

| Facoltà | Cosa fa SAM | Come lo usi |
|---|---|---|
| **Memoria degli impegni** (prospettica) | Si segna da solo gli impegni detti di passaggio, poi te li ricorda e ti chiede com'è andata | «domani ho il dentista alle 16» → riga «Memoria · me lo segno»; «**cosa devo fare domani?**», «che impegni ho questa settimana?» |
| **Saluto del mattino** (agente) | La giornata: impegni di oggi e domani, le note con la data, cosa ha imparato, «com'è andata?» | «**buongiorno**»; oppure lo chiami («SAM») la mattina e te lo dice da solo |
| **Diario** (memoria episodica) | Le conversazioni diventano pagine di diario (una frase), tenute per un anno; le battute intere restano 7 giorni | «**il tuo diario**», «il diario di ieri», «di cosa abbiamo parlato il mese scorso?» |
| **Forza dei ricordi** (memoria semantica) | I ricordi che usate restano vivi, quelli imparati da solo e mai usati da 3 mesi sbiadiscono (non si cancellano: se ne riparli tornano) | automatico; nel pannello vedi i più forti |
| **Associazioni** (memoria di lavoro) | A ogni domanda gli «vengono in mente» episodi passati, impegni e il tuo stato d'animo | automatico |
| **Abitudini** (memoria procedurale) | Impara cosa chiedi alla stessa ora per più giorni e te lo propone al momento giusto | «**le mie abitudini**»; al risveglio: «Come ogni mattina, vuoi il meteo?» |
| **Emozioni** | Capisce come ti senti («sono stanco morto», «che giornata pesante») e cambia tono | «come mi vedi?» |
| **Pensiero lento** | Alle domande difficili (decidere, calcolare, risolvere) prima ragiona passo per passo (riquadro «Ragionamento»), poi risponde | «aiutami a decidere se…»; «rispondi subito senza pensarci» / «pensa bene alle domande difficili» |
| **Metacognizione** | Sa da dove viene quello che ha detto: memoria, enciclopedia, internet, cervello | «**come lo sai?**», «**sei sicuro?**» (se viene dal cervello propone di controllare su internet) |
| **Curiosità** | Ti fa qualche domanda per conoscerti; le risposte diventano ricordi | «**facciamo conoscenza**» (passo per saltare, basta per finire) |
| **Sonno** | Quando è fermo riordina: diario, impegni passati, autoapprendimento (ora anche sul PC) | automatico; nel pannello «Riordina adesso» |

- Le proposte da solo (saluto, abitudini, «com'è andata?») si spengono con «**non propormi più niente**» e si riaccendono con
  «puoi propormi le cose». Arrivano solo quando lo chiami e basta («SAM»), mai in mezzo a un comando.
- Se qualcuno dice di voler farsi del male, SAM risponde subito con vicinanza e con chi può aiutare (112, Telefono Amico
  02 2327 2327, Samaritans 06 7720 8977) e propone di chiamare. Queste frasi non finiscono nel diario.
- «come funziona la tua mente?» te lo spiega a voce.

## PC 1.7 — OpenJarvis (Stanford) come cervello di SAM
- **OpenJarvis** è il progetto di Stanford per un'IA personale che gira sul computer (quello dei video su TikTok). SAM per
  PC 1.7 lo usa come cervello: tu parli a SAM, la risposta la scrive OpenJarvis. Voce, comandi, memoria, skill, foto e
  video restano di SAM. Il tutorial completo per installarlo su Windows è il documento «OpenJarvis su Windows e dentro SAM».
- Come si collega: apri OpenJarvis con `uv run jarvis serve` (lascia aperta la finestra), poi in SAM per PC
  **Impostazioni › OpenJarvis (Stanford)** › «Controlla» › scegli il modello › «**Usa OpenJarvis come cervello**».
  A voce: «**usa OpenJarvis**», «**torna al tuo cervello**», «**OpenJarvis funziona?**».
- Compare anche nella lista dei modelli, sotto «Caricati da te», come «OpenJarvis · cervello esterno».
- Sul telefono no: OpenJarvis gira sul computer (il telefono te lo dice se glielo chiedi).

## Telefono 1.22 e PC 1.6 — il nome si cambia, i modelli caricati a mano nella lista
- **Il nome dell'assistente si cambia quando vuoi.** A voce o scritto: «**da ora ti chiami Jarvis**», «chiamati Luna»,
  «il tuo nome è Ettore», oppure «voglio cambiarti nome» (e te lo chiede). Oppure dalle impostazioni: sul telefono
  «Il nome dell'assistente», sul PC «Impostazioni › Nome dell'assistente». Una o due parole, solo lettere.
  - Cambia tutto: la **parola per chiamarlo**, come si presenta («come ti chiami?»), quello che scrive sullo schermo,
    la notifica, il nome in alto. Le cartelle restano «SAM» (Galleria › SAM, Documenti\SAM), così non si perde niente.
  - Subito dopo il cambio ti chiede di **dire il nome una volta**: il riconoscitore della voce non conosce tutti i nomi
    e magari scrive «giar vis» al posto di «Jarvis». SAM se lo segna e da lì si sveglia anche così. Per rifarlo:
    «**impara come dico il tuo nome**». All'inizio della frase basta che il nome suoni simile.
  - «**torna a chiamarti SAM**» per tornare come prima.
  - Sul telefono il nome sotto l'icona dell'app non si può cambiare (Android non lo permette): nelle impostazioni
    c'è «**Metti l'icona con questo nome nella schermata Home**», che aggiunge un'icona col nome nuovo. Quella vecchia
    lasciala o mettila in una cartella: su Xiaomi trascinarla via può voler dire disinstallare l'app.
  - Sul PC il collegamento sul desktop prende il nome nuovo.
  - Consiglio: scegli un nome che non sia una parola che dici spesso (con «Luna» si sveglierebbe anche a «guarda la luna»).
- **I modelli caricati a mano compaiono nella lista come quelli scaricati.**
  - Sul PC, in Impostazioni › Modelli, sotto «**Caricati da te**»: nome, grandezza, che tipo è, «Presente», e i tasti
    «Usa come principale» e «Mostra». Compaiono subito dopo «Carica un cervello GGUF…», senza riaprire le impostazioni,
    e anche quelli messi a mano nella cartella dei modelli.
  - Sul telefono la lista «I cervelli sul telefono» c'è sempre (prima solo con due o più), e quelli importati da te
    hanno scritto «caricato da te».

## Telefono 1.21 e PC 1.5 — mostra e crea foto e video
- **Le vede nella chat**: «**mostrami le foto di ieri**», «fammi vedere l'ultimo video», «le ultime 5 foto», «le foto
  di sabato», «le foto della settimana scorsa», «le foto del 3 agosto», «gli screenshot di oggi», «**i tuoi disegni**»,
  «i video che hai fatto», «che foto ho fatto ieri?». Una sola si vede grande, di più a griglia (i video con ▶ e la
  durata). **Tocca** per aprirla, **tieni premuto** per condividerla (sul PC: clic per aprire, tasto destro per la cartella).
  - Sul telefono la prima volta chiede il permesso **Foto e video** (se scegli «solo alcune», vede solo quelle).
  - Sul PC cerca in Immagini, Video, Desktop e Download; le foto del telefono copiate sul PC si vedono dritte.
- **Le guarda**: «**cosa c'è nell'ultima foto?**», «descrivimi l'ultima foto che ho fatto», «com'è venuto il selfie?»
  → la mostra e la guarda il cervello che vede (Gemma 4). Per ora solo le foto, non i video.
- **Scatta e filma da solo**, con la sua fotocamera (senza aprire l'app Fotocamera):
  - «**scatta una foto**», «fai una foto alla pianta», «**fammi un selfie**» (fotocamera davanti, 3 secondi per sorridere),
    «scatta una foto tra 10 secondi» (autoscatto, col conto alla rovescia sullo schermo). Massima qualità, nella
    Galleria, cartella **DCIM › SAM**.
  - «**registra un video**» (15 secondi), «registra un video di 30 secondi», «filmami per un minuto» (davanti),
    «fai un video di 20 secondi con la fotocamera davanti». Full HD con l'audio; **tocca lo schermo** per fermare
    prima. Mentre filma SAM non ascolta e non parla (finirebbe nel video).
  - Sul PC usa la **webcam** (foto e video, col microfono se c'è) e sa anche «**registra lo schermo per un minuto**»
    (sul telefono per lo schermo c'è il Registratore schermo di Xiaomi, nella tendina).
  - «apri la fotocamera e scatta una foto» o «voglio fare una foto» aprono ancora l'app Fotocamera, se preferisci farla tu.
- **Crea video con le tue foto** (come i «ricordi» della Galleria): «**fammi un video con le foto di sabato**»,
  «crea un video con le ultime 10 foto», «monta le foto di ieri in un video», «**anima l'ultima foto**», «fai un video
  dall'ultima foto». Le foto vanno in fila dalla più vecchia, ognuna si muove (zoom e panoramiche) e si passa all'altra
  con una dissolvenza; verticale (720×1280) se le foto sono quasi tutte verticali, altrimenti orizzontale. Fino a 40
  foto, al massimo un minuto. Finisce in **Video › SAM**. Per fermarlo tocca Parla.
- **I disegni e i video che crea SAM** (Stable Diffusion, i disegni animati, Wan sul PC) ora **si vedono direttamente
  nella chat**, non solo nella Galleria.
- «sai fare i video?» ora spiega tutti e tre i modi: filmare, montare le foto, disegnare e animare.

## Telefono 1.20 e PC 1.4 — agisce, usa le skill da solo, legge la chat
- **Non solo chiacchiere: agisce.** Quando parlate e serve fare qualcosa, il cervello lo chiede da solo e SAM lo fa
  (sveglie, timer, promemoria, «ricordati che…», ricerche su internet, aprire app/programmi, meteo, musica, documenti,
  presentazioni, codice, disegni…). Sullo schermo compare una riga **Azione** con quello che ha fatto. Esempi: «svegliami
  domani alle 6 che vado a pescare» → mette la sveglia; «ho bisogno di sapere quanto costa il rame oggi» → cerca su
  internet da solo. Dipende dal cervello: Gemma 4 lo fa bene, i cervelli piccoli a volte no. Le cose delicate (messaggi, chiamate) chiedono sempre conferma come prima.
- **Le skill in automatico**, senza aprire il pannello: «traduci in inglese: …», «traduci … in spagnolo», «correggimi
  questa email: …», «riassumi: …», «scrivimi un'email al condominio per…», «dammi delle idee per…», «fammi un piano
  per…», «pro e contro di…», «spiegami in modo semplice…», «spiegami questo codice: …».
- **Legge la chat**: «**leggimi la chat**», «rileggi gli ultimi tre messaggi», «**riassumi la conversazione**», «cosa
  c'è scritto nella chat?». Il riassunto lo fa solo da quello che vi siete detti davvero.
- **Cambia cervello da solo**: oltre a codice (quando scrive programmi) e vista, anche le **domande sul codice**
  («perché questo codice Python dà errore?») vanno da sole al cervello per programmare; poi torna a quello principale.

## Telefono 1.19 e PC 1.3 — ricorda senza inventare, video veri con Wan
- **Non inventa più i ricordi.**
  - Quando chiedi «cosa ti ho detto della macchina?», «di cosa abbiamo parlato ieri?», SAM risponde **solo** da quello che
    vi siete detti davvero (con quando: «ieri alle 18:05», «sabato alle 9:05») e da quello che gli hai chiesto di ricordare.
    Se non c'è, dice che non se lo ricorda, invece di inventare.
  - La conversazione si tiene per **una settimana** (prima un giorno), anche se chiudi SAM.
  - Anche nelle risposte normali ha l'istruzione di non inventare fatti, nomi, date e numeri su di te; e usa un po' meno
    fantasia (Gemma). Sul PC un ricordo nuovo («ricordati che…») vale subito, dalla risposta dopo.
  - Per le cose importanti resta il modo più sicuro: «**SAM, ricordati che** …» (le tiene per sempre).
- **Video veri con Wan 2.1 (SAM per PC).** In Impostazioni › Modelli ci sono tre righe "Video":
  - **Video: Wan 2.1 T2V 1.3B** — qui va il tuo **Wan2.1-T2V-1.3B-F16.gguf** (tasto *Carica file…*; tiene il suo nome).
    Va bene anche la versione .safetensors o un'altra quantizzazione del 1.3B T2V; i Wan 14B, I2V e VACE no.
  - **Video: UMT5-XXL** — il codificatore del testo (Q4_K_M, 3,7 GB; va bene anche Q8 o fp16 se li hai).
  - **Video: VAE di Wan 2.1** — wan_2.1_vae.safetensors (254 MB).
  Quando ci sono tutti e tre, «**fammi un video di** un gatto che corre sulla spiaggia» crea un video vero (prima era il
  disegno animato). Sulla scheda video (con il motore Vulkan) 832×480, 33 fotogrammi, qualche minuto; sul processore
  480×272, e ci vuole molto (anche più di mezz'ora: SAM ti dice quanto manca). «in alta qualità» = più grande e più lungo;
  «veloce» = più corto. Mentre fa il video il cervello si mette da parte e poi si ricarica.
- **Sul telefono Wan non va**: se provi a importarlo come cervello SAM ti spiega che è un modello per i video da usare sul PC.

## SAM per PC 1.2 — modelli caricati a mano
- In **Impostazioni › Modelli** ogni modello ha, accanto a "Scarica", il tasto **Carica file…**: scegli un file che hai
  già (scaricato col browser, copiato da una chiavetta, preso da LM Studio). SAM controlla che sia quello giusto e lo
  copia nella sua cartella (l'originale resta dov'è):
  - cervelli e vista: il file **.gguf** (la vista è il file "mmproj"; i GGUF divisi in più pezzi non vanno);
  - disegni: i file **.safetensors** (Stable Diffusion 1.5, anche un'altra versione 1.5; LCM; TAESD);
  - voce (Vosk) e Piper: lo **zip** oppure la **cartella già aperta**; Python: lo zip "embeddable";
  - voce di Paola: il file **.onnx** (metti accanto il suo **.onnx.json**, SAM lo prende insieme).
- **Carica un cervello GGUF…** aggiunge un cervello qualsiasi (Qwen, Llama, Mistral, Phi…): poi lo scegli in
  Impostazioni › Cervello principale.
- Se il file non va bene SAM ti dice perché (per esempio "non è un file GGUF", "è il pezzo della vista, non un cervello").
- Sul telefono il cervello si carica a mano già da prima: ⚙︎ › 4 › Importa il file del cervello.

## SAM per PC 1.1 e telefono 1.18 — al massimo
- **Scheda video (GTX 1650).** Impostazioni › Scheda video › **Scarica il motore per la scheda video** (~150 MB, le
  versioni ufficiali Vulkan di llama.cpp e stable-diffusion.cpp). Da lì il cervello mette sulla scheda video tutti gli
  strati che ci stanno (sulla GTX 1650 circa metà di Gemma 4 E4B) e i disegni si fanno sulla scheda. In Impostazioni vedi
  dove gira ("scheda video (23 strati di 42)"); se qualcosa non va, SAM torna da solo al processore.
- **MetaTrader 5.**
  - «**compila l'EA**» (quello che gli hai dato con Allega o l'ultimo che ha scritto): lo copia in MetaTrader
    (Experts › SAM), lo compila con MetaEditor e, se ci sono errori, **li corregge da solo e ricompila** (fino a 5 giri),
    salvando le versioni nuove accanto (robot_v2.mq5…). Quando SAM scrive un EA, lo compila subito.
  - «**fai il backtest su GBPUSD H4 dal 2023 al 2024**» (anche «sull'oro», «ultimi 6 mesi», «ogni tick», «con deposito
    5000»): apre lo Strategy Tester, aspetta il rapporto, lo salva in Documenti › SAM e te lo riassume con un giudizio.
    MetaTrader va chiuso prima (lo apre e lo chiude SAM).
- **Prova i programmi.** «**esegui il programma**» / «provalo»: avvia l'ultimo programma o progetto Python; se dà errore
  lo **corregge e riprova** (fino a 4 volte); se mancano librerie le installa. Le pagine HTML le apre nel browser. Serve
  Python: se non c'è, Impostazioni › Modelli › Python 3.12 (11 MB).
- **La vista.** Scarica "La vista di Gemma 4" (1 GB). Poi: «**cosa c'è sullo schermo?**», «**spiegami questo errore**»,
  «leggi il grafico sullo schermo», oppure un'immagine con Allega.
- **Voce naturale.** Scarica "Piper" e "Voce di Paola" (~85 MB): SAM parla con una voce neurale italiana molto più
  umana, sempre senza internet (si può tornare a quella di Windows in Impostazioni).
- **Capisce meglio.** "Voce italiana precisa (Vosk grande)", 1,2 GB: sbaglia molto meno le parole (usa ~3 GB di RAM).
- **Tasto rapido**: **Ctrl+Spazio** da qualsiasi programma apre SAM e lo mette in ascolto.
- **I tuoi documenti.** Impostazioni › I miei documenti › Aggiungi cartella › Leggi adesso. Poi «**cerca nei miei
  documenti** la polizza auto», «cosa dicono i miei documenti sul contratto d'affitto»: risponde con i file come fonti
  (si toccano e si aprono). «Rileggi i miei documenti» per aggiornare.
- **Stessa memoria su telefono e PC** (stesso Wi-Fi). Sul PC: Impostazioni › Telefono mostra un codice di 6 cifre.
  Al telefono (aggiorna a SAM 1.18): «**SAM, collega il computer con il codice** 4 8 2 9 1 3» la prima volta, poi
  «**SAM, sincronizza la memoria col computer**» quando vuoi: i ricordi nuovi passano in tutte e due le direzioni
  (niente si cancella). La prima volta Windows chiede il permesso per la rete: consenti le reti private.
- **Consiglio per il computer:** porta la RAM a **32 GB** (2 × 16 GB DDR4 3200 SO-DIMM; il GF63 ha due slot). Con 12 GB
  SAM deve togliere il cervello per disegnare; con 32 GB tiene tutto caricato insieme. E una cuffia con microfono.

## SAM per PC (Windows) — novità
- **Cos'è.** Lo stesso SAM del telefono sul tuo MSI con Windows 11: stesso cervello dei comandi, stessa memoria (nuova,
  sul PC), stesse skill. Tutto gira sul computer; internet solo per scaricare i modelli e per le ricerche che chiedi tu.
- **Installazione.** Estrai **SAM-PC.zip**, apri **SAM.exe** (se Windows avvisa: "Ulteriori informazioni" › "Esegui
  comunque"). Si apre Impostazioni: **Scarica tutto** (voce 48 MB, Gemma 4 E4B 5 GB, Qwen2.5 Coder 7B 4,7 GB, disegni
  2,3 GB; i download riprendono da dove si fermano). SAM si mette sul desktop; in Impostazioni puoi farlo partire con Windows.
- **Voce.** Di' «SAM» e parla (microfono del PC, Vosk), oppure tocca **Parla**; risponde con la voce italiana di Windows.
  Se scrivi, risponde solo sullo schermo (si cambia in Impostazioni).
- **Schermata** come Kimi: risposte formattate, fonti coi link, schede Piano/Ricerche/File (Apri, Apri la cartella),
  anteprima dei disegni, tasto destro su un messaggio per **Copia** o **Copia il codice**. Trascina un file nella finestra
  o usa **Allega** (PDF, Word, Excel, testo, codice).
- **Comandi del PC:** «apri Chrome / MetaTrader / il blocco note», «apri la cartella download», «fai uno screenshot»,
  «alza/abbassa il volume», «metti in pausa», «blocca il PC», «spegni il PC tra 10 minuti», «annulla lo spegnimento»,
  «riavvia il computer», «metti in sospensione», timer e sveglie (con notifica vicino all'orologio).
- **Programmazione complessa** (col cervello per programmare, Qwen2.5 Coder 7B):
  - «**crea un progetto per** gestire le spese di casa con grafici», «**fammi un gioco completo** di snake in Python»:
    SAM scrive tutti i file (anche più cartelle) e un LEGGIMI con le istruzioni, in Documenti › SAM › Progetti;
  - dai un file di codice con Allega (es. il tuo .mq5) e di' «**correggi questo codice**», «**migliora il file aggiungendo**
    il trailing stop»: SAM salva la versione nuova accanto (robot_v2.mq5) senza toccare l'originale;
  - skill **Progetto completo** nel pannello Skill.
- **Ricerca con fonti, ricerca approfondita (PDF), presentazioni (PowerPoint + PDF), documenti, siti**: come sul telefono,
  in Documenti › SAM.
- **Disegni e video**: Stable Diffusion 1.5 col "disegno veloce" (LCM: 4-8 passi) sul processore: circa mezzo minuto-un
  minuto a immagine; i video MP4 da 8 secondi in Video › SAM.
- **Limiti veri:** la scheda video GTX 1650 per ora non si usa (servirebbe un compilatore che da qui non posso
  scaricare): tutto gira sul processore i7, che per Gemma 4 E4B e Qwen Coder 7B va bene (qualche parola al secondo).
  Niente vista delle foto e niente riconoscimento della tua voce sul PC (ci sono sul telefono).

## Novità della 1.17 — le skill (come in Spinta)
- In alto c'è il tasto **⚡ Skill**: si apre un pannello con 18 funzioni pronte. Tocchi una scheda, scrivi cosa ti serve
  e tocchi **Vai**. La risposta arriva nella conversazione (con la formattazione), e tenendo premuto il fumetto la copi.
  Tutto gira sul telefono, col cervello di SAM; internet solo per le due skill di ricerca.
- **Lavori sul testo** (il cervello con istruzioni fatte apposta):
  - 📝 **Riassumi**: il punto principale e i punti chiave;
  - 🌍 **Traduci**: in qualsiasi lingua (scrivi la lingua nel primo campo; se lo lasci com'è, inglese);
  - ✍️ **Correggi e migliora**: grammatica, chiarezza, scorrevolezza, senza cambiare il senso;
  - ✉️ **Scrivi un messaggio**: email, WhatsApp, lettere, col tono che scegli (cordiale, formale, deciso…);
  - 💡 **Spiega facile**, 🧠 **Idee** (8 proposte), 🗂️ **Piano passo passo**, ⚖️ **Pro e contro**;
  - 🔍 **Spiega o correggi codice**: incolli il codice e SAM spiega cosa fa, trova gli errori e scrive la correzione
    (usa il cervello per programmare, se c'è).
- **Lavori che SAM fa da solo** (le stesse cose che puoi chiedere a voce):
  - 🔎 Cerca sul web (con le fonti) e 📚 Ricerca approfondita (rapporto PDF);
  - 📊 Presentazione (PowerPoint e PDF), 💻 Scrivi codice, 🌐 Sito web, 📈 Expert Advisor MQL5;
  - 🎨 Disegna e 🎬 Video;
  - 📎 Analizza un file (PDF, Word, Excel, foto).
- **Il cervello dentro l'APK?** Non c'è, e per ora va bene così: Gemma 4 E4B (3,7 GB) non si può scaricare da dove preparo
  l'app e, messo dentro l'APK, occuperebbe il doppio (7,5 GB), perché Android lo deve copiare fuori per usarlo. Si scarica
  dentro SAM, una volta sola, col Wi-Fi, come adesso.

## Novità della 1.16 — come Kimi
- **La schermata nuova, come l'app di Kimi.** La conversazione ora è a fumetti: i tuoi a destra (blu), quelli di SAM
  a sinistra, con **titoli, grassetto, elenchi e codice formattati** e i numeri delle fonti [1] colorati. In alto il
  nome, lo stato, i tasti **Ferma** e **⚙︎**; in basso una barra unica: 📎 (dai un file), il campo per scrivere,
  🎙️ (parla) e ➤ (invia). Sopra le risposte compaiono le schede **🧭 Piano**, **🔎 Ricerche**, **📚 Fonti** (coi link
  che si toccano) e i **file** creati (toccali per aprirli). Il "💭 Ragionamento" si apre e si chiude toccandolo.
  Tieni premuto un fumetto per **copiare** il testo.
- **Ricerca sul web con le fonti.** "SAM, **cerca su internet** chi ha vinto il campionato", "**cercami** le ultime
  notizie sulle auto elettriche", "**fai una ricerca** sul prezzo del rame", oppure "cercalo" dopo una domanda.
  SAM cerca (DuckDuckGo, e se non risponde Wikipedia), legge le pagine migliori, e il cervello risponde **citando le
  fonti** [1], [2]; sotto trovi l'elenco delle fonti coi link. A voce legge la risposta senza i numeri.
  Il browser si apre solo se lo dici: "cerca **su google** …", "cerca **nel browser** …".
- **Ricerca approfondita** (come Kimi Researcher). "SAM, **fai una ricerca approfondita** sulle auto elettriche",
  "**fammi un rapporto** sul riscaldamento globale", "**approfondisci** la storia di Roma". SAM prepara 3-4 ricerche
  diverse, legge fino a 10 pagine, si appunta i fatti con la loro fonte e scrive un **rapporto con sintesi, sezioni,
  conclusioni e fonti**, salvato in **PDF** in Download › SAM. Ci mette qualche minuto; poi ti legge la sintesi.
- **Modalità agente: presentazioni e siti.**
  - "SAM, **fammi una presentazione** sulle energie rinnovabili", "**crea 10 slide** sulla storia di Roma",
    "**preparami cinque diapositive** sul sistema solare". SAM mostra il piano, scrive la scaletta e salva
    **PowerPoint (.pptx) e PDF** in Download › SAM (16:9, titolo su sfondo scuro, una slide per argomento, fino a 15).
    Con "… **con dati aggiornati**" / "… **cercando su internet**" prima cerca sul web e mette le fonti.
  - "SAM, **fammi un sito** per la mia pizzeria", "**crea una pagina web** che…": una pagina HTML completa e moderna
    (si apre col browser).
- **I video.** "SAM, **fammi un video di** un faro sul mare in tempesta", "**crea un'animazione di** …", "**genera un
  filmato su** …". I veri modelli che creano video (come quelli di Kimi, Sora o Veo) pesano decine di giga e girano su
  computer enormi: sul telefono non ci stanno. Allora SAM fa così: **disegna la scena** (Stable Diffusion, sul telefono)
  e poi la **anima** come nei documentari, con zoom o panoramica lenti e dissolvenze, in un **video MP4 di 8 secondi**
  (720×720) che va nella Galleria, cartella SAM (c'è anche l'immagine da sola). Il movimento lo sceglie lui: panoramica
  per i paesaggi, salita per torri e alberi, zoom per il resto.
- **Il cervello di Kimi?** Kimi K2 è un modello enorme (mille miliardi di parametri): non sta su nessun telefono. SAM
  usa le stesse idee (ricerca con fonti, ricerca approfondita, agente che crea file) col cervello che sta sul tuo
  telefono, quindi è più lento e le risposte sono più semplici, ma tutto resta tuo.

## Novità della 1.15
- **SAM sceglie da solo il modello giusto per ogni lavoro.**
  - **Chiacchierare, rispondere, riassumere:** il cervello principale (quello che hai scelto, di solito Gemma 4 E4B).
  - **Scrivere codice** ("scrivimi un programma che…"): passa da solo al cervello per programmare (Qwen2.5 Coder). Se
    non ce l'hai lo scarica da solo, solo col Wi-Fi (2,1 GB; senza Wi-Fi aspetta), e intanto il codice lo scrive il
    cervello di sempre.
  - **Guardare** (fotocamera, foto): se il cervello di adesso non vede, passa da solo a un Gemma 4 che vede (se è sul telefono).
  - **Disegnare:** usa il modello delle immagini; se manca lo scarica da solo col Wi-Fi.
  - Finito il lavoro, quando SAM resta libero per tre minuti torna da solo al cervello principale. Se dopo il codice
    chiedi "spiegamelo", risponde lo stesso cervello che l'ha scritto.
  - Sullo schermo una riga "Info" dice quale cervello sta usando e perché. "SAM, **che cervello usi?**" te lo dice a voce.
  - Cambiare cervello ci mette qualche secondo: i GGUF partono quasi subito, Gemma 4 E4B ci mette di più.
  - "SAM, **usa sempre lo stesso cervello**" spegne la gestione automatica (e i download automatici); "SAM, **gestisci tu
    i modelli**" la riaccende. Parte accesa.
- **Cosa c'è dentro SAM e cosa si scarica.** Dentro l'app ci sono tutti i *motori*: LiteRT-LM (Gemma, .litertlm e
  .task), llama.cpp (GGUF), l'interprete TFLite (disegni), Vosk (voce) e il vocabolario per i disegni. I *modelli* invece
  sono file grandi (Gemma 4 E4B 3,7 GB, cervello per programmare 2,1 GB, disegni 1 GB) e non possono stare
  nell'app: SAM li scarica da solo, una volta sola, col Wi-Fi, quando servono. Poi tutto gira sul telefono, senza internet.

## Novità della 1.14
- **Altri tipi di cervello.** Oltre ai file **.litertlm**, SAM ora usa anche:
  - i **GGUF**, il formato di llama.cpp, LM Studio e Ollama: il più diffuso su Hugging Face. Vanno bene Qwen
    (2, 2.5, 3, 3.5), Llama, Mistral, Phi, DeepSeek, Gemma 1-2-3, SmolLM, Granite, GLM e molti altri. Li fa girare
    un secondo motore dentro SAM (llama.cpp, già ottimizzato per il tuo processore). Su Hugging Face scegli il file
    "Q4_K_M": è il compromesso migliore tra qualità e velocità. Con 12 GB di RAM vanno bene modelli fino a 7-8B.
  - i **.task** (il formato di MediaPipe / Google AI Edge).
  Si importano come sempre: ⚙︎ › 4 › **Importa il file del cervello**. SAM riconosce il formato dal contenuto e ti
  spiega se il file non va (per esempio i .safetensors di PyTorch: serve la versione GGUF). Memoria, regole, file,
  autoapprendimento e tutto il resto funzionano uguali con tutti i cervelli.
  Limiti: un GGUF diviso in più pezzi ("-00001-of-00003") non si può usare; i GGUF che vedono le immagini per ora
  non guardano le foto (per la fotocamera resta Gemma 4).
- **Il cervello per programmare.** "SAM, **usa il cervello per programmare**": se non ce l'hai, ti propone di scaricare
  col Wi-Fi Qwen2.5 Coder 3B (2,1 GB, GGUF). Per tornare a Gemma: "SAM, usa l'altro cervello".
- **Scrivere codice.** "SAM, **scrivimi un programma in Python che** …", "**crea una pagina web con** …", "**fammi un
  expert advisor MQL5 che** …", "**scrivi uno script bash che** …", "**fammi una calcolatrice**". SAM scrive il codice
  (lo vedi crescere sullo schermo) e lo salva in Download › SAM con l'estensione giusta: .py, .html, .js, .kt, .java,
  .mq5, .mq4, .sh, .sql, .php, .cpp, .c, .cs, .bas (macro di Excel), .ino (Arduino)… Se non dici il linguaggio usa
  Python, e per pagine, app e calcolatrici HTML (si apre col browser). Poi "apri il file" / "condividi il file".
- **Disegnare.** "SAM, **disegna un faro sul mare al tramonto**", "**crea un'immagine di** …", "**fammi un disegno di**
  …", "**genera una foto realistica di** …". Tutto sul telefono, senza internet, con Stable Diffusion:
  - la prima volta ti chiede di scaricare il modello per disegnare: circa **1 GB**, una volta sola, solo col Wi-Fi
    (oppure "SAM, **scarica il modello per disegnare**"); quando è pronto te lo dice;
  - la tua descrizione la traduce in inglese il cervello (il modello delle immagini capisce solo l'inglese) e la vedi
    sullo schermo;
  - un disegno 512×512 ci mette **qualche minuto** (lo stato in alto dice a che passo è e quanto manca); per fermarlo
    tocca **Parla**. "**in alta qualità**" = più passi e più tempo; "**veloce**" = una bozza;
  - l'immagine finisce nella **Galleria, cartella SAM**, e si apre da sola; "**condividi il file**" la manda;
  - è Stable Diffusion 1.4: va bene per paesaggi, oggetti, stili pittorici; le mani e le scritte spesso vengono male.
    Licenza del modello: CreativeML OpenRAIL-M (uso personale libero, con i soliti divieti sugli usi dannosi).
  - se la memoria è poca, mentre disegna SAM mette da parte il cervello e poi lo ricarica da solo.

## Novità della 1.13
- **Caricare un file a SAM: tasto 📎.** Nella schermata principale, a sinistra della casella "Scrivi a SAM…", c'è la
  graffetta: tocca, scegli il file dal telefono (o da Drive) e SAM lo apre come quelli che gli condividi. Vanno bene
  PDF, Word, Excel, PowerPoint, testi e foto. Poi chiedi: "leggilo", "riassumilo", "cosa dice sul pagamento?".
- **Farti preparare dei file da scaricare.** Finiscono nella cartella **Download › SAM** del telefono (la vedi
  dall'app File) e arriva una notifica: toccala e il file si apre. A voce o scrivendo:
  - "**salvalo in un file**" / "**salvamelo in PDF**" / "**scaricalo**": la sua ultima risposta;
  - "**salva la conversazione**" (anche "in PDF"): le cose che vi siete detti nelle ultime 24 ore;
  - "**salva il testo del documento**" (anche "in PDF"): il testo del documento aperto, per esempio per
    trasformare un Word in un file di testo;
  - "**scrivimi un documento su** …", "**fammi un PDF con** …", "**scrivi una lettera per** …": il cervello scrive il
    documento (lo vedi crescere sullo schermo, non lo legge a voce), poi lo salva. Ci mette un minuto o due; per
    fermarlo tocca **Parla**. Dove servono dati che non conosce lascia uno spazio da compilare, per esempio [data].
  - "**apri il file**" riapre l'ultimo; "**condividi il file**" lo manda con WhatsApp, email, Drive…
- Il PDF è semplice (titolo e testo, numeri di pagina); se vuoi modificarlo, chiedi il file di testo.
- Anche i file caricati con 📎 arrivano a SAM direttamente, senza far ricomparire l'avviso del microfono.

## Novità della 1.12.1
- **Scrivere non fa più comparire l'avviso del microfono.** Ogni messaggio scritto rimetteva SAM "in primo piano
  col microfono", e HyperOS mostrava di nuovo la notifica. Ora quello che scrivi arriva direttamente a SAM già acceso.

## Novità della 1.12
- **Corretto "Ho avuto un problema con il mio cervello locale".** Il cervello era sano: il guasto era nel modo in
  cui SAM gli parlava. Quando una risposta veniva fermata a metà (toccavi Parla, gli scrivevi mentre parlava, o la
  risposta era troppo lunga) il motore teneva quella conversazione "annullata", e da lì ogni domanda tornava vuota
  ("Non saprei…") o con l'errore. Capitava anche quando la memoria del discorso si riempiva dopo una lunga
  chiacchierata. Adesso in tutti e due i casi SAM riparte da solo con una conversazione nuova, mettendo davanti
  alla domanda le ultime battute: il filo del discorso non si perde.
- **Se il cervello si inceppa lo stesso, si rimette in piedi da solo.** Ogni errore viene riprovato una volta con
  una conversazione pulita; se succede due volte di fila SAM ricarica il cervello in sottofondo ("lo sto
  riavviando, riprova tra qualche secondo") e intanto i comandi funzionano. Sullo schermo compare una riga
  "Dettaglio: …" con il motivo tecnico: se il problema torna, mandamela.
- **Autoapprendimento.** Quando SAM è libero da qualche minuto, il suo cervello rilegge le cose che gli hai detto
  e si segna da solo quelle importanti sulla tua vita (famiglia, lavoro, gusti, abitudini, posti, animali,
  progetti), per esempio "Mia figlia fa danza il martedì" o "Il sabato vado a pescare a Pozzuoli". Sullo schermo
  compare "Memoria: ho imparato da solo «…»", e dalla conversazione dopo le usa.
  - Impara **solo dalle tue parole**: ogni cosa deve stare in una frase che hai detto davvero. Se il cervello
    si inventa qualcosa (per esempio "suona il violino" quando non l'hai mai detto), SAM lo scarta.
  - Non si segna le cose passeggere ("oggi sono stanco", "domani piove"), né domande e comandi, né quello che sa già.
  - "SAM, **cosa hai imparato da solo?**" te le elenca. Se una è sbagliata: "SAM, **dimentica** …".
  - "SAM, **smetti di imparare da solo**" lo spegne; "SAM, **impara da solo**" lo riaccende. Parte acceso.
  - Il modello in sé (i miliardi di numeri) non cambia: sul telefono non si può riaddestrare. Quello che impara
    diventa un ricordo che il cervello rilegge ogni volta.
  - Se gli parli mentre sta rileggendo si ferma subito e risponde a te; la domanda subito dopo una rilettura può
    metterci qualche secondo in più.

## Novità della 1.11
- **Puoi scrivere a SAM.** Quando non puoi parlare (una riunione, un posto silenzioso) usa la casella **“Scrivi a SAM…”**
  in basso nella schermata principale e tocca **Invia** (o il tasto invio della tastiera). Quello che scrivi vale come se
  l'avessi detto: comandi ("che ore sono?", "metti una sveglia alle 7", "chiama Mario"), domande, chiacchierate,
  e risposte alle sue domande ("cosa scrivo?" → scrivi il testo del messaggio).
- **Risponde solo sullo schermo.** Quando scrivi SAM non parla e non vibra: la risposta compare nella conversazione.
  Appena tocchi **🎙️ Parla** o gli dici "SAM", torna a rispondere a voce.
- Puoi anche usare la parola "SAM" davanti ("SAM, che ore sono?"): si ignora. Scrivere "basta" chiude la
  conversazione. Se scrivi mentre sta rispondendo, lo zittisce e risponde alla nuova frase.
- Con la tastiera aperta la sfera si nasconde, così resta più spazio per leggere.
- Limiti: per **imparare la voce** serve parlare (è la voce che impara); se SAM ti fa una domanda e non rispondi,
  dopo tre minuti la lascia cadere.

## Novità della 1.10.1
- **Ti riconosce la voce più facilmente.** La soglia per dire "sei tu" è più larga: di circa 3 punti percentuali
  sulle frasi lunghe e di 5 su quelle brevi (i comandi), e vale subito anche per la voce che avevi già insegnato,
  senza rifare l'addestramento. Le voci di altre persone continuano a non passare per la tua.
- **Si adatta da solo a come parli davvero.** Se SAM non ti riconosce e tu tocchi **🎙️ Parla** e dici il comando,
  quella frase è sicuramente tua: SAM allarga un poco la soglia e sposta l'impronta verso la tua voce di tutti i
  giorni (microfono, distanza, stanza). Dopo due o tre comandi detti così di solito non serve più toccare.
  Resta un tetto: la soglia non si allarga all'infinito, e una voce che non somiglia affatto alla tua (la TV
  accesa) non cambia niente.
- Per vedere i numeri: "SAM, **mi riconosci dalla voce?**" ti dice "somiglia all'X per cento, mi serve il Y".

## Novità della 1.10
- **Un carattere confidenziale.** SAM ti parla come un amico di vecchia data: ti dà del tu, frasi semplici e calde,
  qualche battuta leggera, niente più frasi da centralino ("certamente", "sono qui per aiutarti").
- **Una voce più naturale, scelta da te.** SAM usa le voci italiane del telefono che funzionano senza internet
  (con il motore di Google di solito ce ne sono diverse): le provi e scegli quella che ti piace. A voce:
  - "**cambia voce**" (ti chiede "ti piace?": "sì" la tiene, "no" o "un'altra" passa alla prossima),
    "**torna alla voce di prima**", "**che voce usi?**";
  - "**parla più piano**" o "parla più lentamente", "**parla più veloce**", "parli troppo veloce";
  - "**voce più grave**", "voce più acuta"; "**voce normale**" rimette tutto com'era.
  Oppure in ⚙︎ › **9 · La voce di SAM**: senti le voci una per una e regoli velocità e tono.
  "Abbassa la voce" e "parla più forte" restano il volume.
- **SAM cambia da solo il proprio modo di fare.** Il cervello vero (i pesi del modello) non si può riaddestrare
  sul telefono: servirebbero computer con schede grafiche potenti. Quello che SAM cambia da solo sono le regole con
  cui lo usa:
  - quando ti lamenti, ti propone lui la regola per correggersi: "**parli troppo**" → "Hai ragione. Vuoi che d'ora in
    poi segua questa regola: «Rispondi in modo breve, con poche frasi»?", e la tiene solo se dici sì. Funziona con
    "sei troppo formale", "sei noioso", "mi fai sempre domande", "mi chiami sempre per nome", "sembri un robot"…;
  - per le altre lamentele ("**non mi piace quando mi interrompi**", "non mi piace il tuo tono") la regola la scrive
    il cervello stesso, e SAM ti chiede se tenerla;
  - se in una chiacchierata gli chiedi di cambiare modo di fare, il cervello si scrive da solo la regola e SAM ti
    chiede "Vuoi che me lo segni come regola?";
  - "**cambia il tuo carattere**" (ti chiede come), "**diventa più simpatico**", "sii più dolce";
  - "**puoi modificare il tuo cervello?**": te lo spiega lui, onestamente.
  Le regole le vedi con "quali regole ti ho dato?" e le togli con "dimentica la regola …".
- **Più o meno fantasia.** "**Sii più preciso**" (o "attieniti ai fatti", "non inventare"): il cervello sceglie le
  parole in modo più sicuro e inventa meno. "**Usa più fantasia**": risposte più varie. "Fantasia normale" per tornare.
- **Più di un cervello.** Dopo un'importazione ti chiede se tenere anche quello di prima, così puoi cambiare a voce:
  "**usa il cervello veloce**", "**usa il cervello più intelligente**", "**usa l'altro cervello**", "**che cervello
  usi?**". Se il cervello che chiedi non c'è, ti propone di scaricarlo col Wi-Fi. "**Sei troppo lento**" ti propone
  il cervello più veloce.
- **Gli aggiornamenti da soli, col Wi-Fi** (punto 13): una volta alla settimana SAM guarda se c'è una Wikipedia più
  nuova della stessa versione della tua: se c'è la scarica, la controlla tutta e la usa al posto della vecchia. Guarda
  anche se è uscita una versione nuova del file del cervello che usi: se sì la scarica, la controlla e poi ti chiede
  "**la installo?**" (se il nuovo non parte, rimette da solo il vecchio). Le novità te le dice la prima volta che lo
  chiami. A voce: "**ci sono aggiornamenti?**", "**a che punto è lo scaricamento?**", "**non aggiornarti più da
  solo**", "aggiornati da solo".
- Il cervello consigliato e la Wikipedia li scarica SAM stesso, col Wi-Fi (prima serviva il browser, che resta come
  alternativa).

## Novità della 1.9.1
- **SAM non dice più che non sa leggere i file o l'enciclopedia.** Era il cervello IA che se lo inventava ("non ho la
  capacità di leggere file", "copialo e incollalo qui"). Ora sa cosa c'è sul telefono, e se una frase del genere gli
  scappa lo stesso SAM la toglie e dice come stanno le cose: i file si leggono con **Condividi → Leggi con SAM**,
  e l'enciclopedia la usa da solo quando serve.
- **"Hai l'enciclopedia?", "la leggi Wikipedia?", "ce l'hai Wikipedia?"** risponde SAM: "Sì, ce l'ho: Wikipedia in
  italiano di settembre 2026, con 1.900.000 voci…", oppure ti dice che non la vede ancora e dove si mette.
  Quando la carichi te lo conferma una volta ("Enciclopedia pronta: …") e in basso compare "· enciclopedia".
- **"Sai leggere i file?"**: "Sì. Da qualsiasi app…" con le istruzioni.
- **"Perché non rispondi?"**, "ti stavo chiamando, perché non mi senti?": SAM spiega il motivo vero. Mentre pensa o
  parla non può ascoltare (per interromperlo tocca **Parla**); se è aperta la Fotocamera è lei a tenere il microfono.

## Novità della 1.9
- **L'enciclopedia sul telefono.** Metti Wikipedia in italiano (il file di Kiwix, senza internet) e SAM la usa da solo:
  - alle domande come "chi era Garibaldi?", "cos'è un buco nero?", "dove si trova Matera?", "parlami della Torre di Pisa",
    "chi ha scritto la Divina Commedia?", "quanto è alto il Monte Bianco?" SAM cerca prima la voce di Wikipedia e il cervello
    risponde partendo da quel testo, invece che dalla sua memoria. Sullo schermo compare la riga "Enciclopedia: «…»" con la
    voce usata;
  - "**cosa dice l'enciclopedia su Napoli?**", "**cerca Garibaldi su Wikipedia**", "**leggimi la voce Matera**" te ne legge
    l'inizio così com'è, dicendo di che mese è la sua copia;
  - le persone di casa non le cerca: "chi è Maria?" o "chi è Ciruzzo?" (se nei ricordi è tuo cugino) restano al cervello.
  - Non si aggiorna da sola (SAM non va su internet): quando la copia ha più di sei mesi te lo ricorda, e ne scarichi una nuova.
    (Dalla 1.10 invece si aggiorna da sola, col Wi-Fi: punto 13.)
  Come metterla: punto 11.

## Novità della 1.8
- **Il cervello più intelligente per il tuo telefono.** Con 12 GB di RAM SAM ora consiglia **Gemma 4 E4B**: capisce,
  ragiona e segue le istruzioni nettamente meglio dell'E2B (nei test di Google risponde giusto il 69% delle volte contro
  il 60% su domande di cultura generale in più lingue, e segue le istruzioni quasi il doppio meglio). Risponde più
  lentamente e pesa 3,7 GB. Come passarci: punto 3 qui sotto.
- **Risposte esatte, senza passare dal cervello**, dove i cervelli piccoli sbagliano più spesso:
  - **date e feste**: "quanto manca a Natale?", "quando è Pasqua?", "quando cade Pasqua nel 2030?", "quanti giorni
    mancano al 15 agosto?", "quanto manca all'estate?", "che giorno sarà tra 10 giorni?", "che giorno era il 25 dicembre
    1990?", "quanti giorni ci sono tra Natale e Capodanno?", "quanti giorni sono passati da Ferragosto?", "quanti anni ha
    chi è nato nel 1985?", "il 2028 è bisestile?", "quanti giorni ha febbraio?", "che settimana è?".
    Conosce Natale, Capodanno, la Befana, San Valentino, Pasqua e Pasquetta, Carnevale, la festa della mamma e del papà,
    il 25 aprile, il primo maggio, il 2 giugno, Ferragosto, San Gennaro, Halloween, i Santi, l'Immacolata e l'inizio delle
    stagioni (equinozi e solstizi calcolati al minuto);
  - **che ore sono nel mondo**: "che ore sono a New York?", "a Tokyo che ore sono?", "che fuso orario ha il Giappone?"
    (più di 200 città e nazioni, con l'ora legale giusta);
  - **conversioni**: miglia e chilometri, pollici, piedi, libbre, once, galloni, gradi Fahrenheit, nodi, ettari, acri,
    cucchiai… ("quanti chilometri sono 10 miglia?", "30 gradi in Fahrenheit", "un chilo e mezzo in libbre");
  - **IVA, sconti e conti**: "100 euro più IVA", "122 euro senza IVA", "sconto del 20% su 50 euro", "che percentuale è
    30 su 120?", "dividi 100 euro tra 3 persone";
  - **lettere**: "quante lettere ha la parola ciao?", "come si scrive Gennaro?" (G, E, doppia N, A, R, O);
  - **a caso, davvero**: "testa o croce", "tira un dado", "tira 2 dadi", "dammi un numero a caso tra 1 e 100",
    "scegli tu tra pizza e sushi".
- **Il cambio delle valute non lo inventa**: senza internet non lo può sapere, quindi te lo dice e ti propone di
  cercarlo con il browser.
- "Che ore sono?" alle 1 e a mezzanotte ora si dice bene ("è l'una", "è mezzanotte e 5"), e "e 5" invece di "e 05".

## Novità della 1.7.2
- **"Sai chi sono?" lo risolve SAM**, non più il cervello IA (che non sa chi parla e rispondeva a vuoto). Vale anche
  per "mi riconosci?", "ora mi riconosci?", "mi riconosci dalla voce?", "chi sono io?", "con chi stai parlando?", e anche
  quando il riconoscitore sente "mi riconosco". Risponde con il tuo nome e, se conosce la tua voce, ti dice se
  ti riconosce anche da quella e quanto somiglia: "Sei Sam: ti riconosco anche dalla voce, somiglia all'82 per cento."
- "**Mi senti?**", "**ci sei?**": risponde subito "Sì, ti sento" / "Sì, ci sono".
- **Niente più "Non te l'ha ancora detta." a ogni frase.** Era un pezzo delle istruzioni del cervello ("digli che non
  te l'ha ancora detta") che il cervello piccolo ripeteva parola per parola, anche a sproposito: tolto, insieme a
  un'altra frase scritta nello stesso modo.
- **Se il cervello ridà la stessa risposta a domande diverse**, SAM se ne accorge: dice "Scusa, mi sono ripetuto:
  riparto da capo", toglie quelle risposte dal filo del discorso e il cervello riparte pulito alla domanda dopo.

## Novità della 1.7.1
- **Se non ti riconosce la voce, non resti chiuso fuori** (con "ascolta solo me" attivo):
  - tocca **🎙️ Parla**: per due minuti, e per tutta la conversazione cominciata così, SAM ti ascolta senza
    controllare la voce (chi ha in mano il telefono sei tu);
  - se poco prima non ti aveva riconosciuto, appena tocchi Parla ti chiede: "**Vuoi che la impari di nuovo?**".
    Rispondi "sì" e ripeti le sei frasi: da lì ti riconosce come parli adesso. Se rispondi "no", ti ascolta e basta;
  - quando non ti riconosce **te lo dice** (prima, dopo la prima volta, restava zitto e sembrava che non ti
    sentisse) e sullo schermo scrive quanto la voce somiglia alla tua e quanto serve, per esempio
    "Info: Non ho riconosciuto la voce di «accendi la torcia» (somiglia al 52%, serve il 58%)";
  - "SAM, **mi riconosci?**" (o "sai chi sono?") risponde sempre, anche quando non ti riconosce, con la percentuale;
  - sullo stato in alto compare "**solo la tua voce**" quando è attivo;
  - se il modello delle voci non riesce a partire, SAM risponde a tutti invece di non rispondere a nessuno.
- **Il microfono si riprende da solo.** Se Android lo toglie a SAM (una chiamata, un'altra app, il sistema audio
  che riparte), SAM lo riapre da solo dopo pochi secondi: prima poteva restare sordo finché non lo riavviavi.
  Ogni 10 secondi controlla anche di non essere rimasto bloccato a parlare o a pensare.
- Se il microfono resta muto per più di un minuto (un'altra app lo tiene, o Android non lo lascia a SAM in
  sottofondo) arriva la notifica "**SAM non riesce a sentirti**": toccala e SAM riparte. Anche aprire SAM o
  toccare Parla rimette a posto il microfono.
- Tolta la riaccensione dal servizio di accessibilità (aggiunta nella 1.6.5): acceso in sottofondo, Android
  poteva non dargli il microfono e SAM restava sordo. Se Android chiude SAM arriva la notifica
  "**SAM si è fermato**": toccala e riparte, con il microfono.

## Novità della 1.7
- **Ragiona quando glielo chiedi.** "SAM, **pensaci bene**: conviene il treno o la macchina per Roma?",
  "**ragionaci** e dimmi…". Prima di rispondere scrive un ragionamento passo per passo, seguendo il metodo che
  usa Claude (che ha scritto SAM):
  1. cosa chiedi davvero;
  2. cosa è sicuro e cosa no;
  3. i calcoli fatti un passaggio alla volta e ricontrollati;
  4. un'altra possibilità da scartare;
  5. la conclusione, con quanto ne è sicuro.

  Il ragionamento lo vedi sullo schermo ("Ragionamento: …"), poi ti risponde a voce. Ci mette di più
  (qualche decina di secondi): per questo lo fa solo quando lo chiedi. "**Pensaci meglio**" dopo una risposta
  rifà la stessa domanda, ragionando.
- **Impara dalle correzioni.** Se una risposta è sbagliata, di': "SAM, **sbagliato**, la capitale dell'Australia
  è Canberra" (o solo "sbagliato": ti chiede la risposta giusta). Se la segna come **lezione**: la volta dopo, a
  una domanda simile, il cervello riceve la risposta giusta prima di rispondere. "**Esatto**" o "**bravo**" dopo
  una buona risposta la tengono come esempio. "**Cosa hai imparato?**" · "quali lezioni hai imparato?" ·
  "dimentica la lezione …" · "dimentica tutte le lezioni".
- Se la domanda non è chiara, il cervello ti chiede cosa intendi invece di tirare a indovinare.
- Il cervello resta lo stesso (Gemma, sul telefono, senza internet): non diventa più grande, ma impara da te
  con regole, lezioni, scorciatoie e correzioni.

## Novità della 1.6.5
- **Impara dalle tue istruzioni e le segue**, senza bisogno di un aggiornamento:
  - "SAM, **quando ti dico guarda, apri la fotocamera, guarda e poi chiudi in automatico**": va bene, ed è
    proprio la sua vista. Apre la **sua** fotocamera, scatta, la richiude da sola e ti dice cosa vede. Prima
    questa istruzione diventava una scorciatoia che apriva l'app Fotocamera del telefono: restava aperta e,
    finché non la chiudevi tu, SAM non ti sentiva. Le scorciatoie vecchie di questo tipo si sistemano da sole.
  - **Regole da seguire sempre**: "SAM, **d'ora in poi** rispondimi in modo breve", "non chiamarmi per nome",
    "dammi del lei", "devi sempre…", "non devi mai…", "quando ti chiedo il meteo dimmi anche la temperatura".
    Te la ripete per conferma e la segue in ogni risposta (anche meteo, foto e documenti).
    "**Quali regole ti ho dato?**" le elenca, "**dimentica la regola** …" ne toglie una.
  - **Correggilo e impara**: se non capisce una frase e subito dopo dici "**no, intendevo** accendi la torcia",
    lo fa e ti chiede "Vuoi che d'ora in poi, quando dici «…», faccia questo?". Con "sì", la volta dopo
    capisce quella frase da solo.
  - Le scorciatoie si insegnano anche senza "intendo": "quando dico buonanotte spegni la torcia".
- **Ricorda la conversazione**:
  - "**Ti ricordi cosa abbiamo detto prima?**", "di cosa abbiamo parlato?", "cosa mi hai detto prima?",
    "dove eravamo rimasti?": risponde con quello che vi siete detti. Prima cercava solo tra i ricordi per
    sempre e rispondeva "No, su questo non ho ricordi".
  - La conversazione è salvata sul telefono: se Android chiude SAM (succedeva soprattutto con l'app
    Fotocamera aperta), quando riparte riprende il filo e sullo schermo rivedi le righe di prima. Il cervello
    riceve le ultime battute delle ultime 3 ore; per "di cosa abbiamo parlato stamattina?" arriva a un giorno.
  - Il cervello riparte da capo molto meno spesso: non più quando una risposta era solo lunga, né quando
    gli chiedi di ricordare una cosa.
  - "**Ripeti**", "cosa hai detto?", "non ho capito": ripete l'ultima risposta.
- **Fotocamera**: "**apri la fotocamera**" ora fa guardare SAM, con la sua fotocamera che si chiude da sola.
  L'app Fotocamera del telefono si apre solo per fare foto ("scatta una foto", "fammi un selfie", "apri l'app
  della fotocamera") e SAM ti avvisa che, finché resta aperta, potrebbe non sentirti. Se un'altra app gli
  occupa il microfono, sullo schermo lo vedi scritto.
- **Non scambia più le chiacchiere per comandi**: "perché quando apri la fotocamera non mi rispondi?",
  "non riesco a chiudere WhatsApp", "domani chiamo Marco", "non vedo l'ora di partire", "la batteria della
  macchina è scarica" prima aprivano la fotocamera, tornavano alla home, telefonavano o dicevano l'ora.
  Ora risponde il cervello. Comandi nuovi: "devi / voglio aprire WhatsApp", "fammi luce", "parla più forte",
  "abbassa la voce", "fai uno screenshot", "modalità aereo", "chiamami tra un'ora" (timer).
- Se Android chiude SAM e non lo lascia ripartire da solo, compare la notifica "**SAM si è fermato**":
  toccala e riparte. Con il servizio di accessibilità attivo, SAM prova anche a riaccendersi da solo.

## Novità della 1.6.4
- **La scritta in alto ora è sempre vera.** Prima, se il cervello finiva di caricare mentre la schermata
  di SAM era chiusa (telefono in tasca, un'altra app aperta), al ritorno restava "Carico il cervello IA…"
  finché non toccavi Parla. Ora vedi "SAM pronto", e mentre carica conta i secondi.
- **Il cervello parte più in fretta.** Il motore tiene una copia di lavoro dei pesi (circa 2 GB) che prima
  stava nella cache: la pulizia di Xiaomi e Android la cancellavano, e a ogni avvio il cervello doveva
  rifarla (1–3 minuti). Ora sta in una cartella che non viene svuotata.
- **La prima volta dopo questo aggiornamento ci mette di più**: rifà la copia una volta (1–3 minuti, la
  scritta dice "la prima volta ci mette di più: prepara i pesi"). Dalle volte dopo bastano pochi secondi.
  La vecchia copia nella cache viene cancellata da sola.
- Quando il cervello è pronto, nella conversazione compare "Info: Cervello pronto in … secondi".
- La vista parte sul processore, come fino alla 1.6.1; passa alla scheda grafica solo se serve.

## Novità della 1.6.3
- **Vera modalità conversazione con SAM**: "SAM, **modalità conversazione**", "SAM, **parliamo**",
  "facciamo due chiacchiere", "parliamo di calcio". Da lì non serve più dire "SAM": ti ascolta finché
  non dici "**basta**" (o "fine conversazione", "ciao ciao"), segue il discorso e **i comandi funzionano**
  ("cosa vedi?", "cercalo su internet", "ricordati che…", "accendi la torcia"). Se taci per 45 secondi
  chiede "Ci sei ancora?", poi chiude.
- Prima "modalità conversazione" faceva parlare SAM con **un'altra intelligenza artificiale**: ti chiamava
  "Altra IA", non eseguiva comandi e diceva di non poter vedere. Per quella ora si dice "SAM, parla con
  ChatGPT"; e se lì dentro dici "SAM, apri la fotocamera", SAM esce e lo fa.
- **Segue il filo del discorso**:
  - "sì" sentito come "**sei**" vale sì;
  - "**cercalo su internet**" (o "cercalo", "fai una ricerca") cerca la domanda di prima, non le parole
    "cercalo su internet";
  - il cervello IA riceve le ultime battute, anche quelle dei comandi e delle ricerche;
  - riparte da capo dopo 10 minuti di silenzio, non più dopo 3.
- Niente più "Sono un modello linguistico…" né "non ho la possibilità di cercare su internet" (subito
  prima di proporti la ricerca).

## Novità della 1.6.2
- **Guarda davvero quando glielo chiedi**. Prima, se nella frase c'era "fotocamera" ("guarda con la
  fotocamera", "apri la fotocamera e dimmi cosa vedi", "vedi la fotocamera?") SAM apriva l'app Fotocamera
  e non guardava niente; altre frasi ("dimmi cosa vedi", "mi vedi?", "riesci a vedere?") finivano al
  cervello IA, che rispondeva "non posso vedere". Ora vanno tutte alla vista di SAM.
- "**Mi vedi?**", "**guardami**", "**come sono vestito?**": usa la fotocamera **davanti**.
- "**Hai gli occhi?**", "**perché non vedi?**": risponde SAM e spiega come farlo guardare.
- **La vista del cervello è più robusta**. SAM legge dal file del cervello dove può girare la vista
  (processore o scheda grafica) e ora può usare anche la scheda grafica. Se un modo dà errore o si
  blocca, cambia da solo: dice "Un attimo, provo a guardare in un altro modo" e riprova.
- **Messaggi chiari** quando non va: se Android blocca la schermata della fotocamera ti dice quale
  permesso attivare; se il cervello non ha la vista, ti dice il nome del file.
- Il cervello IA non dice più "non posso vedere": SAM toglie quella frase e ti dice come farlo guardare.
- I comandi per l'app Fotocamera restano: "apri la fotocamera", "scatta una foto", "fai un selfie".

## Novità della 1.6.1
- **Non dice più "non posso memorizzare"**. Prima, se chiedevi di ricordare con parole diverse da
  "ricordati che" ("ti puoi ricordare che…", "riesci a ricordare…", "ti ricordo che…", "sappi che…",
  "segna che…", "salva che…", "voglio che memorizzi…"), la frase finiva al cervello IA: lui per
  abitudine risponde di non avere memoria, e la cosa non veniva salvata. Ora SAM le capisce e le salva.
- **"Ricordatelo"**: racconta una cosa ("la macchina è al terzo piano") e subito dopo di'
  "**ricordatelo**" (oppure "memorizzalo", "tienilo a mente", "te lo ricorderai?"). Va bene anche in
  una frase sola: "il mio cane si chiama Rex, ricordatelo".
- "**Hai una memoria?**", "ti ricordi le cose che ti dico?": ora risponde SAM e spiega come funziona.
- Il cervello IA adesso sa di avere una memoria. Se dice comunque di non averla, SAM toglie quella
  frase e ti dice come fargli ricordare la cosa; se risponde "me lo ricorderò", SAM la salva davvero.

## Novità della 1.6
- **Riconosce la tua voce**: "SAM, impara la mia voce", ripeti sei frasi e da lì ti riconosce; con
  "SAM, ascolta solo me" risponde solo a te e ignora gli altri e la TV. Migliora con l'uso. Vedi il punto 8.
- **Legge i file**: da qualsiasi app tocca **Condividi → Leggi con SAM** (PDF, Word, Excel, PowerPoint,
  testo, foto). Te lo legge, te lo riassume o risponde alle tue domande ("quanto devo pagare?"). Punto 9.
- **Vede con la fotocamera**: "SAM, cosa vedi?", "SAM, leggimi questo", "che pesce è questo?". La foto la
  guarda il cervello sul telefono e non esce mai. Punto 10.
- SAM resta **senza permesso internet**. Il modello delle voci (15 MB, licenza Apache 2.0) è dentro l'app:
  non devi scaricare niente.

## Novità della 1.5
- **Meteo letto ad alta voce**: "SAM, che tempo fa?", "pioverà domani?", "che tempo fa a Roma sabato?".
  SAM resta senza internet: apre l'app del meteo (per un'altra città la pagina delle previsioni nel
  browser), **legge lo schermo** e ti risponde a voce. Vedi il punto 6.
- **Memoria per sempre**: "SAM, ricordati che…" e lo ricorda per sempre, anche dopo gli aggiornamenti.
  Lo **cancella solo quando glielo chiedi**, e solo quella cosa: "SAM, dimentica il dentista"
  (ti chiede conferma). Le note della 1.4 diventano ricordi da sole.
- **Impara da solo**: quello che racconti di te ("mi piace la pizza", "mia moglie si chiama Anna",
  "abito a Casoria") lo ricorda e lo usa quando chiacchierate; se cambia, si aggiorna.
- **Migliora con l'uso**: con due "Mario" in rubrica ricorda quale chiami di solito; impara le
  **scorciatoie** che gli insegni ("quando dico buonanotte intendo spegni la torcia e metti la
  sveglia alle sette") e i **nomi** ("quando dico amore intendo Giulia").
- Nella configurazione c'è la sezione **Ricordi**: li vedi tutti, cancelli quelli che vuoi e puoi
  salvarne una copia sul telefono.
- Corretto "scrivi **ad** Anna…" (prima SAM capiva "d Anna").

## Novità della 1.4
- **Conversazione con un'altra intelligenza artificiale**: "SAM, parla con ChatGPT di calcio" e le due
  IA chiacchierano a voce tra loro. Vedi il punto 5.
- "Mostra sopra altre app": ora SAM compare nell'elenco (prima mancava). I pulsanti non finiscono più
  sotto la barra di navigazione.

## Novità della 1.3
- **Chiamate**: trova il contatto anche se il riconoscitore sbaglia una lettera, se dici solo il nome
  o il cognome, o se in rubrica ci sono accenti ed emoji ("Mamma ❤️"). Con due "Mario" ti chiede
  quale. Puoi dettare il numero ("chiama tre tre tre…"). La chiamata parte anche con SAM in sottofondo.
- **WhatsApp**: "scrivi a Giulia su WhatsApp che arrivo tra dieci minuti" in una frase sola. SAM ripete
  il messaggio, chiede conferma e **preme invia da solo** (serve il servizio di accessibilità).
  Mentre detti un messaggio puoi fare delle pause: SAM aspetta che tu abbia finito.
- **Internet**: "vai su internet", "cerca su internet …", "cercami …", "apri il sito repubblica punto it".
  Quando il cervello non può sapere una cosa (notizie, risultati, prezzi) SAM ti chiede se vuoi cercarla.
- **Numeri detti a parole**: "volume al settanta", "timer di venticinque minuti", "sveglia alle sette
  meno un quarto", "quanto fa dodici per sette" (prima alcuni non funzionavano).
- **Permessi chiari**: nella configurazione vedi ✓ e ✗ accanto a ogni permesso, e c'è il tasto per il
  permesso Xiaomi che blocca le app aperte in sottofondo.

## Novità della 1.2
- **Ti chiede come ti chiami** alla prima accensione, ti chiede conferma e se lo ricorda.
  Puoi anche scriverlo o correggerlo nella configurazione, oppure dire "SAM, il mio nome è …".
- **Niente più "sono Sam e questo è il telefono di Sam"**: istruzioni nuove per il cervello,
  esempi di risposta, freno alle ripetizioni e un filtro che toglie le presentazioni inutili.
- **Conversazione più fluida**: SAM comincia a parlare appena ha pronta la prima frase.
  Se parla troppo, tocca **Parla** e ti ascolta subito.
- **Chiudere**: "chiudi la fotocamera", "chiudi WhatsApp", "esci", "chiudi tutto".
- **Musica**: "pausa", "riprendi", "prossima canzone", "canzone precedente".
- **Sveglie**: "spegni la sveglia", "posticipa la sveglia". Corretto un errore della 1.1 che
  poteva bloccare **sveglie e timer** (mancava un permesso).
- **Cervello scelto in base all'hardware**: SAM legge RAM, processore e spazio libero, ti
  consiglia il cervello giusto e rifiuta quelli troppo pesanti per il tuo telefono.

---

## 1 · Installare o aggiornare

**Se hai già SAM**: scarica solo **SAM.apk** e aprilo: si installa sopra, voce, nome, cervello,
ricordi e impronta della voce restano al loro posto. Se arrivi dalla 1.5 o da prima, apri ⚙︎ e tocca
**Concedi i permessi**: c'è quello nuovo della **fotocamera** (dalla 1.6 non serve niente di nuovo).
Dopo l'aggiornamento apri ⚙︎ e controlla che il **servizio di accessibilità** abbia la ✓: Xiaomi a
volte lo spegne quando un'app si aggiorna. Dalla 1.10 tra i permessi di SAM compare anche "accesso alla rete": serve
solo per gli aggiornamenti col Wi-Fi (punto 13), Android non chiede niente.

**Se è la prima volta**, dalla conversazione con Claude scarica sul telefono:
- **SAM.apk** (l'app, 32 MB: dentro c'è anche il modello che riconosce la tua voce)
- **SAM-voce-italiana.part1** e **SAM-voce-italiana.part2** (la voce italiana offline, 24 MB l'uno)

Poi:
1. Apri **SAM.apk**.
2. Consenti **"Installa app sconosciute"** per l'app da cui lo apri, poi installa.
3. Se compare Play Protect o l'avviso Xiaomi, tocca **Installa comunque** (l'app non viene dal Play Store).

## 2 · Configurazione (una volta sola)

Apri **SAM** (o tocca ⚙︎):
1. **Permessi**: microfono, contatti, telefono, SMS, notifiche, fotocamera.
2. **Il tuo nome**: scrivilo qui, oppure aspetta che SAM te lo chieda a voce.
3. **Voce italiana**: tocca *Importa la voce italiana* e seleziona **insieme** i due file
   `SAM-voce-italiana.part1` e `.part2` (tieni premuto sul primo, poi tocca il secondo, poi *Seleziona*).
4. **Cervello IA**: vedi il punto 3.
5. **Permessi speciali** (su Xiaomi sono importanti): in alto vedi quali sono attivi (✓) e quali no (✗).
   - **Xiaomi: finestre pop-up in background** → attiva *"Mostra finestre pop-up durante l'esecuzione
     in background"*. Senza, Xiaomi blocca in silenzio le app che SAM apre mentre è in sottofondo:
     chiamate, WhatsApp, internet, fotocamera.
   - **Servizio di accessibilità** → SAM apre le app anche in sottofondo, preme "invia" su WhatsApp,
     **ti legge il meteo**, chiude le app, torna indietro, blocca lo schermo.
   - Mostra sopra altre app, accesso alle notifiche, modifica impostazioni di sistema, togli il
     risparmio batteria.
6. **Ricordi di SAM**: qui vedi tutto quello che SAM ricorda, puoi cancellare i ricordi che vuoi e
   salvarne una copia (vedi il punto 5).
7. **La tua voce**: *Insegna la tua voce a SAM*, *Rispondi solo a me*, *Dimentica la mia voce* (punto 8).
8. **Enciclopedia** (facoltativa): Wikipedia in italiano sul telefono (punto 11).
9. **La voce di SAM**: la voce con cui ti parla, la velocità e il tono (punto 12).
10. **Aggiornamenti (solo col Wi-Fi)**: se SAM si aggiorna da solo, l'ultimo controllo, gli scaricamenti in corso (punto 13).
11. **Avvia SAM**: la prima volta si presenta e ti chiede come ti chiami. Rispondi, per esempio,
   "mi chiamo Sam", poi "sì" quando ti chiede se ha capito bene.

Impostazioni Xiaomi: Impostazioni › App › SAM › Risparmio batteria → **Nessuna restrizione**,
**Avvio automatico** attivo, e nelle app recenti il **lucchetto** su SAM.

**Voce di SAM offline**: Impostazioni › Lingue e immissione (o Accessibilità) › **Sintesi vocale**
› motore Google › Installa dati vocali › **Italiano**. SAM usa solo voci installate sul telefono.

## 3 · Il cervello IA, scelto per il tuo telefono

Nella configurazione SAM mostra le caratteristiche del telefono (RAM, processore, spazio
libero) e il cervello consigliato:

| Telefono | Cervello consigliato | File |
|---|---|---|
| 12 GB di RAM (come il tuo Redmi Note 14 Pro) | **Gemma 4 E4B** | 3,7 GB |
| 6–8 GB di RAM | Gemma 4 E2B | 2,6 GB |
| 4 GB di RAM | Qwen 3.5 0.8B | 1,0 GB |

Gemma 4 E4B è il più intelligente che un telefono regge: rispetto all'E2B capisce meglio, ragiona meglio e segue
meglio le istruzioni. In cambio è più lento (circa il doppio, a occhio: la prima frase arriva dopo qualche secondo in
più) e la prima volta che lo carica ci mette qualche minuto. Se ti sembra troppo lento puoi tornare all'E2B
(⚙︎ › 4 mostra anche quello tra i compatibili). Tutti sono gratuiti (licenza Apache 2.0) e non richiedono account.

**Passare da E2B a E4B** (ricordi, voce e impostazioni restano):
1. Controlla di avere almeno **8 GB liberi** sul telefono (Impostazioni › Informazioni sul telefono › Memoria).
2. Collegati al Wi-Fi, apri SAM › ⚙︎ › **4 · Cervello IA** e tocca **Scarica Gemma 4 E4B (3,7 GB)**: lo scarica SAM
   (l'avanzamento lo vedi nella tendina delle notifiche). Appena è arrivato lo controlla e passa a quello da solo (la
   prima volta ci mette qualche minuto: lo stato in alto conta i secondi). Oppure, a voce: "SAM, usa il cervello più
   intelligente".
3. L'E2B resta sul telefono: puoi tornarci quando vuoi ("SAM, usa l'altro cervello"), oppure toglierlo in ⚙︎ › 4
   per liberare 2,6 GB.

In generale:
1. Tocca **Scarica …** (il nome del cervello consigliato): lo scarica SAM, solo col Wi-Fi, e lo usa appena è pronto.
   Se preferisci, **Scaricalo con il browser** e poi **Importa il file del cervello** scegliendolo dalla cartella
   **Download**: SAM controlla che il telefono lo regga e che il file sia un modello compatibile. Dalla 1.14 vanno
   bene anche i file **GGUF** e **.task** di Hugging Face (vedi le novità della 1.14).
2. Dopo un'importazione puoi cancellare il file dalla cartella Download: SAM ne tiene una copia sua.

Può tenere più cervelli: dopo un'importazione ti chiede se tenere anche quello di prima. In ⚙︎ › 4 vedi quelli che
hai, con **Usa** e **Togli**. A voce: "che cervello usi?", "usa il cervello veloce", "usa il cervello più
intelligente", "usa l'altro cervello". La prima volta che ne carica uno ci mette un po' di più (prepara una cache).

---

## 4 · Come si usa

Di' **"SAM"** (oppure tocca 🎙️ Parla): vibra e ti ascolta. Puoi anche dire tutto insieme:
"SAM, accendi la torcia". Dopo una risposta resta in ascolto per continuare la conversazione.
**"basta"** o **"grazie"** chiude, **"spegniti"** lo ferma del tutto.

- "SAM, accendi / spegni la torcia" · "quanta batteria ho?" · "che ore sono?" · "che giorno è?"
- "SAM, metti una sveglia alle 7 e mezza" · "spegni la sveglia" · "posticipa la sveglia" · "timer di 10 minuti"
- "SAM, riproduci Albachiara" · "fammi sentire Vasco Rossi" · "metti la musica"
- "SAM, pausa" · "riprendi" · "prossima canzone" · "canzone precedente"
- "SAM, volume al massimo / alza / abbassa / a metà / muto" · "luminosità al 50 per cento"
- "SAM, apri WhatsApp / la galleria / MetaTrader / le impostazioni"
- "SAM, chiudi la fotocamera" · "chiudi WhatsApp" · "chiudi tutto" · "chiudi tutte le app"
- "SAM, chiama mamma" (conferma con "sì") · "chiama Mario Rossi" · "chiama tre tre tre uno due…"
- "SAM, scrivi a Giulia su WhatsApp che arrivo tra dieci minuti" (ripete, chiede conferma, invia)
- "SAM, scrivi a Mario su WhatsApp" (ti chiede il testo) · "manda un SMS a Luca: sono in ritardo"
- "SAM, vai su internet" · "cerca su internet chi ha vinto il campionato" · "apri il sito amazon punto it"
- "SAM, portami a Piazza Garibaldi Napoli" · "dove sono?" · "scatta una foto" (app Fotocamera) · "fai uno screenshot"
- "SAM, leggi le notifiche" (o "leggi i messaggi") · "che giorno è domani?" · "quanto fa il 20 per cento di 150?"
- "SAM, che tempo fa?" · "pioverà domani?" · "che tempo fa a Napoli sabato?" · "serve l'ombrello?"
- "SAM, ricordati che domani ho il tagliando" · "cosa ricordi?" · "dimentica il tagliando"
- "SAM, la bici è in cantina" e poi "ricordatelo" · "hai una memoria?"
- "SAM, modalità conversazione" · "parliamo di calcio" · poi parli senza dire SAM · "basta" per finire
- "SAM, quando dico buonanotte spegni la torcia e metti la sveglia alle sette" · "buonanotte"
- "SAM, d'ora in poi rispondimi in modo breve" · "quali regole ti ho dato?" · "no, intendevo accendi la torcia"
- "SAM, ti ricordi cosa abbiamo detto prima?" · "di cosa abbiamo parlato?" · "ripeti" · "parla più forte"
- "SAM, pensaci bene: …" · "pensaci meglio" · "sbagliato, la risposta giusta è …" · "esatto" · "cosa hai imparato?"
- "SAM, impara la mia voce" · "ascolta solo me" · "ascolta tutti" · "chi sta parlando?"
- "SAM, cosa vedi?" · "guarda" · "apri la fotocamera" · "leggimi questo" · "che pianta è questa?" · "mi vedi?" · "come sono vestito?"
- File: Condividi → **Leggi con SAM**, poi "leggilo" · "riassumilo" · "continua" · una domanda sul documento
- "SAM, come mi chiamo?" · "il mio nome è …"
- "SAM, torna indietro / vai alla home / blocca il telefono / spegni il telefono" (apre il menu)
- Qualsiasi altra domanda → risponde il cervello IA, sul telefono.

**Conversare**: SAM ricorda il discorso, anche le battute dei comandi ("cercalo su internet" cerca quello
che avevi chiesto prima). Le ultime battute delle ultime 3 ore le ridà al cervello quando serve, anche se nel
frattempo il telefono aveva chiuso SAM: la conversazione è salvata sul telefono. "SAM, **di cosa abbiamo
parlato?**", "**ti ricordi cosa ti ho detto prima?**", "**ripeti**". "SAM, nuova conversazione" fa ripartire
il discorso da zero. Se una risposta è lunga e non ti interessa, tocca **Parla**.

**Modalità conversazione**: "SAM, **modalità conversazione**" (oppure "SAM, parliamo", "facciamo due
chiacchiere", "parliamo di cucina"). Da quel momento parli e basta, senza dire "SAM" ogni volta: SAM ti
risponde e resta in ascolto. Tutti i comandi continuano a funzionare. Per finire: "**basta**", "fine
conversazione" o "ciao ciao". Se non dici niente per 45 secondi chiede "Ci sei ancora?", poi chiude.

Nota: SAM si collega solo per le ricerche che chiedi tu (e per gli aggiornamenti col Wi-Fi). Alcuni comandi **aprono altre app** che invece usano internet per
natura (WhatsApp, Maps, "cerca su YouTube/Google"): è l'app aperta a collegarsi, non SAM.

## 5 · Memoria: ricorda per sempre, dimentica solo quando lo chiedi

**Fargli ricordare una cosa**: "SAM, **ricordati che** domani ho il dentista", "**ricordami di**
comprare il latte", "**memorizza che** il codice del cancello è 1234", "**segnati che** ho parcheggiato
al terzo piano". Vanno bene anche "**puoi ricordarti che**…", "**riesci a ricordare che**…",
"**ti ricordo che**…", "**sappi che**…", "**segna che**…", "**salva che**…", "**tieni a mente che**…",
"**voglio che memorizzi**…". Risponde "Ok, me lo ricordo". Se la cosa la sapeva già te lo dice; se
aggiorna una cosa che cambia ("ricordati che il mio colore preferito è il verde") ti dice cosa sapeva prima.

**Oppure dilla e poi "ricordatelo"**: "SAM, la bici è in cantina" e subito dopo (entro qualche minuto)
"**ricordatelo**", "**memorizzalo**", "**tienilo a mente**" o "**te lo ricorderai?**". Anche tutto
insieme: "il mio cane si chiama Rex, ricordatelo". Se non c'è niente di appena detto, ti chiede
"Cosa devo ricordare?" e salva la frase che dici dopo.

**"Hai una memoria?"**, "ti ricordi le cose che ti dico?", "come faccio a farti ricordare una cosa?":
risponde SAM, spiega come funziona e ti dice quante cose ricorda.

**Chiedergliela**: "SAM, **cosa ricordi?**" (le elenca) · "**ti ricordi** del dentista?" ·
"**dove ho parcheggiato?**" · "**quando ho il dentista?**" · "**cosa ti ho detto di** Marco?" ·
"**cosa sai di me?**". Anche quando chiacchierate il cervello IA le conosce e le usa.

**Fargliela dimenticare** (solo quella): "SAM, **dimentica** il dentista", "**scordati del** latte",
"**cancella dalla memoria** il codice del cancello". Ti dice quale ricordo ha trovato e chiede
**"Confermi?"**: con "sì" lo cancella, con "no" lo tiene. Se ne trova due simili ti chiede quale
("il primo", "il secondo" o "tutti e due"). "SAM, **dimentica l'ultima cosa**" cancella l'ultimo
ricordo. "SAM, **dimentica tutto**" li cancella tutti, ma solo dopo il tuo "sì". Nient'altro lo
cancella: né gli aggiornamenti di SAM né il tempo. Attenzione: "nuova conversazione" fa dimenticare
solo il discorso in corso, non i ricordi.

**Impara da solo** quello che racconti di te mentre parlate: gusti ("mi piace la pizza", "odio il
traffico"), famiglia ("mia moglie si chiama Anna", "ho due figli"), casa e lavoro ("abito a Casoria",
"faccio il trader"), squadra ("tifo Napoli"), età. Sullo schermo compare *Memoria: ho imparato…*.
Se un fatto cambia ("non mi piace più la pizza") si aggiorna da solo; quello che gli hai chiesto tu
con "ricordati" invece lo cambia solo se glielo chiedi tu. "SAM, portami a casa" e "che tempo fa a
casa" usano dove abiti.

**Migliora con l'uso** (impara da te, senza aggiornamenti):
- **Scorciatoie**: "SAM, quando dico **buonanotte** spegni la torcia e metti la sveglia alle
  sette" (anche "intendo…", "devi…"). Da quel momento "SAM, buonanotte" fa tutte e due le cose. Puoi
  metterne di fila quante vuoi ("…e poi…"). Un'istruzione per guardare ("quando ti dico guarda, apri la
  fotocamera, guarda e chiudila") usa la vista di SAM, che si richiude da sola.
- **Regole da seguire sempre**: "SAM, **d'ora in poi** rispondimi in modo breve", "da adesso non chiamarmi
  per nome", "dammi del lei", "devi sempre…", "non devi mai…", "sii più sintetico", "quando ti saluto
  rispondimi ciao capo". Te la ripete ("Va bene, da ora seguo questa regola: …") e il cervello la segue in
  ogni risposta; se due regole si contraddicono vale l'ultima. "**Quali regole ti ho dato?**" ·
  "**dimentica la regola** di rispondere breve" · "dimentica tutte le regole" (chiede conferma).
- **Correggilo**: se SAM non ha capito una frase, di' subito "**no, intendevo** …" con il comando giusto.
  Lo fa e ti chiede se d'ora in poi quella frase vuol dire questo: con "sì" la impara.
- **Lezioni**: se ti risponde male, "SAM, **sbagliato**, la risposta giusta è …" (o "non è vero, …", "hai
  sbagliato, …"). La lezione resta per sempre: a una domanda simile il cervello la riceve prima di rispondere.
  "Esatto" o "bravo" dopo una buona risposta la tengono come esempio. Ne tiene fino a 200: oltre, le più vecchie
  lasciano il posto. "**Cosa hai imparato?**", "dimentica la lezione …", "dimentica tutte le lezioni".
- **Ragionare**: "SAM, **pensaci bene**: …" oppure "ragionaci". Prima ragiona passo per passo (lo vedi sullo
  schermo), poi risponde. "Pensaci meglio" rifà l'ultima domanda ragionando.
- **Nomi**: "SAM, quando dico **amore** intendo Giulia" → "chiama amore", "scrivi ad amore che arrivo".
  Vale anche per i posti ("quando dico ufficio intendo via Toledo 10 Napoli" → "portami in ufficio")
  e per le app.
- **Contatti con lo stesso nome**: la prima volta ti chiede quale Mario; dalla volta dopo propone
  quello che scegli di solito. Se dici "no" (o "no, l'altro", "no, Rossi") lo corregge e se lo ricorda.
- Per togliere una scorciatoia: "SAM, dimentica la scorciatoia buonanotte".

**Nella configurazione** (⚙︎ › Ricordi di SAM): *Vedi e cancella i ricordi* li mostra tutti, spunti
quelli da togliere e confermi. *Salva una copia dei ricordi* crea un file sul telefono: se un giorno
reinstalli SAM o cambi telefono, *Ripristina i ricordi da una copia* li rimette tutti. I ricordi
stanno solo sul telefono, nella memoria privata di SAM.

## 6 · Il meteo, letto ad alta voce

"SAM, che tempo fa?" · "pioverà domani?" · "quanti gradi ci sono?" · "serve l'ombrello?" ·
"che tempo farà nel weekend?" · "che tempo fa a Roma sabato?" · "meteo Napoli".

Come fa senza internet: per dove sei apre l'**app Meteo** di Xiaomi (che usa la tua posizione), per
un'altra città apre la pagina delle previsioni nel **browser**. Poi **legge lo schermo** con il servizio
di accessibilità e ti risponde a voce; con il cervello IA risponde proprio alla tua domanda ("sì,
domani pioverà…"). È l'app del meteo o il browser a collegarsi, non SAM.
- Serve il **servizio di accessibilità** attivo; senza, SAM apre il meteo e lo guardi tu.
- Se il telefono è bloccato, SAM ti dice "Sblocca il telefono e te lo leggo": sbloccalo e ascolta.
- La prima volta l'app Meteo potrebbe chiederti la posizione: concedila all'app Meteo (non a SAM).

## 7 · Far parlare SAM con un'altra intelligenza artificiale

SAM resta senza internet: l'altra IA sta su un **altro dispositivo** e i due si parlano ad alta voce.
1. Sul PC o su un altro telefono apri ChatGPT, Gemini o un'altra IA in **modalità voce**
   (in ChatGPT è il tasto con le onde sonore, in Gemini è "Live").
2. Metti il Redmi vicino all'altro dispositivo, con il volume alto su tutti e due.
3. Di' **"SAM, parla con ChatGPT"**, oppure **"SAM, parla con Gemini di calcio"**, **"SAM, fai una
   conversazione con l'altra intelligenza artificiale sul futuro"**. ("SAM, modalità conversazione" invece
   è la conversazione con SAM: vedi il punto 4.)
4. SAM si presenta, poi ascolta l'altra IA senza bisogno di sentire "SAM" e risponde a voce, con una
   domanda ogni tanto per portare avanti il discorso. Sullo schermo vedi chi ha detto cosa.
5. Per finire: **"SAM basta"**, oppure tocca **Parla**. Si chiude anche da sola se l'altra IA tace
   per un minuto, o dopo 40 battute. Se dici "SAM" seguito da un comando ("SAM, apri la fotocamera",
   "SAM, cosa vedi?") la conversazione con l'altra IA finisce e SAM fa quello che hai chiesto.

Consigli: se l'altra IA interrompe SAM mentre parla, allontana un po' i due dispositivi o abbassa
il volume dell'altro. La conversazione viene meglio con il cervello Gemma 4 E2B.

## 8 · La tua voce

**Insegnargliela** (una volta, un paio di minuti): "SAM, **impara la mia voce**", oppure ⚙︎ › La tua voce ›
*Insegna la tua voce a SAM*. SAM ti dice sei frasi e tu le ripeti una alla volta, con la voce di sempre,
tenendo il telefono come quando gli parli di solito e senza altre persone che parlano. Se una frase non
l'ha sentita bene te la fa ripetere; se nel frattempo ha parlato qualcun altro, quella frase la scarta da solo.

**Rispondere solo a te**: "SAM, **ascolta solo me**" (oppure il tasto in ⚙︎). Da lì:
- i tuoi comandi li esegue come sempre;
- se parla un'altra persona, o la TV dice "Sam", non esegue niente e lo dice ("rispondo solo alla voce di Sam",
  poi più breve: "non riconosco la tua voce"); mentre chiacchierate ignora la TV in silenzio;
- se non è sicuro (frase cortissima, molto rumore) ti chiede di ripetere con qualche parola in più; se non è
  sicuro neanche la seconda volta, ti dice di toccare Parla.
  Dopo che ti ha riconosciuto, per un minuto e mezzo vanno bene anche le risposte brevi ("sì", "no").
- **Se un giorno non ti riconosce** (raffreddore, rumore, telefono più lontano): tocca **🎙️ Parla**. Per due
  minuti ti ascolta senza controllare la voce, e se poco prima ti aveva rifiutato ti chiede "Vuoi che la impari
  di nuovo?": di' "sì". Sullo schermo trovi quanto somigliava la voce ("somiglia al 52%, serve il 58%").
- Per tornare come prima: "SAM, **ascolta tutti**" (se non ti riconosce, prima tocca Parla), oppure il tasto
  in ⚙︎. Per provarlo: "SAM, **mi riconosci?**", "**sai chi sono?**" o "**chi sta parlando?**" (ti dice il tuo nome
  e quanto la voce somiglia alla tua, e risponde anche quando non ti riconosce). Le frasi brevissime ("chi sono?")
  a volte sono troppo corte per la voce: "mi riconosci dalla voce?" va meglio. Per cancellarla: "SAM, **dimentica la mia voce**".

**Migliora con l'uso**: ogni frase che riconosce con sicurezza come tua sposta un poco l'impronta verso
come parli adesso (raffreddore, stanza, microfono).

Com'è andata nelle prove sul PC, con voci sintetiche diverse: la voce giusta riconosciuta 9 volte su 10
(la decima era troppo corta e avrebbe chiesto di ripetere), le altre voci scambiate per la tua **0 volte
su 20**. **Non è una serratura**: una voce molto simile alla tua o una tua registrazione possono ingannarlo.
Usalo per "ascolta solo me", non per proteggere cose importanti.

## 9 · Leggere i file

Nella schermata di SAM tocca la graffetta **📎** e scegli il file, oppure da qualsiasi app (File, WhatsApp, Gmail,
Chrome, Galleria) tocca **Condividi** e scegli **Leggi con SAM** (oppure "Apri con" → SAM). SAM dice il nome e quante pagine, poi chiede cosa vuoi:
- "**leggilo**": te lo legge a pezzi e ogni tanto chiede "Continuo?" ("sì", "continua", "no");
- "**riassumilo**": riassunto in quattro o cinque frasi. Un documento lungo lo riassume a pezzi (un
  minuto o due) e te lo dice appena ha finito;
- **una domanda**: "quanto devo pagare?", "c'è una scadenza?", "nel documento cosa dice sulle spese?".
  Risponde solo con quello che c'è scritto: se non c'è, te lo dice.
- "**chiudi il documento**" quando hai finito (si chiude da solo dopo 20 minuti che non ne parli).

Formati: PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), OpenDocument, testo, pagine web, foto.
I PDF fatti di foto (scansioni) li legge **guardandoli**, pagina per pagina, con la vista del cervello.
I vecchi .doc e .xls no: salvali come .docx/.xlsx o PDF. Riassunti e domande richiedono il cervello IA;
leggere ad alta voce no. Il file resta nella memoria di SAM (gli ultimi cinque) e non esce dal telefono.

## 10 · Guardare con la fotocamera

"SAM, **cosa vedi?**" · "**dimmi cosa vedi**" · "**guarda**" · "**guarda con la fotocamera**" ·
"**apri la fotocamera e dimmi cosa vedi**" · "**riesci a vedere?**" · "**leggimi questo**" · "**cosa c'è scritto?**" ·
"**cos'è questo?**" · "**che pesce è questo?**" · "**di che colore è questa?**" · "**riconosci questo oggetto**".

SAM apre per un attimo la fotocamera posteriore, scatta da solo dopo un secondo e mezzo (tieni fermo il
telefono e inquadra), poi il cervello guarda la foto e ti risponde. Funziona anche a telefono bloccato.
La foto resta sul telefono, nella memoria di SAM, e viene sovrascritta alla volta dopo.

**Guardare te**: "SAM, **mi vedi?**", "**guardami**", "**come sono vestito?**", "**di che colore è la mia
maglietta?**" usano la fotocamera **davanti**. Non dice mai chi sei: descrive solo quello che vede.

Anche "**apri la fotocamera**" (dalla 1.6.5) fa guardare SAM: la sua fotocamera si apre, scatta e si
richiude da sola, così SAM continua a sentirti.

**L'app Fotocamera** del telefono si apre con "scatta una foto", "fai un selfie", "apri l'app della
fotocamera": lì le foto le fai tu e SAM non le guarda. Finché resta aperta SAM potrebbe non sentirti
(l'app Fotocamera può occupare il microfono): quando hai finito, chiudila. Se SAM non ha un cervello che
vede, anche "apri la fotocamera" apre l'app Fotocamera.

- Serve un cervello che sa vedere le immagini: SAM lo controlla da solo dal file. Gemma 4 E2B è fatto anche
  per guardare le foto. Quando il cervello è pronto, sullo stato in alto compare "vede le immagini"
  (con "scheda grafica" se la vista gira lì). Se il tuo file non avesse la vista, SAM te lo dice con il nome del file.
- Ogni foto richiede qualche secondo. La prima volta un po' di più: il cervello carica la parte della vista.
- Se la vista dà errore, SAM dice "Un attimo, provo a guardare in un altro modo" e riprova da solo
  (dal processore alla scheda grafica, o viceversa). Se il telefono si blocca mentre guarda, alla
  riaccensione SAM usa l'altro modo.
- Non riconosce chi è una persona. Per funghi, bacche, piante o medicine non fidarti mai solo di SAM:
  dalla foto non si può essere sicuri che siano commestibili o innocui.

## 11 · L'enciclopedia sul telefono (facoltativa)

Wikipedia in italiano in un file solo, preparato da **Kiwix** (un progetto senza scopo di lucro che fa le copie di
Wikipedia da leggere senza internet). SAM legge solo il testo e solo dove sta il file: non ne fa copie.

1. Controlla di avere spazio: la versione senza immagini pesa **circa 10 GB** (dato del 2024; oggi può essere un po' di più).
2. Collegati al Wi-Fi e in SAM › ⚙︎ › **8 · Enciclopedia** tocca **Scarica Wikipedia in italiano (Wi-Fi)** (senza
   immagini) oppure **Scarica la versione leggera** (solo l'inizio di ogni voce: è tutto quello che SAM legge, e pesa
   molto meno). La scarica SAM: l'avanzamento lo vedi nella tendina delle notifiche. Quando è arrivata la controlla
   tutta e la usa da sola: compare "Enciclopedia pronta" con la data e il numero di voci.
3. Se ce l'hai già (scaricata con il browser, si chiama tipo `wikipedia_it_all_nopic_2026-09.zim`), tocca **Scegli il
   file dell'enciclopedia (.zim)** e sceglila: SAM la legge dov'è. In quel caso non cancellare il file.
4. Per toglierla: **Togli l'enciclopedia** (se l'aveva scaricata SAM, cancella anche il file).

**Aggiornarla**: lo fa SAM da solo (punto 13). Una volta alla settimana, col Wi-Fi, guarda sul sito di Kiwix se c'è una
Wikipedia più nuova della stessa versione della tua; se c'è la scarica, la controlla con la sua impronta e la mette al
posto della vecchia. Se la vecchia l'aveva scaricata lui la cancella; se l'avevi scelta tu dalla cartella Download te
lo dice, e la cancelli tu. Puoi anche chiedere: "SAM, c'è una Wikipedia più nuova?".

## 12 · La voce di SAM e il suo carattere

**La voce.** SAM parla con le voci italiane installate sul telefono che funzionano senza internet (le voci "di rete",
più naturali ma online, non le usa). In ⚙︎ › **9 · La voce di SAM**: **Scegli la voce** (toccandone una la senti),
**Più lenta / Più veloce**, **Più grave / Più acuta**, **Prova**, **Normale**; se sul telefono c'è più di un motore di
sintesi (Google e Xiaomi, per esempio) puoi scegliere anche quello. A voce: "cambia voce", "torna alla voce di prima",
"parla più piano", "parla più veloce", "voce più grave", "voce più acuta", "voce normale", "che voce usi?".
Se c'è una sola voce: Impostazioni › Sintesi vocale › motore di Google › Installa dati vocali › Italiano (di solito
ne arrivano diverse).

**Il carattere.** Di base è confidenziale: ti parla come un amico. Lo cambi con le regole ("d'ora in poi dammi del
lei", "sii più spiritoso", "parlami in napoletano"), oppure lamentandoti: SAM propone da solo la regola per correggersi
e la tiene solo se dici sì. "Sii più preciso" e "usa più fantasia" cambiano invece quanto il cervello inventa.

## 13 · Aggiornamenti (solo col Wi-Fi)

È l'unica cosa per cui SAM usa internet, e solo su una rete che non si paga a consumo (il Wi-Fi di casa): con i dati
del telefono non scarica mai niente.
- **Cosa va su internet**: SAM chiede a download.kiwix.org l'elenco dei file di Wikipedia e a huggingface.co i dati del
  file del cervello (l'impronta e la dimensione); poi il gestore degli scaricamenti di Android scarica i file. Le tue
  frasi, i ricordi e le risposte non escono mai dal telefono.
- **Quando**: una volta alla settimana, da solo, col Wi-Fi e la batteria non scarica (anche se SAM è spento). Oppure
  quando lo chiedi: "SAM, ci sono aggiornamenti?" o ⚙︎ › 10 › **Controlla adesso**.
- **La Wikipedia**: la mette al posto della vecchia da sola, dopo averla controllata tutta.
- **Il cervello**: lo scarica e lo controlla, poi la prima volta che lo chiami ti chiede "la installo?". Se dici di no
  te lo richiede più avanti, oppure di' tu "installa il cervello nuovo". Se il nuovo non parte, rimette il vecchio.
- **Spazio**: serve spazio per il file nuovo (una Wikipedia senza immagini è circa 10 GB, il cervello E4B 3,7 GB più la
  sua cache). Se manca, SAM non scarica e te lo dice.
- **Spegnerli**: "SAM, non aggiornarti più da solo" oppure ⚙︎ › 10. Per riaccenderli: "SAM, aggiornati da solo".
- L'app SAM invece non si aggiorna da internet: il SAM.apk nuovo te lo prepara Claude, come sempre.

## Limiti veri (li impone Android)
- Wi-Fi, Bluetooth, dati, aereo: nessuna app può cambiarli da sola. SAM apre il pannello, tocchi tu.
- Chiudere un'app: Android non permette di chiudere le app degli altri. SAM torna alla schermata
  home (l'app si ferma in sottofondo); "chiudi tutte le app" apre le recenti, poi tocchi Chiudi tutto.
- Le chiamate le chiudi tu con il tasto rosso. Le chiamate WhatsApp non si possono avviare a voce:
  SAM chiama con il telefono.
- Su WhatsApp SAM preme invia solo con il servizio di accessibilità attivo; senza, apre la chat con il
  messaggio pronto e lo invii tu.
- Ricerca sul web: SAM legge solo il testo delle pagine (non foto o video), e solo pochi pezzi per pagina perché il
  cervello del telefono ha poca memoria. Alcuni siti bloccano i programmi: SAM li salta e usa gli altri.
- Il riconoscimento offline è più semplice di quello di Google: parla chiaro dicendo "SAM".
  Se non ti sente al primo colpo, ripeti o tocca 🎙️ Parla.

## Se qualcosa non va
- **"Ho avuto un intoppo con il mio cervello"** o **"Il mio cervello si è inceppato: lo sto riavviando"**: aspetta
  qualche secondo e richiedi. Se torna spesso, mandami la riga "Dettaglio: …" che compare sullo schermo.
- "Modello incompleto": nella voce italiana vanno selezionate **entrambe** le parti insieme.
- Non sente "SAM": permesso microfono di SAM e risparmio batteria tolto.
- **Non ti sente più**: apri SAM o tocca 🎙️ Parla (il microfono si rimette a posto da solo), poi guarda lo
  stato in alto. "Un'altra app sta usando il microfono" = chiudi quell'app (Fotocamera, registratore, chiamata
  WhatsApp). "Voce non riconosciuta" = vedi "Non ti riconosce la voce", qui sotto. "Il microfono non risponde: riprovo da solo…" = SAM
  lo sta riaprendo. Se resta così, riavvia il telefono e mandami uno screenshot dello schermo di SAM.
- Non chiama, non apre WhatsApp o internet, oppure dice "Android non mi ha lasciato aprire l'app":
  in ⚙︎ attiva le voci con la ✗, soprattutto **finestre pop-up in background** di Xiaomi e il
  **servizio di accessibilità**.
- "Non trovo … in rubrica": controlla come è scritto il contatto; puoi dire anche nome e cognome.
- Non chiama da solo ma apre il numero: manca il permesso **Telefono** (punto 1).
- Non parla: installa la voce italiana offline (sopra).
- La voce non ti piace, è troppo veloce o troppo lenta: "SAM, cambia voce", "parla più piano", "parla più veloce",
  oppure ⚙︎ › 9. "Voce normale" rimette tutto com'era.
- Ha preso una regola che non volevi (anche una proposta da lui): "SAM, quali regole ti ho dato?", poi "dimentica la
  regola …", oppure cancellala in ⚙︎ › Ricordi di SAM.
- Non si aggiorna: gli aggiornamenti partono solo col **Wi-Fi** e con abbastanza spazio libero. Chiedi "SAM, ci sono
  aggiornamenti?" (ti dice cosa succede) o guarda ⚙︎ › 10. Uno scaricamento fermo "in attesa" di solito aspetta il
  Wi-Fi: riparte da solo da dove era rimasto. Se Xiaomi limita SAM in sottofondo, togli il risparmio batteria (punto 2).
- "Ho scaricato …, ma non va bene": il file è arrivato rovinato (lo riscarica la volta dopo) oppure serve un SAM.apk più
  nuovo per usarlo: chiedimelo.
- Non chiacchiera: importa il cervello (punto 3).
- Ha capito male il tuo nome: correggilo nella configurazione, al punto 2.
- Il meteo non viene letto: attiva il **servizio di accessibilità** (dopo un aggiornamento Xiaomi a
  volte lo spegne) e, la prima volta, apri a mano l'app Meteo e concedile la posizione.
- Dice "non posso memorizzare" o "non ho memoria": è il cervello IA che parla per abitudine. Con la
  1.6.1 SAM toglie quella frase e le richieste di ricordare non arrivano più al cervello. Se ti
  succede ancora, di' subito "ricordatelo", e dimmi la frase esatta che avevi usato: la aggiungo.
- Resta a lungo su "**Carico il cervello IA…**": la prima volta (dopo l'aggiornamento alla 1.6.4 o dopo aver
  cambiato cervello) ci vogliono 1–3 minuti, poi pochi secondi. I comandi funzionano già mentre carica.
  Se ogni volta ci mette tanto, mandami la riga "Info: Cervello pronto in … secondi (…)".
- Sullo schermo le tue frasi compaiono come "**Altra IA**": sei nella conversazione con un'altra intelligenza
  artificiale, dove SAM non esegue comandi. Di' "SAM basta". Per chiacchierare con SAM di' "SAM, modalità conversazione".
- Ha ricordato una cosa capita male: "SAM, dimentica …" con una parola giusta del ricordo, oppure
  cancellala in ⚙︎ › Ricordi di SAM.
- Una scorciatoia non parte: dilla da sola, uguale a come l'hai insegnata ("buonanotte"), oppure
  "SAM, fai buonanotte". "SAM, cosa ricordi?" ti dice quali scorciatoie conosce.
- **Non ti riconosce la voce** (dice "non riconosco la tua voce" o "rispondo solo alla voce di …", oppure sullo
  schermo "Voce non riconosciuta"): tocca 🎙️ Parla e rispondi "sì" quando ti chiede di imparare di nuovo la voce
  (oppure, dopo Parla, di' "impara la mia voce"). Dalla 1.10.1 anche solo toccare Parla e dire due o tre comandi
  basta ad adattare la soglia alla tua voce. Se preferisci che risponda a tutti: ⚙︎ › La tua voce ›
  **Rispondi a tutti**. Se continua, mandami la riga "Info: Non ho riconosciuto la voce … (somiglia al …%, serve il …%)".
- "Il cervello che hai importato … non ha la parte della vista": il file del cervello non sa guardare le foto.
  Dimmi il nome del file che SAM ti dice e ti indico quello giusto.
- "La parte della vista del cervello non è partita": riavvia SAM e chiudi le altre app aperte (la vista
  ha bisogno di memoria). Sullo schermo, sotto, SAM scrive il motivo: mandamelo se non si risolve.
- "Android non mi ha lasciato aprire la fotocamera": in ⚙︎ attiva il **servizio di accessibilità** e le
  **finestre pop-up in background** di Xiaomi.
- Mentre l'app Fotocamera è aperta SAM non ti sente: è l'app Fotocamera che occupa il microfono (sullo
  schermo di SAM compare "Un'altra app usa il microfono"). Chiudila. Per farlo guardare usa "guarda",
  "cosa vedi?" o "apri la fotocamera": la sua fotocamera si chiude da sola.
- Non ricorda la conversazione: chiedi "SAM, di cosa abbiamo parlato?" o "ti ricordi cosa ti ho detto
  prima?". Se risponde male, mandami uno screenshot con la tua domanda e la sua risposta.
- Il cervello si incarta e ripete la stessa frase: dalla 1.7.2 SAM se ne accorge da solo e riparte. Se vuoi
  ripartire tu: "SAM, **nuova conversazione**".
- Con Gemma 4 E4B risponde lento: è il prezzo di un cervello più grande. Se lo preferisci più svelto, reimporta
  Gemma 4 E2B (⚙︎ › 4). Se all'importazione dice "spazio insufficiente", libera spazio o cancella prima il file
  scaricato dell'E2B dalla cartella Download.
- Una data, un'ora nel mondo o una conversione detta male: mandami la frase esatta che hai usato, la aggiungo agli
  strumenti esatti.
- "Non riesco ad aprire l'enciclopedia": il file è stato spostato o cancellato, oppure Android ha tolto il permesso.
  Sceglilo di nuovo in ⚙︎ › 8. "Questo file non va bene come enciclopedia": serve un file **.zim** di Kiwix; quelli
  vecchissimi (prima del 2021) usano una compressione che SAM non legge: riscaricalo.
- "Nell'enciclopedia non trovo…": prova con il nome intero ("Giuseppe Garibaldi", "Torre di Pisa"). Se una voce che
  c'è non la trova, mandami la frase esatta.
- Ha usato una voce sbagliata (la riga "Enciclopedia: «…»" sullo schermo non c'entra con la domanda): mandami lo screenshot.
- Non ha capito un comando: di' subito "no, intendevo …" con il comando giusto, poi "sì" per insegnarglielo.
  Se ha imparato una cosa sbagliata: "SAM, dimentica la scorciatoia …" oppure ⚙︎ › Ricordi di SAM.
- Ha sentito male una regola: "SAM, quali regole ti ho dato?", poi "dimentica la regola …" e ridigliela.
- "Pensaci bene" ci mette tanto: è normale, scrive prima tutto il ragionamento (sul telefono, senza internet).
  Se ti serve una risposta veloce, chiedi senza "pensaci bene". Per fermarlo tocca **Parla**.
- Ha imparato una lezione sbagliata: "SAM, quali lezioni hai imparato?", poi "dimentica la lezione …", oppure
  cancellala in ⚙︎ › Ricordi di SAM.
- Compare la notifica "**SAM si è fermato**": Android l'ha chiuso per liberare memoria. Toccala e riparte.
  Perché succeda meno: risparmio batteria di SAM tolto (punto 2).
- Compare la notifica "**SAM non riesce a sentirti**": un'altra app tiene il microfono, oppure Android non lo
  lascia a SAM in sottofondo. Toccala (o apri SAM): il microfono riparte.
- Nel menu Condividi non c'è "Leggi con SAM": il tipo di file non è tra quelli che sa leggere, oppure
  l'app da cui condividi manda solo un link (in quel caso salva il file e condividilo dall'app File).

---

## Per chi vuole modificarla (facoltativo)
Progetto sorgente in **SAM_progetto.zip**. Si compila in locale con `build_apk.sh` (istruzioni nel
file), senza servizi online. Chiave di firma `app/sam-release.jks` (password `samsam2026`):
tienila, serve per installare gli aggiornamenti sopra la versione attuale.
