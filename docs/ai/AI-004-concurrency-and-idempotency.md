# AI-004: Análisis de concurrencia e idempotencia

## Herramienta

Claude Code

## Propósito

Analizar cómo controlar la concurrencia y la idempotencia durante el
procesamiento de las notificaciones.

El objetivo fue determinar cómo evitar que múltiples instancias o
workers procesen simultáneamente la misma notificación y cómo manejar
los diferentes escenarios de duplicación que pueden aparecer desde la
ingestión del evento hasta la entrega del webhook.

También se buscaba evaluar diferentes mecanismos de claim en PostgreSQL
y determinar cómo recuperar notificaciones cuando un worker falla
durante su procesamiento.

## Prompt

quiero anlaizar ahora la estrategia de concurrencia e idempotencia pra el procesamiento de las notificaciones.
hasta ahora decidí utilizar este flujo general:
kafka -> kafka consumer -> postgresql -> delivery workers -> webhook
tambien decidí utilizar postgresql como fuente de verdad del estado de las notificaciones y no agregar una segunda tecnología de mensajería.
ahora quiero resolver un problema concreto:
el servicio puede tener varias instancias y cada instancia puede tener varios workers procesando notificaciones.
quiero asegurarme de que dos workers o dos instancias no procesen la misma notificación simultaneamente y que una notificacion no termiene genrando deliveries duplicados por problemas de concurrencia.
estoy considerando utilizar postgresql con Select for update skip loked, pero no quiero asumir que sea necesariamente la mejor alternativa.
quiero que compares diferentes estrategias, como por ejemplo:
-select for update skip loked
-optimistic locking mediante versionado
-atomic claim mediante un update condicional
-un estado como pending -> processing
-algún mecanismo de lease/timeout para recuperar trabajos cuando el worker murió mientras lo procesaba.

para entender cada alternativa quiero entender:
-que problema resuelve
-como funcionaría con varias instancias
-que ocurre si dos workers intentan reclamar la misma notificación
-que ocurre si un worker muere despues de reclamarla
-que ocurre si el webhook tarda mucho
-que impacto tiene sobre el throughput
-que complejidad introduce
-en que escenarios podría fallar

tambien quiero diferenciar claramente entre:
1) evitar que dos workers procesen simultaneamente la misma fila
2) evitar que el mismo evento genere dos notificaciones
3) evitar que el cliente reciba dos veces el mismo webhook

no quiero que asumas que resolver el primer problema significa que los otros 2 tambien estan resueltos.

quiero que analices tambien como debería funcionar la idempotencia desde la llegada del evento de kafka hasta el delivery del webhook.
considera escenarios como:
-kafka entrega 2 veces el mismo evento
-2 workers intentan reclamar la misma notificacion
- el worker envia el webhook y muere antes de actualizar postgresql
  -el worker actualiza postgresql pero el proceso falla antes de terminar completamente
  -el mismo notification_envent es solicitado mediante replay

quiero que prestes antención a la diferencia entre exactly-once processing y exactly-once delivery y si realmente es posible garantizar esto cuando el receptor del webhook es un sistema externo que yo no controlo.

y por ultimo quiero que me recomiendes una estrategia para este challenge pero explicando los trade-offs y que garantías reales ofrece

no quiero codigo todavía ni que modifiques el proyecto
la estrategia de retries, virtual threads, bulkhead, circuit breaker y particionamiento de kafka se analizarán despues.

## Principales conclusiones

- `pending → processing` debe considerarse parte de la máquina de estados
  de la notificación y no una alternativa independiente de los mecanismos
  de concurrencia.
- `SELECT FOR UPDATE SKIP LOCKED`, optimistic locking y atomic claim son
  mecanismos diferentes para realizar una transición atómica de estado.
- `SELECT FOR UPDATE SKIP LOCKED` permite que varios workers seleccionen
  trabajo sin bloquearse entre sí.
- Un atomic claim mediante `UPDATE` condicional permite garantizar que
  solamente un worker pueda reclamar una notificación.
- Para el caso analizado, optimistic locking no aporta una ventaja
  significativa frente a un claim basado en el estado de la notificación.
- El mecanismo de claim por sí solo no permite recuperar una notificación
  cuyo worker haya muerto después de reclamarla.
- Se necesita un lease mediante información como `lease_expires_at` para
  permitir que otro worker recupere una notificación abandonada.
- La idempotencia debe resolverse en diferentes niveles:
  . creación de la notification a partir del evento
  . ownership de la notification durante el procesamiento
  . entrega del webhook al cliente.
- Un constraint único sobre `(event_id, client_id)` permite evitar que
  una redelivery de Kafka cree una segunda notification.
- El claim atómico evita que dos workers reclamen simultáneamente la
  misma notification.
- Ningún mecanismo interno puede garantizar que un cliente externo no
  reciba dos veces el mismo webhook si el servicio falla después de
  enviarlo y antes de persistir el resultado.
- Para el webhook se necesita una idempotency key que permita al receptor
  identificar e ignorar copias de eventos duplicados en una entrega.
- El objetivo realista para la comunicación con sistemas externos es
  at-least-once delivery con idempotencia, no exactly-once delivery.

## Impacto en mi análisis

El análisis permitió separar claramente tres problemas que inicialmente
podían parecer uno solo:

1. Evitar que el mismo evento genere múltiples notifications.
2. Evitar que múltiples workers procesen simultáneamente la misma
   notification.
3. Evitar que el cliente reciba dos veces el mismo webhook.

Cada problema requiere un mecanismo diferente.

También decidí utilizar una máquina de estados explícita para las
notifications y agregar información relacionada con el ownership
temporal del procesamiento.

El análisis también permitió identificar que un mecanismo de locking
por sí solo no es suficiente para garantizar la recuperación ante la
caída de un worker. Por esta razón, el diseño deberá contemplar un lease
con expiración.

Finalmente, dejé de considerar exactly-once delivery como un objetivo
del sistema. Para la comunicación con el webhook externo se utilizará
un modelo at-least-once con idempotency key.

## Decisión resultante

Se utilizará una máquina de estados para representar el ciclo de vida
de una notification.

El procesamiento utilizará un mecanismo de claim atómico en PostgreSQL
y `SELECT FOR UPDATE SKIP LOCKED` como parte de la selección concurrente
de trabajo.

Las notifications en procesamiento tendrán información de lease para
permitir su recuperación cuando el worker que las reclamó falle.

La creación de notifications será idempotente mediante un constraint
único sobre `(event_id, client_id)`.

Las llamadas al webhook incluirán una idempotency key para permitir al
cliente identificar e ignorar copias de deliveries repetidos.

## Evidencia

- [Prompt utilizado](screenshots/AI-004-prompt.png)
- [Respuesta obtenida](screenshots/AI-004-response.png)