# AI-011: Arquitectura hexagonal, puertos, casos de uso y organización del proyecto

## Herramienta

Claude Code

## Propósito

Analizar y definir la arquitectura hexagonal concreta del servicio de
notificaciones antes de comenzar la implementación.

El análisis busca determinar:

- los casos de uso definitivos
- los puertos de entrada y salida
- la separación entre dominio, aplicación y adapters
- la ubicación de Kafka, JSON, REST y el Delivery Worker
- la estrategia de transacciones
- la ubicación de los mecanismos de concurrencia
- el manejo de DTOs y mappers
- la organización física del proyecto
- las dependencias permitidas entre capas
- qué elementos deben ser puertos y cuáles deben permanecer como
  colaboradores internos
- qué decisiones deben permanecer fuera del alcance para evitar
  sobrearquitectura.

Este análisis toma como base las decisiones establecidas en los ADR y AI
anteriores, especialmente el modelo de dominio, la estrategia de delivery
y retry, seguridad y replay.

## Prompt
ya tenemos definidos y aceptos los ADRs @docs/architecture/decisions\ y analisis anteriores incluyendo:
-arquitectura de ingesta desacoplada del delivery
-kafka como adapter de entrada
-postgresql como estado durable
-workers con claim atómico + skip locked
-Notification + DeliveryAttempt
-máquina de estados PENDING/PROCESSING/COMPLETED/FAILED
-retry con exponential backoff + full jitter
-lease para recuperar workers caidos
-virtual threads
-bulkhead global + bulkhead por cliente
-circuit breaker documentado como evolución
-API self-service
-protección BOLA/IDOR
-SSRF
-rate limiting del endpoint de replay
-HMAC + idempotency key
-modelo de dominio Event, Subscription, Notification y DeliveryAttempt
-casos de uso e input/output ports definidos en el análisis anterior

referencia de los analisis anteriores: @docs/ai/AI-010-application-ports-and-use-case-contracts.md

ahora quiero definir la estructura concreta del proyecto utilizando arquitectura hexagonal.
analiza criticamente y propon la estructura de packages/modulos, responsabilidades de clases/interfaces y dependencias permitidas.
quiero que evues especificamente:
1) si este challenge necesita modulos fisicos separados (gradle/maven) o si un único modulo con pacages bien delimitados es suficiente.
2) la estructura de packages recomendada para:
   -domain
   -application
   -adapters de entrada
   -adapters de salida
   -configuración/bootstrap

3) donde deben vivir:
   -entidades de dominio
   -value objects
   -servicios/policies de dominio
   -input ports
   -output ports
   -casos de uso
   -repositories
   -kafka consumer
   -JSON input adapter
   -REST controllers
   -postgresql/JPA
   -webhook HTTP client
   -delivery worker
   -bulkheads/semaphores
   -mappers
   -configuración

4) define las dependencias permitidas entre esas capas y señala explícitamente que dependencias deben estar prohibidas
5) define cómo debería organiarse el flujo:
   -ingest event
   -claim
   -delivery
   -persist result
   -list/detail
   -replay

6) revisa especialmente que el diseño mantenga la regla de no conservar una transacción abierta durante una llamada HTTP
7) revisa donde deben vivir los virtual threads, semaforos globales y por cliente y el worker
8) define coo mapear DTOs de infraestructura hacia objetos de dominio y viceversa son contraminar el dominio con Spring, JPA, Kafka o librerías HTTP
9) determina que interfaces deben ser realmente ports y qué clases deberían permanecer como colaboradores internos sin crear interfaces innecesarias
10) propón una estructura suficientemente concreta para comenzar inmediatamente con la implementación, incluyendo nombres de packages y clases principales
11) cuestiona cualquier parte del diseño que consideres sobrearquitectura para el tiempo disponible del challente (quedan hoy y mañana)
12) no agregues nuevas entidades, patrones, modulos o abstracciones solamente porque sean comunes en arquitectura hexagonal. cada abstracción debe justificar que problema concreto resuelve en este challenge
13) si durante este análisis aparece una decisión arquitectónica nueva con trade-offs relevantes que debería quedar registrada, indícala como candidata a un nuevo ADR. si no existe una decisión que merezca ADR, dilo explicitamente.

