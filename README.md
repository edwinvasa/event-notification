# event-notification

Servicio de notificación de eventos de la plataforma Cobre: recibe eventos generados por la plataforma, confirma si deben entregarse a un cliente según su suscripción, y los entrega de forma confiable mediante webhooks HTTPS — con retries, observabilidad y una API self-service para que cada cliente consulte y reintente sus propias notificaciones.

## 1. Descripción del proyecto

**Problema que resuelve:** dado un stream de eventos (pagos, transferencias, etc.) generados por la plataforma, el servicio determina si cada evento debe notificarse a un cliente vía webhook, entrega esa notificación de forma resiliente (con retries ante fallos transitorios) y expone una API para que el cliente consulte el estado de sus notificaciones y solicite el reenvío de las que fallaron definitivamente.

**Capacidades principales:**
- Ingestión de eventos desde Kafka y desde un archivo JSON (dos adapters de entrada independientes, misma lógica de negocio).
- Entrega de notificaciones vía webhook HTTPS con retry, backoff exponencial y jitter.
- Persistencia idempotente del estado de cada notificación y del historial de cada intento de entrega.
- REST API self-service (`GET /notification_events`, `GET /notification_events/{id}`, `POST /notification_events/{id}/replay`).
- Autenticación por API Key, protección BOLA, SSRF, HMAC y rate limiting.
- Observabilidad vía Prometheus/Micrometer.

## 2. Descripción de la arquitectura

Arquitectura hexagonal: el dominio y los casos de uso (`domain/`, `application/`) no dependen de Spring, JPA, Kafka ni ningún otro framework. Toda la infraestructura vive en `adapter/` y `config/`.

```
Kafka ──┐
        ├─> Input Port (IngestEventPort) ─> Application Core ─> PostgreSQL (Notification)
JSON  ──┘

PostgreSQL ─> DeliveryWorker (claim + lease) ─> Webhook Sender ─> Client Webhook (HTTPS)

REST API (self-service) ──> Application Core (query/replay) ──> PostgreSQL
```

- **Kafka y JSON** son adapters de entrada intercambiables (`adapter/in/kafka`, `adapter/in/json`) que llaman al mismo puerto de aplicación; el core no sabe de dónde vino el evento.
- **PostgreSQL** es la fuente de verdad del estado de cada `Notification` y su historial de `DeliveryAttempt` (Kafka es solo mecanismo de ingestión, no almacena estado).
- **DeliveryWorker** reclama notificaciones pendientes con `SELECT ... FOR UPDATE SKIP LOCKED`, realiza la llamada HTTP fuera de cualquier transacción de base de datos, y persiste el resultado en un paso separado.
- **REST self-service API** permite a cada cliente (autenticado por API Key) consultar y reintentar sus propias notificaciones.
- **Webhook delivery** usa un cliente HTTP propio con protección SSRF y firma HMAC.
- **Observabilidad** vía Micrometer + Prometheus.

Las decisiones de arquitectura completas están documentadas en `docs/architecture/decisions/` (ADR-001 a ADR-009).

## 3. Tecnologías utilizadas

- Java 21 (toolchain configurado en `build.gradle`)
- Spring Boot 4.1.0 (Web MVC, Data JPA, Security, Actuator, Validation, Flyway)
- PostgreSQL 17 (vía `postgres:17` en Docker)
- Apache Kafka 4.2.1, modo KRaft sin Zookeeper (vía `apache/kafka:4.2.1` en Docker)
- Flyway para migraciones de base de datos
- Micrometer + Prometheus registry para métricas
- Bucket4j para rate limiting
- Gradle (wrapper incluido, `./gradlew`)

## 4. Requisitos previos

- JDK 21
- Docker y Docker Compose (para PostgreSQL y Kafka)
- Sin otros requisitos: no se usa Testcontainers, no se necesita ninguna herramienta CLI de Kafka instalada en el host (los comandos de ejemplo de este README se ejecutan dentro del propio contenedor de Kafka).

## 5. Ejecución local

**1. Levantar la infraestructura:**

```bash
docker compose up -d
```

Esto levanta `event-notification-postgres` (puerto `5432`) y `event-notification-kafka` (puerto `9092`, KRaft de un solo nodo).

