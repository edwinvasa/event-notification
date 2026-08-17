# AI-005: Análisis del modelo de delivery y estrategia de retry

## Herramienta

Claude Code

## Propósito

Analizar el modelo de dominio relacionado con las notificaciones y sus
intentos de entrega, definir una máquina de estados adecuada y evaluar
la estrategia de retries.

El objetivo fue determinar cómo representar el estado actual de una
notification, cómo conservar el historial de intentos y cómo manejar
errores temporales y permanentes, incluyendo el mecanismo de replay
solicitado por el cliente.

## Prompt

ahora quiero analizar el modelo de estados de una notification y la estrategia de delivery attempts y retries.
hasta ahora tengo definido este flujo general:
kafka -> kafka consumer -> postgresql -> delivery workers -> webhook

también decidí:
- kafka como mecanismo de ingestión
- postgresql como fuente de verdad
- separar la ingestion del delivery
- utilizar una maquina de estados para las notifications
- utilizar un claim atómico para que los worers reclamen trabajo
- utilizar un lease para recuperar notifications cuyo worker falle
- utilizar idempotencia para evitar duplicados en diferentes etapas

ahora quiero definir con más precisión que ocurre desde que una notification entra en estado pending hasta que termina en completed o failed definitivamente.

quiero que analices primero el modelo de dominio y me digas si conviene separar:
- Notification
- DeliveryAttempt

mi idea inicial es que una Notification represente el estado agregado de la entrega para un cliente y un evento, mientras que cada DeliveryAttempt represente un intento HTTP individual.
quiero que cuestiones esta idea y expliques que ventajas y desventajas tendría.
para los DeliveryAttempt quiero analizar que información debería persistirse, por ejemplo:

- attempt number
- timestamp
- request/response information
- HTTP status
- duration
- error
- resultado del intento

no quiero definir todavia todos los campos de la base de datos, mas bien entender que información necesitamos para soportar retries, observabilidad, auditoria y replay.
tambén quiero definir una maquina de estados adecuada, estoy considerando algo como esto:
pending
processing
completed
failed

pero no estoy seguro de si necesitamos estados adicionales o si pending debería representar también una notification esperando un retry.
quiero que analices diferentes opciones y me recomiendes una maquina de estados clara y fácil de defender.

después quiero analizar la estrategia de retry. estoy pensando en:
- exponential backoff
- jitter
- maximo de intentos
- next_attempt_at

quiero que analices cómo debería comportarse el sistema ante diferentes respuestas al momento de realizar el request como por ejemplo:
- timeouts
- connection refused
- DNS failure
- HHTPs como: 400, 401, 403, 404, 409, 429, 500, 502, 503, 504

no quiero que simplemente digas "4xx no retry y 5xx retry", quiero que expliques si existen excepciones, especialmente 429 y cómo podría determinarse si un error es transitorio o permanente.
también quiero analizar que significa exactamente "failed definitivamente" para este challenge.
compara diferentes criterios como:
- número máximo de intentos
- tiempo máximo de reintento
- combinaciones de ambos
- política configurable por cliente o suscripción

quiero entender los trade-offs y finalmente elegir una estrategia razonable para este challenge.
tambien quiero analizar el endpoint:
POST /notification_events/{notification_event_id}/replay
quiero saber cómo debería interactuar con la maquina de estados. por ejemplo:
- ¿solo debería permitir replay cuando el estado sea failed?
- debería crear una nueva notification o reutilizar la existente?
- debería reiniciar attempt_account?
- debería conservar el historial anterior de attemps?
- ¿que ocurre si 2 requests de replay llegan simultaneamente?
- ¿que ocurre si llega un replay mientras otro worker está procesando la notification?

quiero que priorices observabilidad.
el challenge requiere almacenar información final de la entrega y proporcionar mecanismos para monitorear el estado de las notificaciones.
quiero que me digas que información debería quedar disponible para responder preguntas como:
- ¿porque esta notificación falló?
- ¿cuantas veces intentamos enviarla?
- ¿cuando fue el ultimo intento?
- ¿que respondió el webhook?
- ¿cuando se volverá a intentar?
- ¿quien solicitó el replay?

