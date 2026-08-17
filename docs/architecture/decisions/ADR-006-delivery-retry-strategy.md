# ADR-006: Modelo de delivery y estrategia de retry

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

Una notification puede requerir múltiples intentos para ser entregada
correctamente al webhook del cliente.

El sistema debe conservar el estado actual de la notification, permitir
retries, proporcionar información suficiente para observabilidad y
permitir el replay de notifications que hayan fallado definitivamente.

Se identificó que el estado actual de una notification y el historial
de sus intentos tienen responsabilidades diferentes.

## Decisión

Se utilizarán dos entidades principales:

- `Notification`
- `DeliveryAttempt`

### Notification

Representará el estado actual y la información necesaria para decidir
cuándo y cómo continuar el procesamiento.

Contendrá conceptualmente información como:

- estado;
- número de intentos;
- fecha del último intento;
- fecha del próximo intento;
- razón del fallo definitivo;
- información necesaria para identificar el evento y la suscripción.

### DeliveryAttempt

Representará un intento individual de entrega.

Los attempts serán append-only y no se modificarán después de haber sido
registrados.

Cada attempt podrá contener información como:

- número de intento;
- timestamp;
- duración;
- resultado;
- código HTTP;
- tipo de error;
- información limitada de la respuesta;
- tipo de trigger;
- información del solicitante cuando el intento provenga de un replay.

## Máquina de estados

La notification utilizará cuatro estados principales:

- `PENDING`
- `PROCESSING`
- `COMPLETED`
- `FAILED`

Las transiciones principales serán:

    PENDING -> PROCESSING -> COMPLETED
                        L -> PENDING
                        L -> FAILED

Una notification en `PENDING` puede representar tanto una notification
que aún no ha sido procesada como una que está esperando un retry.

La diferencia puede determinarse mediante `attempt_count` y
`next_attempt_at`.

Un fallo individual no implica necesariamente `FAILED`.

`FAILED` representa exclusivamente un estado terminal en el que no se
realizarán más retries automáticos.

## Estrategia de retry

Los errores considerados transitorios mantendrán la notification en
`PENDING` y establecerán un nuevo `next_attempt_at`.

El delay utilizará exponential backoff con un límite máximo y full
jitter.

El jitter será aplicado antes de calcular el próximo intento.

Cuando el cliente proporcione `Retry-After`, este valor tendrá
prioridad sobre el delay calculado cuando corresponda.

## Clasificación de errores

### Errores de red

Timeouts, errores de conexión y errores de DNS serán considerados
retryable dentro de la política general de retries.

### HTTP 4xx

Los errores `4xx` serán considerados no retryable por defecto.

`429` constituye una excepción, ya que representa una condición de
rate limiting y puede indicar explícitamente cuándo volver a intentar.

`409` se tratará como no retryable por defecto y su semántica deberá
definirse en el contrato del webhook.

### HTTP 5xx

Las respuestas `5xx` serán consideradas retryable por defecto.

`503` podrá utilizar `Retry-After` cuando el cliente lo proporcione.

## Fallo definitivo

Una notification se considerará definitivamente fallida cuando se
cumpla alguna de las condiciones que finalice la política de retry:

- se alcance el máximo de intentos
- se alcance la ventana máxima de retry
- se produzca un error clasificado como permanente.

La política utilizará simultáneamente un límite de intentos y una ventana
máxima de tiempo.

Los valores concretos de estos parámetros serán configurables y se
definirán posteriormente.

## Replay

El endpoint:

`POST /notification_events/{notification_event_id}/replay`

solo podrá ejecutarse sobre notifications en estado `FAILED`.

El replay reutilizará la notification existente y realizará la
transición:

`FAILED → PENDING`

El historial de `DeliveryAttempt` se conservará.

El `attempt_count` no se reiniciará, evitando que múltiples requests de
replay puedan utilizarse para evadir indefinidamente el límite de
intentos.

Las solicitudes concurrentes de replay serán protegidas mediante una
transición condicional de estado, de manera que solo una pueda cambiar
una notification desde `FAILED` hacia `PENDING`.

## Observabilidad

La separación entre `Notification` y `DeliveryAttempt` permitirá
responder preguntas como:

- ¿Cuál es el estado actual?
- ¿Cuántos intentos se realizaron?
- ¿Cuándo ocurrió el último intento?
- ¿Cuándo está programado el próximo?
- ¿Qué código HTTP respondió el cliente?
- ¿Cuál fue el error?
- ¿Por qué dejó de reintentarse?
- ¿El intento fue automático o resultado de un replay?
- ¿Quién solicitó un replay?

La razón del fallo definitivo se mantendrá separada de la información
del último intento.

