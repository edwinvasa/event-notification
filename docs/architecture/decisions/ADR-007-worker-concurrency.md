# ADR-007: Concurrencia de los Delivery Workers

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

Los Delivery Workers realizan llamadas HTTP hacia los webhooks de los
clientes.

Estas llamadas son operaciones principalmente I/O-bound y pueden
permanecer esperando durante varios segundos debido a latencia,
timeouts o problemas en el endpoint remoto.

El sistema debe poder procesar múltiples notifications
concurrentemente, pero también debe evitar que un cliente lento o con
problemas monopolice los recursos disponibles.

Además, la concurrencia HTTP no debe estar limitada directamente por el
número de conexiones disponibles en PostgreSQL.

## Decisión

Los Delivery Workers utilizarán Java Virtual Threads.

Se utilizará un modelo de ejecución donde cada notification reclamada
pueda ser procesada mediante un Virtual Thread.

Los Virtual Threads se utilizarán como mecanismo eficiente de ejecución
de tareas bloqueantes de I/O, pero no serán utilizados como mecanismo
de control de concurrencia.

## Control de concurrencia

Se utilizarán dos límites independientes:

### Límite global

Define la cantidad máxima de llamadas HTTP que pueden estar en vuelo
simultáneamente en toda la instancia del servicio.

Su objetivo es proteger los recursos propios del servicio, como:

- memoria;
- conexiones HTTP;
- capacidad de red;
- CPU asociada al procesamiento de las respuestas.

### Límite por cliente

Define la cantidad máxima de llamadas HTTP simultáneas hacia un cliente
determinado.

Su objetivo es evitar que un cliente con un webhook lento o caído
monopolice la capacidad global disponible.

Este mecanismo implementa el patrón **Bulkhead**, aislando parcialmente
los recursos utilizados por diferentes clientes.

La combinación de ambos límites permite que un cliente tenga una
concurrencia máxima propia sin poder consumir toda la capacidad global
del servicio.

## Ejemplo

Si el servicio permite hasta 100 llamadas HTTP simultáneas y un cliente
tiene un límite de 5:

- ese cliente podrá tener como máximo 5 llamadas en vuelo
- las demás notifications de ese cliente permanecerán pendientes
- otros clientes podrán utilizar el resto de la capacidad global.

De esta forma, un cliente con problemas no bloquea el procesamiento de
los demás clientes.

## PostgreSQL y llamadas HTTP

Una llamada HTTP externa no deberá ejecutarse dentro de una transacción
de PostgreSQL.

El procesamiento seguirá tres etapas independientes:

1. Reclamar la notification mediante una transacción corta.
2. Realizar la llamada HTTP sin mantener una conexión de PostgreSQL.
3. Persistir el resultado mediante otra transacción corta.

Conceptualmente:

    PostgreSQL
        |
        | claim
        v
    Notification PROCESSING
        |
        | conexión DB liberada
        v
    HTTP Webhook
        |
        | respuesta
        v
    PostgreSQL
        |
        | persistir resultado
        v
    Notification actualizada
    + DeliveryAttempt

Esto evita que la latencia del webhook determine directamente la
cantidad de conexiones de PostgreSQL ocupadas.

## Connection pools

El pool de conexiones de PostgreSQL se dimensionará de acuerdo con la
duración y frecuencia de las operaciones de base de datos.

No se utilizará el tamaño del pool de PostgreSQL como mecanismo para
controlar la concurrencia HTTP.

De igual forma, la concurrencia HTTP se controlará explícitamente
mediante los límites definidos anteriormente y no mediante la cantidad
de Virtual Threads.

## Rate limiting

No se implementará inicialmente un rate limiter como mecanismo de
control de las llamadas HTTP.

El rate limiting controla una cantidad de operaciones durante un
período de tiempo, mientras que el mecanismo definido en esta decisión
controla la cantidad de operaciones simultáneas.

Si posteriormente existe un requisito contractual que limite la
cantidad de requests por segundo hacia un cliente, podrá incorporarse
como mecanismo adicional.

## Circuit Breaker

Se considera apropiado utilizar un Circuit Breaker por cliente o
webhook en una implementación de producción.

Su objetivo sería evitar continuar realizando llamadas HTTP hacia un
destino que presenta evidencia reciente de fallos sistemáticos.

El Circuit Breaker tendría estados independientes del estado de cada
`Notification`.

Un circuito abierto no cambiaría una notification directamente a
`FAILED`. Las notifications continuarían pendientes y serían
reprogramadas mediante `next_attempt_at`.

La implementación del Circuit Breaker queda inicialmente fuera del
camino crítico del challenge.

La decisión podrá revisarse después de implementar y validar el flujo
principal de delivery.

## Alternativas consideradas

### Threads de plataforma

Se consideraron los threads tradicionales de plataforma.

Fueron descartados como mecanismo principal porque cada operación
bloqueante de I/O mantiene ocupado un thread de plataforma durante la
espera, haciendo menos eficiente manejar grandes cantidades de
operaciones concurrentes.

### Programación reactiva

Podría utilizarse un modelo basado en programación reactiva para
manejar muchas operaciones I/O concurrentes.

No se considera necesario para este challenge porque aumentaría la
complejidad del código sin una necesidad evidente que justifique ese
costo.

### Pool fijo de workers

Un pool fijo de threads de plataforma podría limitar directamente la
cantidad de llamadas concurrentes.

Se descartó como mecanismo principal porque mezcla el costo del modelo
de ejecución con la política de concurrencia.

Con Virtual Threads, la política de concurrencia se puede expresar
explícitamente mediante límites independientes.

### Límite global solamente

Se descartó porque un único cliente lento podría consumir una parte
desproporcionada de la capacidad global y afectar a otros clientes.

### Límite por cliente solamente

Se descartó como único mecanismo porque muchos clientes funcionando
simultáneamente podrían superar la capacidad total que el servicio puede
soportar.

Por ello se utilizará una combinación de límite global y límite por
cliente.

## Consecuencias

### Positivas

- Manejo eficiente de operaciones HTTP bloqueantes.
- Separación entre ejecución y control de concurrencia.
- Protección frente a clientes lentos o con problemas.
- Aislamiento parcial entre clientes.
- Mayor aprovechamiento de la capacidad disponible.
- PostgreSQL no queda ligado a la duración de las llamadas HTTP.
- El comportamiento de concurrencia es explícito y configurable.

### Negativas

- Se deben gestionar correctamente los límites de concurrencia.
- Será necesario definir y controlar el tamaño del pool de conexiones
  HTTP.
- Los límites por cliente requieren mantener información asociada a
  cada cliente.
- Virtual Threads no eliminan los límites físicos de red, conexiones o
  recursos del sistema.
- Circuit Breaker, si se implementa posteriormente, añade complejidad
  adicional.

## Decisiones pendientes

- Valores concretos del límite global.
- Valores concretos del límite por cliente.
- Estrategia exacta para almacenar y administrar los semáforos por
  cliente.
- Configuración del cliente HTTP y su connection pool.
- Timeouts de conexión y lectura.
- Si el límite deberá aplicarse por `client_id`, webhook o host.
- Implementación definitiva del Circuit Breaker.
- Métricas y alertas asociadas a saturación de los límites.