Importante: no quiero código todavia. primero quiero cerrar la estructura arquitectónica y las responsabilidades de cada pieza para luego implementarla.

## Principales conclusiones

### 1. Casos de uso definitivos

Se identificaron cinco casos de uso conceptuales:

1. Procesar evento recibido.
2. Entregar notification pendiente.
3. Listar notifications.
4. Obtener detalle de una notification.
5. Reproducir notification fallida.

`Procesar evento recibido` no debe dividirse en múltiples casos de uso.
Resolver subscriptions, crear notifications y persistirlas son pasos
internos de una misma unidad de trabajo.

La entrega de notifications sí debe ser un caso de uso independiente,
aunque sea disparado por un mecanismo interno de scheduling.

El Delivery Worker se considera un input adapter para este caso de uso.

### 2. Input Ports

Se utilizarán cuatro interfaces de entrada:

- `IngestEventPort`
- `DeliverNotificationPort`
- `NotificationQueryPort`
- `ReplayNotificationPort`

`NotificationQueryPort` agrupará las operaciones de listado y detalle,
pues no existe suficiente complejidad para justificar dos interfaces
independientes.

Los ports no conocerán:

- Kafka
- ConsumerRecord
- offsets
- HTTP
- ResponseEntity
- DTOs de infraestructura
- entidades JPA
- mecanismos de autenticación específicos.

### 3. Output Ports

Se utilizarán:

- `NotificationRepository`
- `NotificationClaimRepository`
- `SubscriptionPort`
- `WebhookSenderPort`

`NotificationClaimRepository` se separa deliberadamente del repositorio
general porque el claim no es una operación CRUD convencional.

Su responsabilidad es seleccionar y reclamar trabajo de forma atómica,
incluyendo la utilización de `SKIP LOCKED` cuando corresponda.

No se considera necesario separar adicionalmente lectura y escritura en
repositorios independientes, ya que introduciría una forma de CQRS sin
beneficio proporcional para el challenge.

### 4. Kafka y JSON comparten el mismo Input Port

Kafka y JSON serán adapters de entrada independientes.

Ambos realizarán:

    formato externo
          ↓
    mapper del adapter
          ↓
    Event de dominio
          ↓
    IngestEventPort

Kafka no será un output port.

El core no tendrá ninguna dependencia de Kafka ni conocerá offsets,
partitions, topics o `ConsumerRecord`.

El adapter Kafka será responsable de confirmar el offset después de que el
caso de uso termine correctamente.

El adapter JSON utilizará el mismo caso de uso sin introducir una segunda
implementación de la lógica de negocio.

### 5. Separación entre dominio y application

El dominio será responsable de las reglas que representan el
comportamiento del negocio.

Entre ellas:

- transiciones de `Notification`
- invariantes
- matching entre Event y Subscription
- clasificación de errores
- política de retry
- cálculo conceptual del backoff.

Application será responsable de la orquestación:

- invocar output ports
- coordinar los pasos del caso de uso
- traducir resultados técnicos hacia objetos de dominio
- coordinar las transacciones cortas
- invocar los casos de uso desde los input ports.

### 6. Notification mantiene sus invariantes

Las transiciones no se expondrán como setters libres.

Se utilizarán operaciones de dominio como:

- `claim()`
- `markCompleted()`
- `scheduleRetry()`
- `markFailed()`
- `replay()`.

Esto permite que el agregado proteja sus propias reglas.

Por ejemplo:

- `markCompleted()` solo es válido desde `PROCESSING`
- `replay()` solo es válido desde `FAILED`
- `attempt_count` nunca disminuye
- `next_attempt_at` solo aplica mientras la notification está pendiente
- una Notification no cambia de Event o Subscription.

### 7. DeliveryOutcome

El adapter HTTP no expondrá excepciones ni tipos específicos de la
librería HTTP hacia el dominio.

`WebhookSenderPort` devolverá un `DeliveryOutcome`.

Este value object podrá representar:

- `SUCCESS`
- `HTTP_ERROR`
- `TIMEOUT`
- `CONNECTION_ERROR`
- `DNS_ERROR`
- `INTERRUPTED`
- `CIRCUIT_OPEN`.

También podrá contener:

- HTTP status
- duración
- detalle de error acotado
- fragmento de respuesta acotado
- `Retry-After` cuando corresponda.

