# ADR-002: PostgreSQL como fuente de verdad del estado de las notificaciones

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

El servicio debe almacenar la información relacionada con la entrega de
notificaciones.

Además, debe proporcionar una REST API que permita consultar las
notificaciones por cliente y filtrarlas por fecha de creación del
evento y delivery_status.

La API también debe permitir consultar el detalle de una notificación
y solicitar el replay de una notificación cuya entrega haya fallado
definitivamente.

Estos requisitos implican la necesidad de mantener un estado persistente
y consultable de las notificaciones.

## Decisión

Se utilizará PostgreSQL como almacenamiento principal para el estado de
las notificaciones y la información relacionada con sus deliveries.

PostgreSQL será considerado la fuente de verdad para el estado de
delivery de una notificación.

Kafka será utilizado como mecanismo de ingestión de eventos y no como
fuente de verdad para el estado de las notificaciones.

## Razones

PostgreSQL proporciona:

- persistencia durable
- transacciones
- constraints para garantizar integridad
- índices para soportar filtros
- consultas por múltiples criterios
- mecanismos de control de concurrencia
- capacidad para almacenar el estado de las notificaciones
- capacidad para almacenar información relacionada con los intentos
  de delivery.

El modelo relacional también se adapta bien a las necesidades de la
self-service API.

## Alternativas consideradas

### Base de datos NoSQL

Una base de datos NoSQL podría proporcionar escalabilidad horizontal,
pero el caso requiere consultas combinando diferentes criterios,
incluyendo cliente, fecha y estado.

PostgreSQL proporciona un modelo de consulta más adecuado para estas
necesidades y además permite utilizar transacciones y constraints para
proteger la consistencia.

### Kafka como almacenamiento del estado

Kafka mantiene los eventos y permite replay del stream, pero no es el
almacenamiento adecuado para representar el estado actual de una
notificación ni para servir directamente las consultas de la
self-service API.

Por este motivo, Kafka y PostgreSQL tendrán responsabilidades
diferentes.

## Consecuencias

### Positivas

- Estado de las notificaciones centralizado y consultable.
- Consultas eficientes mediante índices.
- Soporte para transacciones y constraints.
- Posibilidad de controlar concurrencia desde la base de datos.
- Facilita la implementación de replay y retries basados en el estado
  persistido.

### Negativas

- Introduce una dependencia de infraestructura adicional.
- El diseño deberá considerar correctamente la concurrencia entre
  múltiples instancias.
- La persistencia del evento recibido desde Kafka y el commit del
  offset deben coordinarse cuidadosamente.

## Consideraciones

PostgreSQL será responsable del estado de las notificaciones, pero no
se ha decidido todavía cómo se realizará exactamente el procesamiento
de deliveries ni cómo se programarán los retries.

La estrategia de concurrencia, locking, scheduling y procesamiento
asíncrono será definida en decisiones posteriores.