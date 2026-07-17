# LightningBI — Roadmap: Area Cross-Database

## Stato attuale (baseline)

Oggi (`AreaSource`, `ConfigureSourceDialog`, `EtlOrchestrator`) ogni area supporta
**una sola sorgente dati** — un singolo database esterno, con una singola view,
che alimenta tutte le colonne (filtri + somme) della tabella fatti dell'area.

## Funzionalità proposta: Area Cross-Database

Permettere a un'area di avere **più sorgenti dati contemporaneamente**, ognuna
proveniente da un database diverso (es. ERP su SQL Server + CRM su Oracle),
unite tramite una o più dimensioni condivise (es. "Cliente") usate come chiave
di join implicito.

### Caso d'uso tipico
- Anagrafica clienti e ordini vivono nell'ERP (SQL Server)
- Dati di marketing/lead vivono nel CRM (Oracle o Salesforce via JDBC)
- L'utente vuole un'unica analisi "Vendite e Marketing per Cliente" che
  incrocia entrambe le fonti senza dover replicare manualmente i dati

### Perché è complesso (motivi del rinvio)

1. **Modello dati**: `AreaSource` oggi è una relazione 1:1 con l'area
   (un `area_id` → una config). Serve passare a 1:N, con ogni sorgente che
   dichiara *quali colonne* della tabella fatti alimenta (non più tutte).

2. **Join implicito**: le righe provenienti da sorgenti diverse vanno
   allineate sulla dimensione condivisa (es. "Cliente"). Serve decidere:
   - Come gestire un cliente presente in una sorgente ma non nell'altra
     (riga parziale con colonne null? riga scartata?)
   - Se il join è 1:1 (un cliente = una riga) o può generare più righe
     (es. join con la tabella ordini, che è naturalmente 1:N)

3. **Ordine di esecuzione ETL**: se la sorgente B fa join sulla sorgente A,
   serve garantire che A sia stata sincronizzata per prima, o eseguire
   l'estrazione da entrambe e fare il merge in memoria/staging prima del
   caricamento finale nella tabella fatti.

4. **Conflitti sulle colonne comuni**: se due sorgenti mappano entrambe una
   colonna alla stessa dimensione con valori diversi (es. "Cliente" scritto
   in modo diverso nei due sistemi), serve una regola di precedenza o
   un meccanismo di riconciliazione (già in parte coperto dalle symbol
   table, ma da verificare nel caso multi-sorgente).

5. **UI del wizard**: `ConfigureSourceDialog` va esteso da "singola
   configurazione" a "elenco di sorgenti collegate", con un mapping
   colonne più sofisticato che indica anche *quale sorgente* alimenta
   quale colonna.

### Approccio proposto (da validare quando si affronta il lavoro)

- Estendere `AreaSource` con un campo che indica il sottoinsieme di colonne
  (filtri/somme) alimentate da quella sorgente specifica, invece di
  assumere che una sorgente alimenti sempre tutta la tabella
- `EtlOrchestrator` esegue le sorgenti in sequenza dichiarata (ordine
  esplicito nel campo `AreaSource`, non implicito), caricando ognuna in una
  tabella di staging separata, poi fa un `JOIN`/`FULL OUTER JOIN` finale in
  ClickHouse sulla/e dimensione/i condivisa/e per produrre la tabella fatti
  definitiva (coerente col pattern già adottato per il full-reload sicuro
  con tabella temporanea + rename atomico)
- La UI mostra le sorgenti come una lista ordinabile nel dialog "Sorgenti
  Dati", ognuna con il proprio sottoinsieme di colonne mappate

### Prerequisiti prima di iniziare questo lavoro

- Il ciclo ETL a sorgente singola deve essere solido e testato end-to-end
  in produzione (verifica con dati reali, non solo demo)
- Serve aver validato con un caso reale concreto (cliente con effettivo
  bisogno di due sorgenti) per non progettare "al buio"

---

*Documento creato durante la sessione di sviluppo per tracciare l'idea senza
perderla, mentre il focus resta sul completamento del flusso a sorgente
singola.*