El adapter será responsable de transformar las excepciones técnicas en
estos resultados.

### 8. Manejo de errores

Se utilizará una estrategia híbrida.

Los resultados de negocio esperables utilizarán tipos explícitos, por
ejemplo:

- `Found`
- `NotFound`
- `Replayed`
- `NotInFailedState`
- `ConcurrentReplayConflict`.

Los fallos técnicos inesperados se propagarán como excepciones.

El caso de uso no decidirá directamente qué código HTTP debe devolver.

El adapter REST realizará dicha traducción mediante un mecanismo
centralizado.

El adapter Kafka utilizará la presencia o ausencia de un fallo técnico
para decidir si confirma el offset.

### 9. Transacciones cortas

Se reafirma la regla establecida anteriormente:

    Claim → COMMIT
    HTTP → fuera de transacción
    Persist result → COMMIT

No debe existir una transacción que permanezca abierta durante la llamada
HTTP.

Los métodos orquestadores de los casos de uso no utilizarán
`@Transactional`.

Las transacciones vivirán en los adapters de persistencia que realizan
operaciones concretas como:

- `claimDueBatch`
- `saveIdempotent`
- `recordAttemptResult`
- `transitionFailedToPending`.

### 10. Clock

No se considera necesario crear un `TimeProvider` propio.

Se utilizará `java.time.Clock`.

Producción:

    Clock.systemUTC()

Tests:

    Clock.fixed(...)

Esto permite probar de forma determinística:

- backoff
- `next_attempt_at`
- expiración de lease
- ventana máxima de retry.

### 11. Concurrencia

Los mecanismos de concurrencia permanecerán fuera del dominio y de los
casos de uso.

El Worker utilizará:

- Virtual Threads
- semáforo global
- semáforo por cliente.

La arquitectura conceptual será:

    DeliveryWorker
          ↓
    claimDueBatch()
          ↓
    ConcurrencyLimiter
          ↓
    Virtual Thread
          ↓
    DeliverNotificationPort
          ↓
    DeliverNotificationUseCase

El concepto de "cantidad de workers" deja de ser la principal palanca de
control al utilizar Virtual Threads.

La capacidad se controla mediante permisos de concurrencia, no mediante
un pool fijo de workers.

### 12. DTOs y mappers

Los DTOs permanecerán confinados a sus adapters.

Ejemplos:

- Kafka DTO
- JSON DTO
- REST DTO
- JPA Entity.

Cada adapter tendrá sus propios mappers explícitos.

No se utilizará:

- mapper genérico
- librería reflexiva de mapping
- reutilización de entidades JPA como objetos de dominio.

El dominio permanecerá libre de anotaciones de:

- Spring
- JPA
- Jackson
- Kafka.

### 13. Autorización

La autenticación será responsabilidad del adapter REST.

El adapter resolverá el `client_id` a partir de la identidad autenticada.

La autorización será responsabilidad de la aplicación/dominio.

Para operaciones individuales se utilizará una regla equivalente a:

    notification.belongsTo(clientId)

Para listados, el `client_id` deberá formar parte obligatoria del scope de
la consulta.

La aplicación nunca confiará en un `client_id` proporcionado libremente
por el consumidor de la API.

### 14. Organización física

Se utilizará un único módulo Gradle.

No se crearán subproyectos físicos para domain/application/adapter.

La estructura propuesta será:

    com.cobre.notifications/

    ├── domain/
    │   ├── event/
    │   ├── subscription/
    │   ├── notification/
    │   └── delivery/
    │
    ├── application/
    │   ├── port/
    │   │   ├── in/
    │   │   └── out/
    │   ├── usecase/
    │   └── result/
    │
    ├── adapter/
    │   ├── in/
    │   │   ├── rest/
    │   │   ├── kafka/
    │   │   ├── json/
    │   │   └── worker/
    │   │
    │   └── out/
    │       ├── persistence/
    │       └── webhook/
    │
    └── config/

La separación mediante packages se considera suficiente para el alcance
del challenge.

### 15. Dependencias entre capas

La dirección permitida será:

    domain
       ↑
    application
       ↑
    adapters

En términos prácticos:

- domain no depende de nadie
- application depende de domain
- input adapters dependen de application
- output adapters implementan ports de application
- config conecta implementaciones concretas.

