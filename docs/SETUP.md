# LightningBI — Setup nuova installazione

## Chiave di cifratura sorgenti dati (SOURCE_ENCRYPTION_KEY)

LightningBI cifra le password delle connessioni ai database esterni (usate
dall'ETL per estrarre dati) con AES-256-GCM. La chiave di cifratura **non**
è inclusa nel codice: va generata una volta per ogni installazione e non
va mai condivisa tra installazioni diverse (ogni cliente/ambiente = chiave propria).

### Perché è importante

- Se la chiave viene condivisa tra installazioni diverse, chi ha accesso al
  database di un cliente potrebbe decifrare le password salvate da un altro.
- Se la chiave viene **cambiata dopo** che sono già state salvate delle
  sorgenti dati, quelle password cifrate diventano illeggibili (l'ETL smette
  di funzionare per quelle sorgenti finché non vengono ri-salvate).

### Genera la chiave (una tantum, al primo deploy)

**Windows (PowerShell):**
```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

**Linux / macOS:**
```bash
openssl rand -base64 32
```

Il risultato è una stringa base64 che rappresenta 32 byte casuali (chiave AES-256).

### Dove va inserita

Nel `docker-compose.yml` dell'installazione, sotto le environment variables
del servizio `lightningbi-engine`:

```yaml
environment:
  ...
  SOURCE_ENCRYPTION_KEY: "<la_chiave_generata_qui>"
```

### Regole

1. **Genera la chiave una sola volta** per installazione, al primo deploy.
2. **Non modificarla più** una volta che ci sono sorgenti dati configurate —
   romperesti la decifratura di tutte le password già salvate.
3. **Non condividere la stessa chiave** tra ambienti/clienti diversi.
4. **Conservala in un posto sicuro** (password manager aziendale), separata
   dal repository di codice — non deve mai finire su Git.
5. Se serve ruotare la chiave (es. sospetto di compromissione), va scritta
   una procedura di migrazione che decifra tutte le sorgenti con la vecchia
   chiave e le ri-cifra con la nuova, prima di sostituirla in produzione.
   *(Procedura non ancora implementata al momento della stesura di questo
   documento — da fare se/quando serve.)*

---

## Altri parametri da personalizzare per ogni installazione

Oltre a `SOURCE_ENCRYPTION_KEY`, verificare/personalizzare per ogni nuovo
deploy anche:

- `POSTGRES_PASSWORD`, `CLICKHOUSE_PASSWORD` — credenziali database
- `JWT_SECRET` — deve essere lungo almeno 32 caratteri, univoco per installazione
- `SECURITY_PEPPER` — pepper per l'hashing password utenti applicativi
- `ADMIN_PASSWORD` — password dell'utente amministratore iniziale, da cambiare
  subito dopo il primo accesso