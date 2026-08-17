# ADR-005: Concurrencia e idempotencia en el procesamiento de notificaciones

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

El servicio puede ejecutarse en múltiples instancias y cada instancia
puede tener múltiples workers procesando notifications.

Por lo tanto, varias unidades de procesamiento pueden intentar
seleccionar la misma notification simultáneamente.

Además, Kafka utiliza una semántica que puede provocar la redelivery de
un mismo evento, por lo que la creación de la notification debe ser
idempotente.

Existe también un problema diferente durante la comunicación con el
webhook externo: el servicio puede enviar correctamente una notificación
y fallar antes de persistir el resultado. En este escenario no es
posible determinar con certeza si el cliente recibió y procesó el
webhook.

## Decisión

Se utilizarán diferentes mecanismos de concurrencia e idempotencia para
cada etapa del procesamiento.

### 1. Idempotencia durante la ingestión

La entidad de notification tendrá una restricción única basada en:

`event_id + client_id`

Esto permitirá que una redelivery del mismo evento desde Kafka no cree
una segunda notification para el mismo cliente.

### 2. Claim atómico de notifications

Los workers utilizarán PostgreSQL para reclamar notifications de forma
atómica.

La selección de trabajo utilizará `SELECT FOR UPDATE SKIP LOCKED` para
permitir que múltiples workers seleccionen diferentes notifications sin
bloquearse entre sí.

La transición hacia `processing` será persistida antes de iniciar la
llamada HTTP al cliente.

La implementación concreta del claim podrá utilizar una combinación de
`SKIP LOCKED` y un `UPDATE` condicional para realizar la transición de
estado de forma atómica.

### 3. Lease para recuperación

Las notifications en estado `processing` tendrán información de lease,
incluyendo un momento de expiración.

Si el worker encargado de una notification falla y el lease expira,
otro worker podrá volver a reclamarla.

Esto evita que una notification quede permanentemente en estado
`processing` después de la caída de un worker.

### 4. Idempotencia del webhook

Cada llamada al webhook incluirá una idempotency key asociada a la
notification.

La idempotencia del receptor será necesaria para manejar el escenario
en el que el servicio envíe correctamente el webhook pero falle antes
de persistir el resultado.

## Máquina de estados

El estado de una notification seguirá conceptualmente este flujo:

    pending -> processing -> completed
                         |
                         L-> pending -> retry -> failed

El estado exacto utilizado para representar un fallo definitivo y los
detalles de la estrategia de retry se definirán en decisiones
posteriores.

## Razones

### Separación de responsabilidades

Los mecanismos utilizados resuelven problemas diferentes:

- Constraint único: evita notifications duplicadas.
- Claim atómico: evita procesamiento concurrente de la misma
  notification.
- Lease: recupera trabajo abandonado.
- Idempotency key: permite al cliente identificar e ignorar copias de deliveries repetidos.

No se utilizará un único mecanismo intentando resolver todos estos
problemas.

### Seguridad frente a múltiples instancias

La coordinación del procesamiento se delega a PostgreSQL, que es
compartido por todas las instancias del servicio.

Esto evita depender de locks mantenidos únicamente en memoria dentro de
una instancia.

### Recuperación

El lease permite que una notification pueda recuperarse después de
fallos de workers, evitando que el estado `processing` se convierta en
un estado permanente.

## Alternativas consideradas

### Optimistic locking

Se consideró utilizar un campo de versión para detectar modificaciones
concurrentes.

Fue descartado como mecanismo principal porque el problema específico
es reclamar una notification pendiente mediante una transición de
estado, para lo cual un claim atómico basado en el estado resulta más
directo.

### `SELECT FOR UPDATE` sin `SKIP LOCKED`

Permitiría utilizar locks de fila, pero los workers podrían quedar
bloqueados esperando por filas que otro worker ya está procesando.

`SKIP LOCKED` permite que los workers continúen buscando trabajo
disponible.

### Lock distribuido externo

No se considera necesario introducir un sistema adicional de locks,
ya que PostgreSQL ya es la fuente de verdad del estado y proporciona
los mecanismos necesarios para coordinar los workers.

## Consecuencias

### Positivas

- Permite múltiples instancias y workers.
- Evita duplicados durante la creación de notifications.
- Evita que dos workers reclamen simultáneamente la misma notification.
- Permite recuperar trabajo abandonado.
- Mantiene la coordinación en PostgreSQL.
- Evita introducir infraestructura adicional para locking.
- Hace explícitos los límites de idempotencia del sistema.

### Negativas

- El claim requiere consultas y coordinación adicional con PostgreSQL.
- El lease necesita una duración adecuada.
- Un lease demasiado corto puede provocar que dos workers procesen una
  misma notification si el primero todavía está ejecutando el webhook.
- Un lease demasiado largo retrasa la recuperación de una notification
  realmente abandonada.
- La idempotencia del webhook depende de la cooperación del sistema
  receptor.

## Garantías

El diseño busca garantizar:

- una única notification para un mismo `event_id` y `client_id`
- ownership exclusivo de una notification durante un claim válido
- recuperación de notifications abandonadas mediante expiración del lease
- capacidad de identificar e ignorar deliveries repetidos mediante una
  idempotency key

No se garantiza exactly-once delivery hacia sistemas externos.

La comunicación con el webhook se considera at-least-once y depende de
la idempotencia del receptor para evitar efectos duplicados.

## Refinamiento

El análisis posterior del modelo de dominio realizado en ADR-009: [ADR-009-domain-model.md](ADR-009-domain-model.md) refinó
la identidad de la relación utilizada para garantizar idempotencia.

Inicialmente se había considerado:

(event_id, client_id)

Posteriormente se determinó que una Notification representa la relación:

(event_id, subscription_id)

Por lo tanto, la constraint única definitiva será:

(event_id, subscription_id)

El mecanismo de idempotencia mediante constraint único permanece sin cambios.

## Decisiones pendientes

Quedan pendientes:

- duración y renovación del lease
- estructura definitiva de la tabla de notifications
- estructura de `delivery_attempts`
- estrategia de retry y exponential backoff
- uso de jitter
- clasificación de errores HTTP;
- límite de concurrencia por cliente
- estrategia de particionamiento de Kafka
- uso de Virtual Threads
- circuit breaker y bulkhead.