## Alternativas consideradas

### Una única entidad para notification e intentos

Se descartó porque dificultaría conservar un historial completo de
intentos y mezclaría el estado actual con información histórica.

### Estado adicional `RETRYING`

No se considera necesario.

Desde el punto de vista del procesamiento, una notification nueva y una
notification esperando un retry son ambas trabajo pendiente que puede
ser seleccionado cuando `next_attempt_at` sea alcanzado.

### Reiniciar `attempt_count` durante replay

Se descartó inicialmente para evitar que el replay pueda utilizarse para
evadir indefinidamente la política de retries.

El comportamiento podrá revisarse si posteriormente existe un requisito
de negocio que justifique otorgar nuevos intentos.


## Impacto de no persistir el origen del replay

Se decidió no introducir, dentro del alcance actual, un campo adicional
como `lastReplayRequestedBy` para transportar información del request de
replay hasta el momento en que el Worker procese nuevamente la
Notification.

Esta decisión reduce la complejidad del modelo y evita introducir una
entidad o mecanismo adicional de correlación únicamente para soportar
información de auditoría que no es necesaria para el flujo principal del
challenge.

Sin embargo, la decisión tiene una consecuencia arquitectónica
importante: mientras una Notification se encuentra en PENDING, el modelo
actual no conserva explícitamente una señal que permita distinguir si
dicha Notification está pendiente por un retry automático o porque fue
reactivada mediante MANUAL_REPLAY.

Esto limita temporalmente la capacidad de implementar una priorización
basada en el origen del trabajo, por ejemplo:

    AUTOMATIC > MANUAL_REPLAY

Dicha priorización sería útil para garantizar que un volumen elevado de
requests al endpoint de replay no desplace el procesamiento normal de
eventos recibidos desde Kafka.

También limita la posibilidad de crear un bulkhead de capacidad
específico para trabajo originado por replay, separado del flujo
automático.

Esta limitación se acepta para el alcance actual porque el servicio ya
cuenta con otras capas de protección:

- rate limiting por cliente sobre el endpoint de replay;
- transición atómica FAILED → PENDING;
- bulkhead global de entrega;
- bulkhead por cliente;
- límites de concurrencia para llamadas HTTP;
- retry y backoff.

Como consecuencia, el sistema permanece protegido contra abuso básico
del endpoint y contra sobre-concurrencia durante la entrega, aunque no
implementa todavía una separación explícita de prioridad entre
AUTOMATIC y MANUAL_REPLAY dentro de la cola de trabajo.

### Evolución futura

Si el sistema requiriera priorización o aislamiento fuerte entre ambos
tipos de trabajo, se podría introducir un atributo de origen asociado a
la Notification pendiente, por ejemplo:

    AUTOMATIC_RETRY
    MANUAL_REPLAY

Esto permitiría:

- priorizar AUTOMATIC sobre MANUAL_REPLAY durante el claim;
- reservar capacidad para eventos provenientes de Kafka;
- establecer límites independientes para trabajo manual;
- aplicar métricas separadas de backlog;
- detectar clientes que generan cantidades anormales de replays.

La información `requestedBy` podría agregarse posteriormente como una
preocupación independiente de auditoría, sin confundirla con el
concepto de origen del trabajo.

## Consecuencias

### Positivas

- Historial completo de deliveries.
- Estado actual separado de información histórica.
- Retries controlados y auditables.
- Backoff con jitter para evitar retry storms.
- Replay controlado.
- Buena base para observabilidad.
- Permite diferenciar errores transitorios y permanentes.
- Mantiene el modelo de dominio simple dentro del alcance del challenge.

### Negativas

- Requiere mantener dos entidades relacionadas.
- Cada intento implica persistencia adicional.
- La política de retry requiere configuración y monitoreo.
- La clasificación de algunos errores puede depender del contrato con
  el cliente.
- El modelo actual no permite distinguir de forma persistente entre
  trabajo automático y trabajo originado por replay mientras una
  notification permanece en `PENDING`.
- Esto limita temporalmente la priorización y el aislamiento específico
  entre ambos flujos.

## Decisiones pendientes

- Valores concretos de `base_delay`, `max_delay`, `max_attempts` y
  `max_retry_window`.
- Política de retención de `DeliveryAttempt`.
- Configuración de retry por cliente o suscripción.
- Contrato definitivo de `Retry-After`.
- Política detallada de circuit breaker.
- Límites de concurrencia por cliente.
- Mecanismo futuro para persistir el origen de trabajo pendiente si se
  requiere priorización o aislamiento entre `AUTOMATIC` y
  `MANUAL_REPLAY`.
- Política futura para persistir la identidad del solicitante de un
  replay.