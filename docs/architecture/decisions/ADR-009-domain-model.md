# ADR-009: Modelo de dominio

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

El sistema necesita representar eventos recibidos desde la plataforma de Cobre, suscripciones de clientes y el ciclo de vida de las entregas realizadas mediante webhooks.
Durante el análisis inicial se identificaron conceptos como Event, Subscription, Notification y DeliveryAttempt, pero era necesario determinar qué representa exactamente cada uno, cómo se relacionan y cuáles son las reglas que deben protegerse dentro del dominio.
También era necesario definir si una Notification representa la relación entre un Event y un Client o entre un Event y una Subscription.
La decisión afecta directamente la unicidad de las notificaciones, la idempotencia, el soporte de múltiples destinos y el modelo de datos.

## Decision

Se adopta un modelo de dominio compuesto principalmente por:

- Event
- Subscription
- Notification
- DeliveryAttempt

### Event

Event representa un hecho ocurrido en la plataforma de Cobre.

Características:

- Es inmutable.
- Tiene un event_id proporcionado por el sistema upstream.
- No posee un ciclo de vida de entrega.
- Puede generar una o más Notifications.

### Subscription

Subscription representa la configuración mediante la cual un cliente solicita recibir notificaciones.

Conceptualmente contiene:

- subscription_id
- client_id
- webhook URL
- HMAC secret
- estado activo/inactivo

Para el alcance actual se modelará una subscription por cliente, sin implementar filtros complejos por tipo de evento.
El diseño podrá evolucionar posteriormente para soportar múltiples subscriptions por cliente o filtros específicos.

### Notification

Notification representa una obligación concreta de entregar un Event mediante una Subscription.
La identidad conceptual de la Notification es:

(event_id, subscription_id)

La relación con el cliente se obtiene a través de la Subscription.
Por lo tanto, se establece una constraint única sobre:

(event_id, subscription_id)

Esto garantiza idempotencia ante redelivery del mismo evento desde Kafka y permite que un mismo Event pueda generar Notifications independientes para diferentes Subscriptions.
Notification será el agregado raíz responsable de proteger su propio ciclo de vida e invariantes.

### DeliveryAttempt

DeliveryAttempt representa un intento individual de entrega HTTP.

Es estrictamente append-only.

Cada intento conserva información histórica suficiente para reconstruir qué ocurrió durante la entrega, incluyendo:

- attempt number
- timestamp
- duración
- outcome
- HTTP status
- error
- URL utilizada
- origen del intento

Los DeliveryAttempts no se actualizan posteriormente.

## Máquina de estados

Notification utilizará cuatro estados:

- PENDING
- PROCESSING
- COMPLETED
- FAILED

### Transiciones permitidas

- PENDING → PROCESSING
- PROCESSING → COMPLETED
- PROCESSING → PENDING
- PROCESSING → FAILED
- FAILED → PENDING

El estado PENDING representa tanto una Notification que todavía no ha sido intentada como una que está esperando su próximo retry.

La diferencia se obtiene mediante campos como attempt_count y next_attempt_at.

No se utilizará un estado RETRYING separado.

### Lease

Las Notifications en PROCESSING estarán protegidas mediante un lease.

Los campos asociados serán conceptualmente:

- claimed_by
- lease_expires_at

Si el lease expira, la Notification podrá recuperarse:

PROCESSING → PENDING

La expiración del lease se registrará como un DeliveryAttempt con outcome INTERRUPTED y contará dentro de attempt_count.

Esto evita que una caída repetida de workers produzca reintentos infinitos que nunca consuman el presupuesto de retries.

## Invariantes

El agregado Notification deberá proteger las siguientes invariantes:

1. No pueden existir dos Notifications para el mismo (event_id, subscription_id).

2. Una Notification no puede cambiar de Event o Subscription después de creada.

3. Solo pueden ejecutarse transiciones de estado válidas.

4. Replay solo puede ejecutarse desde FAILED.

5. attempt_count nunca disminuye ni se reinicia por un replay.

6. DeliveryAttempt es append-only.

7. next_attempt_at solo es relevante mientras la Notification está en PENDING.

8. failure_reason solo se establece cuando la Notification pasa a FAILED.

9. Una Notification en PROCESSING debe tener un lease válido.

## Replay

El endpoint de replay reutilizará la Notification existente.

La transición será:

FAILED → PENDING

No se creará una nueva Notification.

Los DeliveryAttempts anteriores se conservan.

El replay no reinicia attempt_count.

Los intentos producidos por replay se distinguirán de los retries automáticos mediante el origen del intento.

Una estructura conceptual para dicho origen será:

Trigger

- type
- requestedBy

donde type podrá distinguir entre un intento automático y uno originado por un replay manual.

## Payload y Subscription durante los retries

