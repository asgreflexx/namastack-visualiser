# Namastack Outbox Visualiser

Web-UI für die Tabellen von [Namastack Outbox](https://github.com/namastack/namastack-outbox) (JDBC-Modul, getestet gegen das Schema von v1.11).
Sie zeigt, was in den Outboxen eines oder mehrerer Services liegt, und erlaubt es, Events erneut zu verarbeiten.

- **Dashboard**: offene, fehlgeschlagene und abgeschlossene Records je Service, ältester offener Record, Durchsatz (erstellt vs. abgeschlossen) und Fehler je Handler, live alle 5 s
- **Records**: filterbare Liste mit Detailansicht (Payload, Context, Fehler, Retry-Stand)
- **Key-Timeline**: alle Records eines `record_key` auf einer Zeitachse, mit Markierung, welche Records auf einen Vorgänger warten
- **Blockierte Keys**: FAILED-Records und die Anzahl der Records, die dahinter hängen
- **Cluster**: Namastack-Instanzen mit Heartbeat und die Zuordnung der 256 Partitionen
- **Audit-Log**: wer wann welche Aktion ausgeführt hat

## Aktionen

| Aktion | Gilt für | Was passiert in `outbox_record` |
|---|---|---|
| Erneut versuchen | FAILED | `status=NEW, failure_count=0, failure_reason=NULL, next_retry_at=now` |
| Jetzt ausführen | NEW im Backoff | `next_retry_at=now` |
| Erneut senden (Replay) | COMPLETED | neue Zeile (UUIDv7) mit gleichem Key, Typ, Payload, Context, Handler und Partition, `status=NEW`, `created_at=now` |
| Alle erneut versuchen | alle FAILED eines Handlers/Typs | wie "Erneut versuchen", als ein UPDATE |

Jede Aktion ist ein einzelnes Statement mit Status-Bedingung (`WHERE id = ? AND status = 'FAILED'` usw.). Sie ist damit atomar, und ein doppelter Klick oder eine parallel laufende Namastack-Instanz richtet keinen Schaden an.

Wichtig zum Verständnis: Namastack verarbeitet einen Record erst, wenn alle früheren Records **desselben Keys** abgeschlossen sind. Ein FAILED-Record blockiert also seinen Key. Erst ein Retry gibt die wartenden Records frei.

Ein Replay funktioniert nur, solange der COMPLETED-Record noch existiert, also solange Namastack ihn nicht beim Aufräumen gelöscht hat.

## Schnellstart mit Demodaten

Voraussetzung: JDK 25 (Gradle lädt es über die Toolchain automatisch, falls es fehlt).

```bash
./gradlew bootRun --args='--spring.profiles.active=demo'
```

Dann <http://localhost:8090> öffnen und mit `admin` / `admin` (Operator) oder `viewer` / `viewer` (nur lesen) anmelden.
Das Demo-Profil legt zwei In-Memory-H2-Datenbanken mit dem originalen Namastack-Schema an und simuliert darin laufend Events, Retries, Fehler und Instanzen.

## Eigene Services anbinden

Pro Service-Datenbank kommt ein Eintrag unter `visualiser.sources` in die `application.yml` (oder per Umgebungsvariablen):

```yaml
visualiser:
  sources:
    - id: order-modulith            # erscheint in URLs, nur a-z, 0-9 und -
      name: Order Modulith
      url: jdbc:mysql://db:3306/orders
      username: outbox_visualiser
      password: ${ORDERS_DB_PASSWORD}
      schema:                       # wie namastack.outbox.jdbc.schema-name
      table-prefix: ""              # wie namastack.outbox.jdbc.table-prefix
      tables:                       # nur bei eigenen Tabellennamen nötig
        record: outbox_record
        instance: outbox_instance
        partition: outbox_partition
      read-only: false              # true schaltet alle Aktionen für diese Quelle ab
    - id: payment-service
      name: Payment Service
      url: jdbc:sqlserver://db:1433;databaseName=payments;encrypt=false
      username: outbox_visualiser
      password: ${PAYMENTS_DB_PASSWORD}
  users:
    - username: ops
      password: "{bcrypt}$2a$10$..."
      role: OPERATOR
    - username: dev
      password: "{bcrypt}$2a$10$..."
      role: VIEWER
```

Unterstützte Datenbanken: MySQL, MariaDB, Oracle, SQL Server und H2. Treiber für alle fünf sind enthalten.
Eine nicht erreichbare Datenbank verhindert den Start nicht, sie wird im Dashboard rot angezeigt.
Ohne konfigurierte Benutzer wird beim Start ein Operator `admin` mit Zufallspasswort angelegt und ins Log geschrieben.

### Nötige Datenbankrechte

- `SELECT` auf `outbox_record`, `outbox_instance`, `outbox_partition`
- zusätzlich `UPDATE` und `INSERT` auf `outbox_record`, wenn die Quelle nicht `read-only` ist

## Entwicklung

```bash
./gradlew test              # Unit- und Web-Tests gegen H2
./gradlew integrationTest   # Testcontainers gegen MySQL, MariaDB, Oracle Free und SQL Server (braucht Docker)
./gradlew bootJar           # build/libs/namastack-visualiser-*.jar
```

Die Integrationstests spielen jeweils das originale Namastack-Schema-Skript der Datenbank ein (`src/integrationTest/resources/schema`).

Aufbau:

| Paket | Inhalt |
|---|---|
| `source` | Konfiguration, ein Connection-Pool pro Quelle, Dialekt-Erkennung (nur für Pagination), Tabellennamen |
| `record` | Lesezugriffe auf `outbox_record` |
| `cluster` | Instanzen und Partitionen |
| `stats` | Kennzahlen und Durchsatz fürs Dashboard |
| `action` | Retry, Run-now, Replay, Bulk-Retry und das Audit-Log |
| `web` | Controller, Thymeleaf + htmx, Chart.js |
| `demo` | Datengenerator für das Profil `demo` |

Zeitstempel werden wie bei Namastack selbst als `java.sql.Timestamp` gebunden und gelesen. Visualiser und Services sollten deshalb in derselben JVM-Zeitzone laufen, wenn die Datenbank Spalten ohne Zeitzone verwendet (MySQL/MariaDB `TIMESTAMP`, SQL Server `DATETIME2`).