**2. Variables de entorno requeridas** (usadas por `spring.datasource.*` en `application.properties`):

```
DB_URL=jdbc:postgresql://localhost:5432/event_notification
DB_USERNAME=event_notification
DB_PASSWORD=event_notification_dev
```

**3. Ejecutar la aplicación:**

macOS/Linux (bash/zsh):

```bash
DB_URL="jdbc:postgresql://localhost:5432/event_notification" \
DB_USERNAME="event_notification" \
DB_PASSWORD="event_notification_dev" \
./gradlew bootRun
```

Windows (PowerShell):

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/event_notification"
$env:DB_USERNAME = "event_notification"
$env:DB_PASSWORD = "event_notification_dev"
./gradlew bootRun
```

Flyway ejecuta las migraciones automáticamente al arrancar (`spring.flyway.enabled=true`). Por defecto, la ingestión JSON, la ingestión Kafka y la siembra de datos demo están **deshabilitadas** (ver secciones 6, 7 y 13 para habilitarlas explícitamente).

**4. Ejecutar los tests:**

```bash
./gradlew test
./gradlew build
```

Los tests de integración corren contra PostgreSQL real (mismo contenedor de arriba) — el proyecto no usa Testcontainers deliberadamente.

## 6. Kafka

- Configurado vía las propiedades `kafka.bootstrap-servers` (`localhost:9092`), `kafka.topic` (`notification-events`) y `kafka.group-id` (`event-notification`) en `application.properties`.
- El listener (`NotificationEventsKafkaListener`) está **deshabilitado por defecto** (`ingestion.kafka.enabled=false`) — cuando está en `false`, el bean del listener ni siquiera se crea, por lo que la app y el test suite arrancan sin necesitar un broker Kafka disponible.
- Para habilitarlo al ejecutar la app: `./gradlew bootRun --args='--ingestion.kafka.enabled=true'`.

**Publicar un evento de prueba manualmente** (sin necesitar herramientas de Kafka en el host, usando el propio contenedor):

```bash
echo '{"event_id": "EVT-TEST-001", "event_type": "credit_card_payment", "client_id": "CLIENT001", "content": "Test payment", "delivery_date": "2026-08-18T22:00:00Z"}' | docker exec -i event-notification-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic notification-events
```

(comando de una sola línea, portable entre bash/macOS/Linux y PowerShell)

Formato esperado del mensaje: `event_id`, `event_type`, `client_id`, `content`, `delivery_date` (ISO-8601). El `client_id` debe corresponder a un cliente con una subscription activa (ver sección Demo).

## 7. Ingestión desde JSON

- Está **deshabilitada por defecto** (`ingestion.json.enabled=false`).
- Usa el archivo indicado en `ingestion.json.location` (por defecto `file:challenge/notification_events.json`, el archivo provisto por el challenge).
- Para ejecutar la app con la ingestión JSON habilitada:

```bash
./gradlew bootRun --args='--ingestion.json.enabled=true'
```

Al arrancar, `NotificationEventsJsonIngestionRunner` lee el archivo y llama al mismo puerto de ingestión que usa el adapter de Kafka.

## 8. API REST

Todas las rutas requieren el header `X-Api-Key` con la API Key del cliente. El `client_id` se obtiene siempre de la identidad autenticada, nunca de un parámetro enviado por el cliente.

### `GET /notification_events`
Lista las notificaciones del cliente autenticado.

Filtros (query params, todos opcionales y combinables):
- `created_from` (Instant ISO-8601)
- `created_to` (Instant ISO-8601)
- `status` (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`)

### `GET /notification_events/{notification_event_id}`
Detalle de una notificación puntual (incluye el payload completo). Devuelve `404` si la notificación no existe o pertenece a otro cliente.

### `POST /notification_events/{notification_event_id}/replay`
Reintenta una notificación en estado `FAILED` (transición atómica `FAILED -> PENDING`). Sujeto a rate limiting por cliente. Devuelve `404` si no pertenece al cliente autenticado o no está en `FAILED`.

## 9. Observabilidad

