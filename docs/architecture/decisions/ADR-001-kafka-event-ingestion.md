# ADR-001: Kafka como mecanismo de ingestión de eventos

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

La plataforma de Cobre se describe como una plataforma transaccional,
cloud-native, event-driven y basada en microservicios.

El servicio de notificaciones debe recibir eventos generados por la
plataforma y determinar si deben ser entregados a un cliente mediante
un webhook.

El challenge requiere que la solución sea escalable y resiliente.

Durante el análisis se consideraron diferentes alternativas para la
ingestión de eventos, principalmente Kafka y una queue tradicional.

También se consideró utilizar únicamente el archivo
`notification_events.json` proporcionado por el challenge. Sin embargo,
este archivo corresponde al input proporcionado para la implementación
de Task 2 y no representa necesariamente el mecanismo de ingestión que
tendría la solución en producción.

## Decisión

Se utilizará Kafka como mecanismo de ingestión de eventos en la
arquitectura propuesta y también se implementará un adapter de Kafka
en el proyecto.

Kafka estará desacoplado del núcleo de la aplicación mediante
arquitectura hexagonal.

El core de la aplicación no dependerá directamente de las APIs de Kafka.

## Razones

Kafka permite:

- desacoplar la generación de eventos del procesamiento de
  notificaciones
- absorber bursts de eventos
- distribuir el procesamiento mediante consumer groups
- recuperar el procesamiento después de una caída utilizando los offsets
- escalar horizontalmente los consumidores
- mantener los eventos durante un período de retención definido.

La elección no se basa únicamente en que Kafka tenga mayor throughput
que una queue tradicional. La decisión está principalmente relacionada
con el contexto event-driven de la plataforma descrita en el challenge,
la necesidad de procesamiento asíncrono y la posibilidad de escalar y
recuperarse ante interrupciones.

## Alternativas consideradas

### Queue tradicional

Una queue tradicional también podría proporcionar procesamiento
asíncrono, redelivery y control de concurrencia.

Se descartó como opción principal porque Kafka se adapta mejor al
contexto event-driven de la plataforma y proporciona capacidades de
retención y replay del stream que pueden ser útiles para una plataforma
de eventos.

### JSON como fuente de eventos

El challenge proporciona `notification_events.json` como input para
Task 2.

El archivo se utilizará como un adapter adicional de entrada para
facilitar la implementación y las pruebas, pero no se considera el
mecanismo de ingestión de eventos de la arquitectura de producción.

## Consecuencias

### Positivas

- Permite desacoplar productores y consumidores.
- Facilita el escalamiento horizontal.
- Permite absorber bursts de eventos.
- Facilita la recuperación después de downtime.
- Mantiene abierta la posibilidad de replay a nivel de stream.

### Negativas

- Introduce infraestructura adicional.
- Aumenta la complejidad operacional.
- Requiere decisiones sobre particionamiento, offsets y consumer groups.
- El manejo de retries con delays requiere una estrategia adicional.
- Para desarrollo local será necesario ejecutar Kafka como infraestructura
  adicional.

## Consideraciones

Kafka no garantiza por sí mismo que una notificación sea entregada
exactamente una vez al webhook externo.

El diseño deberá utilizar una estrategia de idempotencia y manejo de
duplicados para soportar escenarios de redelivery y fallos durante el
procesamiento.

La estrategia concreta de particionamiento, retries y procesamiento de
deliveries será definida en ADRs posteriores.