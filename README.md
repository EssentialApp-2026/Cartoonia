# Locale AI

App Android nativa per chattare con modelli GGUF eseguiti **solo sul telefono** tramite
llama.cpp. Nessun server, nessun cloud, nessun account. Il manifest non dichiara il
permesso Internet.

## Ottenere l'APK senza installare niente (GitHub)

Il progetto include `.github/workflows/build-apk.yml`: GitHub compila l'APK gratis.

1. Su github.com crea un repository nuovo, per esempio `LocaleAI` (va bene anche privato).
2. Scegli **uploading an existing file** e trascina **tutto il contenuto** della cartella
   `LocaleAI` (compresa la cartella `.github`), poi **Commit changes**.
3. Apri la scheda **Actions**: la build "Compila APK" parte da sola (circa 15–25 minuti).
4. A build finita trovi **LocaleAI.apk** nella sezione **Releases** del repository:
   aprila dal telefono, scarica l'APK e installalo (consenti "origini sconosciute").

Ogni volta che modifichi un file la build riparte e crea una nuova release. L'APK è
firmato sempre con la stessa chiave (`app/localeai.keystore`), quindi gli aggiornamenti
si installano sopra la versione precedente senza perdere il modello.
Se il repository è pubblico, la chiave è visibile a tutti: va bene per uso personale.

## Cosa serve sul PC (build in locale)

- Android Studio (stabile) con JDK 17 (quello incluso va bene).
- Da *SDK Manager → SDK Tools*: **NDK (Side by side)** e **CMake 3.22.1**.
- Git (https://git-scm.com).
- Telefono Android 8.0 (API 26) o successivo, meglio ARM64 con almeno 6 GB di RAM.

## 1. Scarica llama.cpp (una volta sola)

Dalla cartella `LocaleAI`:

- Windows: doppio clic su **`setup_llama.bat`**
- Linux/macOS: `./setup_llama.sh`

Lo script scarica llama.cpp al tag **`b12282`** in `third_party/llama.cpp`.
Il codice nativo è stato compilato e testato con questa versione: non cambiare
tag senza ricompilare e verificare (l'API C di llama.cpp cambia spesso).

## 2. Compila e installa

1. Apri la cartella `LocaleAI` in Android Studio e attendi la sincronizzazione Gradle.
2. Collega il telefono con il debug USB attivo.
3. Premi **Run**.

Oppure da terminale: `gradlew installDebug` (Windows) o `./gradlew installDebug`.

APK generato: `app/build/outputs/apk/debug/app-debug.apk`.
La prima build compila llama.cpp per due architetture e richiede vari minuti.

## 3. Importa un modello

I pesi non sono inclusi. Scarica un modello **GGUF instruct** piccolo e quantizzato
(Q4_K_M è un buon compromesso), da 0,5 a 3 miliardi di parametri: per esempio
Qwen2.5 1.5B Instruct, Llama 3.2 1B/3B Instruct o Gemma 2 2B it. Controlla la
licenza del modello.

Nell'app tocca **Scegli modello GGUF**. L'app verifica che sia davvero un GGUF, controlla
lo spazio libero, ne fa una copia privata e lo carica. Il modello resta salvato:
alla riapertura viene ricaricato automaticamente. Importandone uno nuovo, il precedente
viene eliminato (niente copie accumulate).

## Caratteristiche

- **Chat template del modello**: il prompt viene formattato con il template contenuto
  nel GGUF (ChatML, Llama 3, Gemma, Mistral…). Se il modello non ne ha uno riconosciuto,
  si usa un formato di riserva con stop automatico su `Utente:`.
- **Cronologia adattiva**: se la conversazione supera il contesto (2048 token), i
  messaggi più vecchi vengono scartati automaticamente.
- **Interrompi** ferma la generazione in qualsiasi momento.
- **Nuova chat** azzera la conversazione.
- llama.cpp è compilato ottimizzato (Release) anche nell'APK debug.
- Compatibile con i telefoni Android 15+ a pagine da 16 KB e con la modalità edge-to-edge.

## Memoria e prestazioni

Regola pratica: il file GGUF + circa 300–500 MB devono stare nella RAM libera.
Un modello troppo grande provoca errore di caricamento o chiusura dell'app.
Il backend usa la CPU (fino a 4 thread), senza GPU.

## Note tecniche

- Caricamento, generazione e rilascio passano da un unico executor di processo
  (`LlmEngine`): l'ordine è sempre garantito, anche se l'Activity viene ricreata.
- Lo stop usa una flag atomica nativa e non attende il mutex della generazione.
- I token arrivano a Kotlin come `byte[]` e vengono decodificati in streaming UTF-8
  (emoji e lettere accentate spezzate tra token restano corrette).
- Il codice nativo è stato verificato compilandolo e collegandolo a llama.cpp `b12282`
  ed eseguendo un test JNI completo (caricamento, chat con e senza template, taglio
  della cronologia, stop, errori). La build Android completa va eseguita sul tuo PC.