- `GET /actuator/health` — público, sin autenticación.
- `GET /actuator/prometheus` — público, sin autenticación (formato Prometheus). Todo el resto de endpoints de Actuator requiere API Key o no está expuesto.

Métricas de delivery expuestas:
- `notification.delivery.attempts` (counter, tag `outcome`)
- `notification.delivery.duration` (timer, tag `outcome`)
- `notification.retry.scheduled` (counter)
- `notification.failed.definitive` (counter, tag `reason`)
- `notification.backlog.pending` (gauge)

## 10. Pruebas

```bash
./gradlew test    # suite completa
./gradlew build   # suite + empaquetado
```

- Tests unitarios de dominio y casos de uso (mocks).
- Tests de integración contra **PostgreSQL real** (el contenedor de `docker compose`), incluyendo el mecanismo de claim con `SKIP LOCKED` y la recuperación de leases expirados.
- Tests del adapter de Kafka: unitarios (listener aislado), un test que confirma que el listener no existe cuando `ingestion.kafka.enabled=false`, y un test de integración con un broker Kafka real embebido (`@EmbeddedKafka`, sin Docker ni Testcontainers).
- El flujo E2E completo (**Kafka → Kafka listener → PostgreSQL → DeliveryWorker → Webhook**) fue verificado manualmente end-to-end contra el `docker compose` real: un evento publicado en el tópico resultó en una `Notification` en estado `COMPLETED` con un `DeliveryAttempt` exitoso (HTTP 200) registrado en PostgreSQL.

Por defecto, `ingestion.json.enabled`, `ingestion.kafka.enabled` y `demo.seed.enabled` están en `false`, por lo que `./gradlew build` es determinístico y no depende de un broker Kafka ni de llamadas HTTP externas.

## 11. Seguridad

- **API Key authentication**: cada request a la API debe incluir `X-Api-Key`; sin ese header o con una key inválida, la request es rechazada antes de llegar a cualquier endpoint de negocio.
- **BOLA protection**: el `client_id` nunca se toma de la request; toda consulta o mutación de una notificación está scopeada por el cliente autenticado a nivel de query. Acceder a una notificación de otro cliente devuelve `404`, no `403` (evita revelar la existencia del recurso).
- **SSRF protection**: antes de cada intento de entrega se resuelve el hostname del webhook, se valida la IP resultante contra rangos privados/loopback/link-local, y la conexión HTTP se realiza directamente contra esa IP ya validada (sin volver a resolver el hostname y sin seguir redirects). Solo se permite el puerto HTTPS.
- **HMAC**: cada request al webhook se firma con `HMAC_SHA256(secret, timestamp + "." + raw_body)`, con un secreto independiente por suscripción.
- **Rate limiting**: el endpoint de replay tiene un límite de requests por cliente (`replay.rate-limit.capacity` / `replay.rate-limit.window`).

## 12. Aspectos destacados de arquitectura y resiliencia

- **Idempotencia**: constraint único `(event_id, subscription_id)` en `notifications` — una redelivery del mismo evento desde Kafka nunca crea una segunda notificación.
- **Retries**: backoff exponencial con jitter completo y tope máximo, clasificación de errores (4xx no reintentables salvo 429, 5xx reintentables, `Retry-After` respetado cuando el cliente lo provee).
- **Leases**: una notificación reclamada (`PROCESSING`) lleva `claimed_by` y `lease_expires_at`; si el worker que la reclamó muere, otro worker puede volver a reclamarla una vez expira el lease.
- **Fencing**: al persistir el resultado de una entrega, se verifica que la notificación siga perteneciente al mismo `claimed_by`/lease con el que fue reclamada, descartando el resultado si otro worker ya la retomó.
- **Backpressure**: límite de concurrencia global y límite de concurrencia por cliente (semáforos) — un cliente con un webhook lento o caído no puede monopolizar la capacidad del servicio.

## 13. Demostración

`ingestion.json.enabled`, `ingestion.kafka.enabled` y `demo.seed.enabled` están **deshabilitados por defecto**. Para reproducir el flujo completo **Kafka → PostgreSQL → DeliveryWorker → Webhook** hay que habilitarlos explícitamente vía `--args`:

