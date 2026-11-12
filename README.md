# SAM

Assistente personale Android: LocaleAI (modelli GGUF offline con llama.cpp) unito
all'assistente vocale SAM, più un'IA online con **più account che si alternano da soli**
quando uno finisce i token.

## Come funziona il motore

1. SAM prova gli account online **nell'ordine che scegli tu** (⚙ Modelli).
2. Se un account risponde con token o quota esauriti, troppe richieste, chiave non valida,
   credito finito o servizio in panne, SAM **lo mette in pausa** per il tempo giusto
   (pochi secondi per i limiti al minuto, almeno un'ora per quelli giornalieri) e passa
   subito al successivo.
3. Se sono tutti in pausa, o sei offline, risponde con il **modello GGUF locale**.
4. Gli account in pausa tornano attivi da soli allo scadere della pausa (oppure con «Riattiva tutti»).

Servizi supportati: Groq, Google Gemini, OpenRouter, Mistral, Cerebras, OpenAI,
Anthropic Claude, DeepSeek e qualsiasi servizio compatibile OpenAI («Personalizzato»).
Per ogni account puoi scegliere il modello dall'elenco fornito dal servizio
(«Scegli dall'elenco») e verificarlo con il pulsante «Prova».
Le chiavi sono cifrate con una chiave del Keystore Android e restano sul telefono.

Le modalità sono «Online, poi locale» (predefinita), «Solo online» e «Solo locale».
Puoi cambiarla anche a voce: «modalità offline» / «modalità online».

## Voce

- **🎙 Parla**: detti una richiesta e SAM risponde a voce.
- **Ascolto «SAM»**: ascolto continuo con notifica fissa. Di' «SAM» e risponde «Ti ascolto»,
  oppure di' tutto insieme «SAM, che ore sono».
- Le risposte vengono lette frase per frase mentre vengono generate. «stop» le interrompe.
- Per aprire app e fare chiamate a schermo spento attiva «Sopra altre app» in ⚙.
  Senza quel permesso SAM mostra una notifica da toccare.

Comandi del telefono (eseguiti subito, senza IA): chiamate, SMS e WhatsApp, sveglia,
timer, torcia, volume, apertura app, navigazione, ricerca web/YouTube, promemoria in
calendario, ora, data, batteria, impostazioni Wi-Fi/Bluetooth, nuova chat. L'elenco
completo è in ⚙.

## APK

Il workflow `.github/workflows/build-apk.yml` compila l'APK su GitHub a ogni push e lo
pubblica nelle Releases del repository (`SAM.apk`). L'APK è firmato con la chiave fissa
`app/localeai.keystore`: gli aggiornamenti si installano sopra la versione precedente e
conservano account e modello.

## Build in locale (facoltativa)

1. Esegui `setup_llama.bat` (Windows) o `./setup_llama.sh`: scarica llama.cpp al tag `b12282`.
2. Apri la cartella in Android Studio (con NDK 27.0.12077973 e CMake 3.22.1) e premi Run.