No se permitirá que:

- domain dependa de adapters
- domain dependa de Spring/JPA/Kafka
- application dependa de adapters concretos
- REST acceda directamente a repositorios JPA
- Kafka acceda directamente a persistencia
- DTOs de infraestructura aparezcan en los ports.

### 16. Puertos vs. colaboradores internos

Se consideran ports aquellos conceptos que cruzan una frontera tecnológica
real.

Por ello tendrán interfaces:

- Input Ports
- `NotificationRepository`
- `NotificationClaimRepository`
- `SubscriptionPort`
- `WebhookSenderPort`.

No se crearán interfaces adicionales para:

- `NotificationFactory`
- `RetryPolicy`
- `DeliveryErrorClassifier`
- mappers
- `ConcurrencyLimiter`
- `HmacSigner`
- `SsrfSafeConnector`.

Estas piezas son colaboradores internos o detalles de infraestructura
cuya abstracción adicional no aporta valor suficiente al challenge.

### 17. Elementos deliberadamente fuera de alcance

Para evitar sobrearquitectura no se implementarán inicialmente:

- módulos Gradle independientes
- Circuit Breaker completo
- cuotas de replay de largo plazo
- Subscription con reglas complejas de matching
- entidad `ReplayRequest`
- wrappers de IDs
- CQRS completo
- mapper framework genérico
- `TimeProvider` propio
- API Gateway
- OAuth2/OIDC completo
- proxy de egreso dedicado.

También permanece fuera del alcance actual persistir explícitamente el
origen `AUTOMATIC` vs. `MANUAL_REPLAY` dentro de `Notification`.

Esta última limitación fue documentada previamente en ADR-006.

## Decisiones pendientes identificadas

### 1. Valores concretos de configuración

Todavía deben definirse durante la implementación:

- `base_delay`
- `max_delay`
- `max_attempts`
- `max_retry_window`
- límite global de concurrencia
- límite de concurrencia por cliente
- timeouts HTTP.

Estos valores son parámetros de tuning y no modifican la arquitectura.

### 2. Implementación concreta de SubscriptionPort

Debe definirse la forma mínima de almacenar y resolver subscriptions para el
challenge, dado que no existe un CRUD de subscriptions solicitado.

### 3. ArchUnit

Podría incorporarse una pequeña suite de ArchUnit para verificar las
dependencias arquitectónicas.

Es opcional y no debe retrasar la implementación funcional.

### 4. Nuevas decisiones arquitectónicas

Si durante la implementación aparece una decisión que cambie una frontera
arquitectónica, una dependencia importante o una decisión previamente
registrada, se evaluará si requiere un nuevo ADR.

Las decisiones puramente de implementación no generarán nuevos ADRs.

## Impacto en mi análisis

Este análisis cierra la etapa de diseño estructural del servicio.

A partir de este punto, el objetivo principal deja de ser seguir
agregando capas de diseño y pasa a ser implementar y validar la arquitectura
definida.

La arquitectura resultante mantiene una separación clara:

    Domain
       ↓
    Application
       ↓
    Ports
       ↓
    Adapters

Esto permite demostrar que Kafka, JSON, REST, PostgreSQL y el cliente HTTP
son detalles reemplazables y no forman parte del núcleo del dominio.

La decisión de utilizar un único módulo Gradle reduce el tiempo de
configuración y mantiene la arquitectura suficientemente explícita mediante
packages.

La separación de `NotificationClaimRepository` permite mantener aislada la
operación de claim sin convertir todo el repositorio en una abstracción
excesivamente grande.

La ubicación del Worker y de los mecanismos de concurrencia fuera de
application mantiene desacopladas las reglas de negocio de las políticas
de ejecución.

La decisión de mantener fuera de alcance el origen persistente de
`MANUAL_REPLAY` evita aumentar el modelo de dominio únicamente para soportar
priorización y bulkheads específicos de replay durante este challenge.

En consecuencia, el diseño se considera suficientemente definido para
comenzar la implementación.

El siguiente paso será construir el skeleton del proyecto y comenzar por
el dominio, seguido de los ports y los adapters de persistencia.

## Evidencia

- [AI-011 Prompt](screenshots/AI-011-prompt.png)
- [AI-011 Response](screenshots/AI-011-response.png)