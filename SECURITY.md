LIGHTNINGBI - SECURITY MODEL
Overview
Sistema security enterprise-grade multi-livello con RBAC granulare, row-level security nativa ClickHouse, audit immutabile e autenticazione MFA. 
Progettato per compliance, scalabilità e zero-trust architecture.

Autenticazione
Login con BCrypt + pepper server-side. MFA obbligatoria (TOTP) per Direzione/Management con recovery codes. 
Rate limiting 5 tentativi/15min, CAPTCHA dopo 3 fail, lock account dopo 5. Session tracking completo con revoca (no delete) per logout/timeout.

Autorizzazione (RBAC)
Ruoli base: DIREZIONE (tutto), MANAGEMENT (economico sua entity), OPERATIONS (produttivo sua entity). 
Permissions granulari mappate a ruoli via tabelle (system_permissions, role_permissions). Utente può avere ruoli multipli. 
Multi-entity support: controller vede più aziende via user_entities (many-to-many).

Row-Level Security
Enforcement a livello ClickHouse con row policies. Default deny all, policy per ruolo inietta filtri automatici 
(WHERE entity_id IN user.entities). Backend non decide accesso, solo passa credenziali giuste. Connection pool segregato per ruolo 
(pool_direzione, pool_management, pool_operations). Zero trust app layer.

View Separate
fatti_*_direzione (tutti campi), fatti_*_operations (no costi/margini). Accesso controllato da GRANT ClickHouse. 
User DB vede solo view permessa per ruolo. Impossibile bypassare da applicazione.

Audit & Compliance
system_audit_log immutabile (INSERT-only, no UPDATE/DELETE). Traccia: chi, cosa (query hash + snippet), quando, 
view acceduta, filtri applicati, righe restituite, durata, errori. system_auth_events separato per login/fail/MFA. Partizioni mensili, TTL 2 anni configurabile. 
IP anonimizzato (GDPR). Query tagging con session_id per traceability.

Segregazione Servizi
AuthService standalone (login, MFA, user CRUD). QueryEngine separato (query construction, permission check). 
Nessuna dipendenza diretta. SessionState minimale in-memory, no credenziali DB salvate.

Backup & Recovery
Backup giornaliero system_users con versioning. Audit log backup + retention policy. 
Session backup settimanale. Recovery disaster plan obbligatorio.

Defense in Depth
Layer 1: ClickHouse row policies + view GRANTS. Layer 2: Backend permission check via RBAC. Layer 
3: UI nasconde features non permesse. Fiducia primaria in DB, backend aggiunge verifiche, UI solo UX.