por ahora no quiero código ni que modifiques el proyecto.
tampoco quiero analizar todavía virtual threads, circuit breaker, bulkhead ni el particionamiento de kafka.
quiero concentrarme exclusivamente en el modelo de estados, DeliveryAttempt, retries, backoff, errores y replay.

al final dame una recomendación concreta para este challenge y señala que decisiones tomarías y cuales dejarías configurables para el futuro.

## Principales conclusiones

- Se decidió separar `Notification` de `DeliveryAttempt`.
- `Notification` representa el estado agregado y mutable de una entrega.
- `DeliveryAttempt` representa un intento HTTP individual y debe ser
  append-only.
- `attempt_count`, `last_attempted_at` y `next_attempt_at` pertenecen a
  `Notification`.
- La máquina de estados propuesta utiliza `PENDING`, `PROCESSING`,
  `COMPLETED` y `FAILED`.
- `PENDING` representa tanto una notification que aún no ha sido
  procesada como una notification esperando un nuevo retry.
- `FAILED` representa únicamente un fallo definitivo, no un fallo
  individual.
- Los errores transitorios mantienen la notification en `PENDING` y
  actualizan `next_attempt_at`.
- Los retries utilizarán exponential backoff con un límite máximo y
  full jitter.
- `Retry-After` tendrá prioridad cuando sea proporcionado por el
  cliente, especialmente para respuestas `429` y `503`.
- Los errores de red serán considerados retryable dentro de la política
  general de retries.
- Los errores permanentes provocarán el paso a `FAILED`.
- El fallo definitivo se determinará utilizando tanto un máximo de
  intentos como una ventana máxima de tiempo.
- El endpoint de replay solo será válido para notifications en estado
  `FAILED`.
- El replay reutilizará la misma notification y conservará el historial
  completo de `DeliveryAttempt`.
- Los requests de replay concurrentes se controlarán mediante una
  transición condicional de estado.
- La información de auditoría y observabilidad debe permitir distinguir
  entre el resultado de un intento y la razón por la que la notification
  dejó de reintentarse.

## Impacto en mi análisis

Este análisis permitió definir con mayor precisión el modelo de dominio
del delivery.

La separación entre `Notification` y `DeliveryAttempt` permite mantener
en la notification únicamente el estado necesario para tomar decisiones
de procesamiento, mientras que los intentos individuales conservan el
historial necesario para auditoría y observabilidad.

También decidí que un fallo individual no debe convertirse
automáticamente en un estado terminal. Mientras existan retries
disponibles, la notification permanecerá en `PENDING` y tendrá un
`next_attempt_at` que determine cuándo puede volver a ser procesada.

Además, decidí utilizar una combinación de máximo de intentos y ventana
máxima de retry para determinar cuándo una notification debe considerarse
definitivamente fallida.

El análisis del endpoint de replay también permitió establecer que el
replay reutilizará la notification existente y no eliminará ni
reiniciará su historial de intentos.

Los valores numéricos de la política de retry quedan pendientes de
configuración y no se consideran todavía decisiones arquitectónicas.

## Decisión resultante

Se utilizará un modelo compuesto por:

- `Notification`: estado actual y metadatos de scheduling.
- `DeliveryAttempt`: historial append-only de cada intento de delivery.

La máquina de estados será:

`PENDING → PROCESSING → COMPLETED`

o:

`PROCESSING → PENDING → PROCESSING`

mientras existan retries disponibles.

Cuando no sea posible continuar:

`PROCESSING → FAILED`

El replay permitirá:

`FAILED → PENDING`

La estrategia de retry utilizará exponential backoff, cap y full jitter,
respetando `Retry-After` cuando corresponda.

## Evidencia

- [Prompt utilizado](screenshots/AI-005-prompt.png)
- [Respuesta obtenida](screenshots/AI-005-response.png)