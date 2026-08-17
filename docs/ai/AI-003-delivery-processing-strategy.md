# AI-003: Análisis de la estrategia de procesamiento de deliveries

## Herramienta

Claude Code

## Propósito

Analizar cómo debería procesarse una notificación después de ser
recibida mediante Kafka y persistida en PostgreSQL.

El objetivo fue comparar diferentes alternativas para separar la
ingestión de eventos del procesamiento de deliveries y entender sus
implicaciones en escalabilidad, resiliencia, concurrencia, retries y
manejo de bursts de eventos.

## Prompt

quiero profundizar ahora en el flujo de procesamiento despues de la ingestion de un evento.
hasta ahora decidí utilizar kafka como mecanismo de ingestion y postgresql como fuente de verdad para el estadod e las notificaciones.
todavia no he decidido como debería ser el flujo entre kafka, postgresql y el webhook.
quiero analizar las alternativas antes de tomar esa decisión.
las alternativas que estoy considerando son estas:
1) kafka -> consumer -> webhook directamente.
2) kafka -> consumer -> postgresql -> workers -> webhook.
3) kafka -> consumer -> postgresql -> scheduler/workers -> wenhook.
4) kafka -> consumer -> postgresql -> queue -> workers -> webhook.

quiero que compares estas alternativas considerando:

-escalabilidad
-resiliencia
-recuperación despues de una caida.
-manejo de burts de eventos
-persistencia del estado
-retries
-backpressure
-concurrencia
-idempotencia
-replay
-observabilidad
-complejidad operacional
-complejidad de implementación
-cosistencia entre kafka y postgresq

quiero que consideres este escenario:
si llegan 300 eventos practicamente al tiempo y una gran parte pertenece al mismo cliente ¿Cómo se comportaría cada alternativa?
no quiero que simplemente asumas que necesitamos proneamente. quiero analizar cómo separar el throughput de ingestión de la concurrencia

## Principales conclusiones

- Las alternativas de procesamiento pueden simplificarse a dos
  enfoques principales: procesar el webhook directamente desde el
  consumer de Kafka o separar la ingestión del delivery mediante
  PostgreSQL y workers.
- Un scheduler independiente no parece necesario. Los propios workers
  pueden seleccionar las notificaciones cuyo `next_attempt_at` indique
  que ya pueden ser procesadas.
- Separar la ingestión del delivery permite que Kafka absorba bursts sin
  obligar al sistema a realizar inmediatamente la misma cantidad de
  llamadas HTTP hacia los clientes.
- PostgreSQL puede funcionar como checkpoint durable y fuente de verdad
  para el estado de las notificaciones.
- Una segunda tecnología de mensajería no parece necesaria para el
  alcance actual del challenge.
- El control de la concurrencia de los webhooks debe ser independiente
  de la velocidad de ingestión.
- El escenario más difícil de garantizar ocurre cuando el webhook se
  envía correctamente pero el servicio falla antes de persistir el
  resultado, ya que puede producirse una nueva entrega del mismo evento.
- La idempotencia del delivery debe considerarse a nivel del contrato
  con el cliente.

## Impacto en mi análisis

A partir de este análisis decidí separar explícitamente la ingestión de
eventos del procesamiento de deliveries.

Kafka será responsable de recibir y distribuir los eventos, mientras que
PostgreSQL mantendrá el estado de las notificaciones y permitirá que
los workers procesen los deliveries de acuerdo con la capacidad
disponible.

También decidí no introducir una segunda tecnología de mensajería por
ahora, ya que añadir otra queue aumentaría la complejidad y crearía un
nuevo punto de coordinación entre sistemas.

Este análisis también hizo evidente que el manejo de concurrencia y el
control de la cantidad de deliveries simultáneos hacia un mismo cliente
deben tratarse como decisiones independientes de la ingestión.

Quedaron pendientes de definir la estrategia concreta para reclamar
notificaciones entre múltiples workers, la idempotencia, los retries,
el particionamiento de Kafka, el límite de concurrencia por cliente y
el comportamiento ante fallos durante un delivery.

## Decisión resultante

Se adoptará el siguiente flujo general:

Kafka → Kafka Consumer → PostgreSQL → Delivery Workers → Webhook

El consumer realizará una operación de persistencia rápida e idempotente
y confirmará el mensaje de Kafka después de completar correctamente la
persistencia.

Los workers serán responsables de procesar posteriormente las
notificaciones pendientes o cuyo próximo intento ya esté disponible.

La estrategia concreta de concurrencia, retries e idempotencia será
analizada en decisiones posteriores.

## Evidencia

- [Prompt utilizado](screenshots/AI-003-prompt.png)
- [Respuesta obtenida](screenshots/AI-003-response.png)