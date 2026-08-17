# ADR-003: Desacoplar las fuentes de eventos del núcleo de la aplicación

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

El challenge requiere implementar la solución utilizando arquitectura
hexagonal.

Para Task 2 se proporciona un archivo `notification_events.json` que
contiene los eventos que deben utilizarse durante la implementación.

Al mismo tiempo, la arquitectura de producción propuesta utilizará
Kafka como mecanismo de ingestión de eventos.

Esto genera dos fuentes de entrada diferentes:

- Kafka para el escenario de producción.
- JSON para la implementación y pruebas del challenge.

Acoplar directamente la lógica de negocio a cualquiera de estas fuentes
haría más difícil cambiar el mecanismo de ingestión y aumentaría la
dependencia del core respecto de la infraestructura.

## Decisión

Las fuentes de eventos se desacoplarán del núcleo de la aplicación
mediante un puerto de entrada definido por la arquitectura hexagonal.

Se implementarán adapters independientes para las diferentes fuentes
de eventos.

Inicialmente se contemplan:

- Kafka adapter.
- JSON/file adapter.

El núcleo de la aplicación recibirá eventos mediante el puerto definido
y no tendrá conocimiento de si el evento proviene de Kafka, del archivo
JSON o de otra fuente.

Conceptualmente:

    Kafka Adapter ─> Event Input Port -> Application Core <- Event Input Port <-JSON Adapter 

## Razones

Esta decisión permite:

- mantener el core independiente de la infraestructura
- cumplir con la arquitectura hexagonal requerida por el challenge
- implementar Kafka sin acoplar la lógica de negocio al broker
- utilizar el JSON proporcionado por el challenge
- facilitar pruebas sin depender obligatoriamente de Kafka
- permitir reemplazar o agregar fuentes de eventos en el futuro.

## Alternativas consideradas

### Acoplar directamente el core a Kafka

Se descartó porque introduciría una dependencia directa de la lógica
de aplicación hacia una tecnología específica de infraestructura.

### Utilizar únicamente el JSON

Permitiría simplificar la implementación del challenge, pero no
representaría la estrategia de ingestión definida para la solución
de producción.

### Utilizar únicamente Kafka

Representaría mejor el escenario de producción, pero impediría
aprovechar directamente el input proporcionado por el challenge y
aumentaría la dependencia de infraestructura durante las pruebas.

## Consecuencias

### Positivas

- Bajo acoplamiento.
- Mayor testabilidad.
- Separación clara entre dominio/aplicación e infraestructura.
- Permite utilizar diferentes mecanismos de entrada.
- Facilita demostrar la arquitectura durante la presentación.

### Negativas

- Introduce abstracciones adicionales.
- Requiere implementar más de un adapter.
- Debemos asegurarnos de que los adapters traduzcan correctamente
  los mensajes externos al modelo utilizado por la aplicación.

## Consideraciones

Este ADR define únicamente el desacoplamiento de las fuentes de eventos.

Todavía deben definirse:

- el modelo de dominio
- la estrategia de procesamiento después de la ingestión
- la estrategia de idempotencia
- el modelo de concurrencia
- la estrategia de retry
- la estrategia de replay.