El payload de la Notification queda congelado en el momento de su creación.
Esto garantiza que los retries y replays vuelvan a intentar entregar el mismo contenido asociado al Event original.
En cambio, la URL y el estado de la Subscription se resolverán nuevamente en cada intento.
Esto permite que una corrección de configuración de la Subscription pueda beneficiar a Notifications que todavía están pendientes de entrega.
Para preservar la trazabilidad, cada DeliveryAttempt registrará la URL que efectivamente fue utilizada en ese intento.

## Retry y fallo definitivo

La Notification mantendrá información agregada del ciclo de retries, incluyendo:

- attempt_count
- next_attempt_at
- last_attempted_at
- failure_reason

El historial detallado permanecerá en DeliveryAttempt.

La política de retry utilizará:

- exponential backoff
- cap máximo
- full jitter
- clasificación de errores retryable/permanent

Una Notification se considerará definitivamente fallida cuando:

- ocurra un error permanente, o
- se alcance el máximo de intentos, o
- se alcance la ventana máxima de retry

La razón por la cual se dejó de reintentar se almacenará mediante failure_reason.

Valores posibles:

- MAX_ATTEMPTS_EXCEEDED
- MAX_TIME_EXCEEDED
- PERMANENT_ERROR

## Responsabilidades del dominio

El dominio será responsable de:

- proteger las transiciones de estado
- proteger las invariantes
- determinar cuándo una Notification puede ser reintentada
- determinar cuándo una Notification puede ser reproducida mediante replay
- clasificar los resultados de entrega
- calcular la política de retry
- determinar cuándo una entrega se considera definitivamente fallida.

La infraestructura será responsable de implementar los mecanismos concretos necesarios para ejecutar estas reglas.

## Separación respecto de infraestructura

Los siguientes conceptos no forman parte del modelo conceptual del dominio:

- Kafka
- offsets de Kafka
- particiones
- JSON
- PostgreSQL
- SQL
- SKIP LOCKED
- connection pools
- HTTP client
- Virtual Threads
- semáforos de concurrencia
- Docker

Estos mecanismos serán utilizados por las capas de aplicación e infraestructura para ejecutar las reglas del dominio.

## Client

No se modelará Client como una entidad rica dentro de este bounded context.

El servicio necesita conocer client_id para:

- ownership
- autorización
- aislamiento por cliente
- configuración de Subscription

Sin embargo, la información completa del cliente pertenece a otro contexto de la plataforma.

## Entidades deliberadamente descartadas

No se crearán inicialmente:

- ReplayRequest
- WebhookEndpoint
- EventType
- Client como entidad rica
- estado RETRYING

Estas entidades o conceptos podrían incorporarse posteriormente si aparecen requisitos que justifiquen su existencia.

## Consecuencias

### Positivas

- El modelo representa correctamente la obligación concreta de entrega.
- Un Event puede generar múltiples Notifications independientes.
- Las múltiples Subscriptions quedan soportadas naturalmente.
- La idempotencia se puede garantizar mediante (event_id, subscription_id).
- El historial de entregas queda separado del estado actual.
- Los retries y replay pueden reutilizar el mismo ciclo de vida.
- Las reglas de negocio quedan encapsuladas en el dominio.
- La infraestructura puede cambiar sin alterar el modelo conceptual.

### Negativas

- El modelo requiere más de una tabla persistente.
- Algunas consultas necesitarán combinar Notification y DeliveryAttempt.
- Existe mayor complejidad que un modelo simple de una sola tabla.
- La Subscription debe mantenerse consistente con la configuración utilizada durante las entregas.
- El modelo de dominio requiere proteger explícitamente sus invariantes.

Estas complejidades se consideran justificadas porque retries, replay, auditoría, observabilidad, concurrencia e idempotencia son requisitos centrales del sistema.

## Relation to previous ADRs

Esta decisión refina ADR-005[ADR-005-idempotency-and-concurrency.md](ADR-005-idempotency-and-concurrency.md).
Inicialmente se había considerado una constraint de unicidad basada en:

(event_id, client_id)

El análisis de dominio posterior determinó que la unidad correcta de entrega es la Subscription y no directamente el Client.
La decisión definitiva pasa a ser:

(event_id, subscription_id)

El mecanismo general de idempotencia no cambia: se mantiene una constraint única en persistencia y un INSERT ... ON CONFLICT para absorber redeliveries del mismo evento.

## Alcance

Este ADR define el modelo conceptual del dominio.

No define todavía:

- estructura concreta de paquetes;
- interfaces/ports;
- adapters;
- repositories;
- controllers;
- implementación de Kafka;
- implementación concreta del cliente HTTP;
- esquema SQL definitivo.

Estas decisiones se abordarán posteriormente durante el diseño de la arquitectura hexagonal y de los casos de uso.