**1. Levantar infraestructura:**

```bash
docker compose up -d
```

**2. Arrancar la app con Kafka habilitado y la siembra de datos demo** (crea subscriptions/API keys activas para CLIENT001/002/003):

macOS/Linux (bash/zsh):

```bash
DB_URL="jdbc:postgresql://localhost:5432/event_notification" \
DB_USERNAME="event_notification" \
DB_PASSWORD="event_notification_dev" \
./gradlew bootRun --args='--ingestion.kafka.enabled=true --demo.seed.enabled=true'
```

Windows (PowerShell):

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/event_notification"
$env:DB_USERNAME = "event_notification"
$env:DB_PASSWORD = "event_notification_dev"
./gradlew bootRun --args='--ingestion.kafka.enabled=true --demo.seed.enabled=true'
```

**3. En otra terminal, publicar un evento al tópico** (comando de una sola línea, portable entre bash y PowerShell):

```bash
echo '{"event_id": "EVT-DEMO-001", "event_type": "credit_card_payment", "client_id": "CLIENT001", "content": "Demo payment", "delivery_date": "2026-08-18T22:00:00Z"}' | docker exec -i event-notification-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic notification-events
```

**4. Verificar el resultado en PostgreSQL:**

```bash
docker exec event-notification-postgres psql -U event_notification -d event_notification -c "SELECT event_id, status, attempt_count FROM notifications WHERE event_id = 'EVT-DEMO-001';"
```

En unos segundos (el worker hace polling cada `worker.poll-interval`, 5s por defecto), la notificación debería quedar en `COMPLETED`.

`demo.seed.enabled=true` crea, para `CLIENT001`, `CLIENT002` y `CLIENT003`, una subscription activa y una API Key (`X-Api-Key: demo-key-CLIENT00X`) — necesarias para poder autenticarse contra la REST API o para que el flujo de ingestión encuentre una subscription activa a la que entregar.

## 14. trade-offs

- `GET /notification_events` no implementa paginación; devuelve el listado completo del cliente.
- El rate limiter del endpoint de replay utiliza almacenamiento en memoria y no es distribuido entre múltiples instancias.
- El entorno Kafka de desarrollo utiliza un broker KRaft de un solo nodo; un despliegue productivo utilizaría múltiples brokers/particiones según las necesidades de escala.

Decisiones deliberadamente fuera de alcance (Circuit Breaker, API Gateway/BFF, OAuth2/OIDC, etc.) están documentadas como evolución futura en `docs/architecture/decisions/`.

## 15. Estructura del proyecto

```
src/main/java/com/edwin/eventnotification/
├── domain/                  # Modelo de dominio puro (sin dependencias de framework)
│   ├── event/                Event
│   ├── notification/         Notification, NotificationStatus
│   └── subscription/         Subscription
├── application/              # Casos de uso y puertos (sin dependencias de framework)
│   ├── port/                  in/ (puertos de entrada), out/ (puertos de salida)
│   ├── usecase/                IngestEventUseCase, DeliverNotificationUseCase, ReplayNotificationUseCase, ...
│   ├── result/
│   └── exception/
├── adapter/
│   ├── in/
│   │   ├── json/              Adapter de ingestión desde archivo JSON
│   │   ├── kafka/              Adapter de ingestión desde Kafka
│   │   ├── rest/                Controller REST + seguridad (API Key)
│   │   ├── worker/              DeliveryWorker
│   │   └── seed/                Seed de datos demo (subscriptions/API keys)
│   └── out/
│       ├── persistence/         Repositorios JPA / PostgreSQL
│       ├── webhook/              Cliente HTTP + protección SSRF + HMAC
│       └── metrics/              Adapters de Micrometer
└── config/                    Configuración de Spring (Security, casos de uso, etc.)

src/main/resources/db/migration/   # Migraciones Flyway (V1-V6)
docs/architecture/decisions/       # ADR-001 a ADR-009
docs/ai/                            # Documentación del uso de IA durante el desarrollo
challenge/                          # Enunciado del challenge y notification_events.json
docker-compose.yml                  # PostgreSQL + Kafka (KRaft) para desarrollo local
```
