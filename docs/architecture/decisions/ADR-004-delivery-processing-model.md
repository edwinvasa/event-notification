# ADR-004: Separar la ingestión del procesamiento de deliveries

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

La solución debe recibir eventos de forma asíncrona y posteriormente
entregarlos a los clientes mediante webhooks HTTPS.

Durante el análisis se evaluaron cuatro alternativas:

1. Kafka → Consumer → Webhook.
2. Kafka → Consumer → PostgreSQL → Workers.
3. Kafka → Consumer → PostgreSQL → Scheduler/Workers.
4. Kafka → Consumer → PostgreSQL → Queue → Workers.

También se consideró el escenario de recibir un burst de eventos, por
ejemplo 300 eventos prácticamente al mismo tiempo.

Una preocupación importante es evitar que la velocidad de ingestión
determine directamente la cantidad de llamadas HTTP simultáneas hacia
los clientes.

Además, el sistema debe mantener el estado de las notificaciones,
soportar retries y permitir replay.

## Decisión

Se separará la ingestión de eventos del procesamiento de deliveries.

El flujo principal será:

    Kafka -> Kafka Consumer -> PostgreSQL -> Delivery Workers -> Client Webhook

El consumer de Kafka tendrá una responsabilidad limitada: recibir el
evento, realizar el procesamiento necesario para determinar la
notificación y persistir de forma idempotente el registro correspondiente
en PostgreSQL.

El commit del offset de Kafka se realizará después de que la persistencia
haya sido completada correctamente.

Los deliveries serán procesados posteriormente por workers que
obtendrán desde PostgreSQL las notificaciones pendientes o cuyo próximo
intento ya esté disponible.

No se introducirá una segunda tecnología de mensajería para el
procesamiento de deliveries en esta etapa.

## Razones

### Separación de throughput

La ingestión de eventos y el envío de webhooks tienen características
diferentes.

Kafka puede absorber un burst de eventos sin obligar al servicio a
realizar inmediatamente la misma cantidad de llamadas HTTP.

Por ejemplo, ante 300 eventos, estos pueden persistirse como
notificaciones pendientes y ser procesados por los workers de acuerdo
con la capacidad de delivery definida.

### Resiliencia

Si un worker falla después de que una notificación fue persistida,
el estado persistido permite recuperar posteriormente el procesamiento.

La caída del mecanismo de delivery no bloquea directamente la ingestión
de nuevos eventos.

### Retries

El estado de retry puede mantenerse en PostgreSQL mediante información
como:

- número de intento;
- próximo intento;
- estado de delivery.

Esto permite que los workers procesen únicamente las notificaciones
cuyo próximo intento esté disponible.

La estrategia concreta de retry será definida posteriormente.

### Evitar infraestructura innecesaria

Una segunda queue proporcionaría mecanismos de distribución y
redelivery, pero introduciría otra pieza de infraestructura y un nuevo
problema de consistencia entre PostgreSQL y dicha queue.

Para el alcance actual del challenge no se considera necesario agregar
esa complejidad.

## Alternativas consideradas

### Kafka → Consumer → Webhook

Se descartó porque acopla directamente la velocidad de ingestión con
la velocidad del delivery.

Además, una llamada HTTP lenta podría mantener ocupado al consumer de
Kafka y afectar su procesamiento.

### Kafka → PostgreSQL → Workers

Se considera la alternativa seleccionada.

Permite separar ingestión y delivery y utilizar PostgreSQL como fuente
de verdad para el estado de las notificaciones.

### Kafka → PostgreSQL → Scheduler → Workers

No se considera necesario introducir un scheduler independiente en
esta etapa.

Los propios workers pueden seleccionar trabajo disponible utilizando
el estado persistido y el momento del próximo intento.

### Kafka → PostgreSQL → Queue → Workers

Se descartó inicialmente porque agrega una tercera infraestructura sin
resolver un problema que PostgreSQL no pueda manejar dentro del alcance
del challenge.

La decisión podría revisarse si los requisitos de escala cambiaran
significativamente.

## Consecuencias

### Positivas

- Desacopla la ingestión del delivery.
- Permite absorber bursts de eventos.
- Permite controlar independientemente la concurrencia de los webhooks.
- PostgreSQL mantiene el estado centralizado.
- Facilita retries y replay.
- Evita agregar una segunda tecnología de mensajería.
- Permite escalar consumers y workers de forma independiente.

### Negativas

- Requiere implementar un mecanismo de claim de trabajo en PostgreSQL.
- Requiere controlar concurrencia entre múltiples workers e instancias.
- Introduce polling sobre PostgreSQL.
- La estrategia de retries debe implementarse en la aplicación.
- Debe resolverse el escenario en el que un worker falle durante un
  delivery.

## Decisiones pendientes

Este ADR no define todavía:

- estrategia exacta de idempotencia
- estrategia de locking/claim
- lease timeout para trabajos que queden en `in_progress`
- particionamiento de Kafka
- número de workers
- concurrencia máxima por cliente
- estrategia de retry y backoff
- comportamiento frente a HTTP 4xx, 5xx y 429
- uso de Virtual Threads
- circuit breaker y bulkhead
- modelo de `DeliveryAttempt`
- contrato de idempotencia con el cliente.