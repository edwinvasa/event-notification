# ADR-008: Seguridad y protección contra repetición

- **Estado:** Accepted
- **Fecha:** 2026-08-16

## Contexto

El servicio expone una API que permite a los clientes consultar sus
notificaciones y solicitar el replay de notificaciones que hayan fallado
definitivamente.

Además, el servicio realiza requests HTTP hacia URLs de webhook configuradas
por los clientes.

Esto introduce varios riesgos que deben considerarse desde el diseño:

- Un cliente podría intentar acceder a notificaciones pertenecientes a otro
  cliente.
- Un cliente podría generar una cantidad excesiva de requests de replay.
- Una URL de webhook podría apuntar hacia infraestructura interna o privada.
- Una entrega podría necesitar ser repetida sin que el receptor procese el
  mismo evento más de una vez.
- Los eventos pueden contener información sensible relacionada con
  transacciones.
- El tráfico generado por replay no debe afectar el procesamiento normal de
  nuevos eventos.

## Decisión

### 1. Autenticación y autorización

Para la implementación del challenge se utilizará una API Key por cliente.

El `client_id` utilizado para autorizar una operación será obtenido
server-side a partir de la identidad autenticada.

No se confiará en un `client_id` enviado directamente por el cliente mediante:

- path parameters
- query parameters
- headers controlados por el cliente

Los endpoints que consulten o modifiquen una notificación deberán verificar
que dicha notificación pertenece al cliente autenticado.

Cuando un cliente intente acceder a una notificación que pertenece a otro
cliente, se devolverá `404 Not Found` para evitar revelar la existencia del
recurso.

### 2. Protección del endpoint de replay

El endpoint:

`POST /notification_events/{notification_event_id}/replay`

tendrá rate limiting por cliente.

El rate limiting protege la API frente a un volumen excesivo de requests,
pero no será utilizado como mecanismo de control de concurrencia de las
entregas.

La transición de una notificación desde `FAILED` hacia `PENDING` será
atómica:

`FAILED -> PENDING`

mediante una actualización condicional.

Si dos requests de replay intentan modificar simultáneamente la misma
notificación, solamente una podrá realizar la transición.

La otra request será rechazada porque la notificación ya no estará en estado
`FAILED`.

### 3. Aislamiento entre replay y eventos normales

El replay genera trabajo que entra al mismo pipeline de entrega utilizado
por las notificaciones normales.

Según ADR-006 ("Impacto de no persistir el origen del replay"), el modelo
actual de `Notification` no persiste una señal que distinga, mientras una
notification está `PENDING`, si quedó pendiente por un retry automático o
porque fue reactivada mediante replay. Por lo tanto, el mecanismo de claim
de los workers **no** prioriza el trabajo proveniente del flujo normal
sobre el trabajo generado por replay: ambos compiten por el mismo turno de
claim en igualdad de condiciones.

La protección frente a que un volumen elevado de replays desplace el
procesamiento normal se apoya, en esta iteración, exclusivamente en los
mecanismos ya definidos:

- rate limiting por cliente sobre el endpoint de replay;
- transición atómica `FAILED -> PENDING`;
- bulkhead global de entrega;
- bulkhead por cliente.

Introducir un origen persistido para priorizar AUTOMATIC sobre
MANUAL_REPLAY en el claim queda como evolución futura, documentada en
ADR-006 ("Evolución futura").

No se implementará un pool de workers separado exclusivamente para replay.

### 4. Protección contra SSRF

Las URLs de webhook serán consideradas datos no confiables.

Antes de realizar una entrega se deberá:

1. Resolver el hostname.
2. Obtener la dirección IP resultante.
3. Validar la IP contra rangos no permitidos.
4. Rechazar direcciones loopback, privadas y link-local.
5. Conectarse a la IP validada.
6. Evitar que el cliente HTTP vuelva a resolver una dirección diferente.
7. No seguir redirects automáticamente.
8. Restringir el puerto permitido al utilizado por HTTPS.

La validación deberá realizarse en cada intento de entrega y no únicamente
cuando se registre la suscripción.

Esto es importante porque una resolución DNS válida en un momento determinado
no garantiza que la misma URL continúe resolviendo al mismo destino después.

Como evolución para un entorno productivo se considera una capa adicional de
protección mediante un proxy de egreso o control equivalente a nivel de red.

### 5. Integridad y autenticidad de los webhooks

Las notificaciones enviadas al webhook utilizarán una firma HMAC.

La firma incluirá el timestamp y el cuerpo original de la request.

Conceptualmente:

`HMAC_SHA256(secret, timestamp + "." + raw_body)`

El timestamp permitirá al receptor rechazar requests antiguas y reducir el
riesgo de replay attacks sobre el webhook.

El secreto será independiente por suscripción y no deberá aparecer en logs,
payloads ni registros de auditoría.

### 6. Idempotencia

Cada notificación enviada al webhook tendrá una idempotency key estable.

La idempotency key permitirá al receptor reconocer que una misma notificación
puede haber sido entregada más de una vez.

HMAC e idempotency key tienen responsabilidades diferentes:

- HMAC permite verificar autenticidad e integridad.
- Idempotency key permite detectar entregas duplicadas.

El sistema seguirá utilizando una semántica de entrega at-least-once, ya que
no es posible garantizar exactly-once delivery contra un sistema externo que
no comparte una transacción con este servicio.

### 7. Protección de información sensible

El payload de una notificación puede contener información sensible.

Por este motivo:

- El payload completo no se expondrá en endpoints de listado.
- No se registrará el payload completo en logs.
- No se repetirá el payload innecesariamente en cada `DeliveryAttempt`.
- Las respuestas recibidas desde los webhooks serán truncadas antes de ser
  almacenadas.
- Los secretos nunca se almacenarán en registros de auditoría.
- El detalle de una notificación tendrá controles de autorización equivalentes
  a los utilizados para el listado.

## OWASP Top 10

Los tres riesgos principales seleccionados para Task 3 serán:

### BOLA / IDOR

Un cliente podría intentar acceder a una notificación perteneciente a otro
cliente utilizando directamente su identificador.

Mitigación:

- Identidad autenticada.
- Scoping obligatorio por `client_id`.
- Verificación de pertenencia en cada operación.
- Respuesta `404` para recursos que no pertenecen al cliente.

### SSRF

El servicio realiza requests hacia URLs configuradas por terceros, por lo
que una URL maliciosa podría intentar utilizar el servicio para acceder a
recursos internos.

Mitigación:

- Resolución explícita.
- Validación de IP.
- Bloqueo de rangos privados, loopback y link-local.
- Conexión a la IP validada.
- Deshabilitar redirects.
- Restricción de puerto.

### Unrestricted Resource Consumption

El endpoint de replay podría utilizarse para generar una cantidad excesiva
de trabajo y consumir recursos del servicio.

Mitigación:

- Rate limiting por cliente.
- Transición atómica `FAILED -> PENDING`.
- Bulkhead de entrega por cliente ya definido para limitar las llamadas
  concurrentes hacia cada webhook.

No se prioriza el trabajo del flujo normal sobre el trabajo de replay en el
mecanismo de claim (ver sección 3 y ADR-006); esta lista cubre las
mitigaciones que sí están vigentes.

## Consecuencias

### Positivas

- Los clientes no pueden utilizar libremente un `client_id` para acceder a
  información de otros clientes.
- Los replays simultáneos sobre una misma notificación no generan múltiples
  trabajos.
- Un cliente no puede monopolizar el pipeline mediante replay.
- Las URLs de webhook tienen controles contra SSRF.
- Los receptores pueden verificar la autenticidad de las notificaciones.
- Los duplicados pueden manejarse mediante idempotency keys.
- Se reduce la exposición de información sensible.
- Las principales vulnerabilidades de seguridad pueden demostrarse mediante
  pruebas automatizadas.

### Negativas

- La autenticación mediante API Keys requiere gestionar almacenamiento y
  validación de credenciales.
- La validación SSRF agrega complejidad al cliente HTTP.
- HMAC requiere gestionar secretos por suscripción.
- El rate limiting requiere mantener algún tipo de estado asociado al cliente.
- La priorización del trabajo añade una consideración adicional al mecanismo
  de claim.
- La entrega at-least-once significa que el receptor debe implementar
  idempotencia para evitar efectos duplicados.

## Alternativas consideradas

### API Gateway / BFF

Se considera la opción adecuada para un entorno productivo donde múltiples
microservicios compartan autenticación, autorización, rate limiting y otras
políticas.

No se implementará como parte del challenge para evitar introducir
infraestructura que no es necesaria para demostrar el comportamiento del
servicio.

### OAuth2 / OIDC

Son opciones válidas para una plataforma real, pero se consideran
innecesarias para la implementación de este challenge.

### JWT

Podría utilizarse si la plataforma ya entregara tokens firmados con la
identidad del cliente.

Para el challenge se utilizará una API Key más simple.

### Rate limiting global

Se descarta como único mecanismo porque un cliente podría consumir una parte
importante del presupuesto global y afectar a otros clientes.

El límite principal será por cliente.

### Semáforo separado para replay

Se descarta porque el bulkhead de entrega por cliente ya limita la cantidad
de entregas concurrentes hacia cada cliente.

Agregar otro semáforo específico para replay duplicaría responsabilidades.

### Circuit breaker

Se mantiene como una evolución posible del diseño, pero queda fuera del
alcance principal de implementación.

## Alcance

Esta decisión cubre:

- autenticación básica del challenge
- autorización por cliente
- protección BOLA
- protección del endpoint de replay
- protección SSRF
- HMAC
- idempotencia
- tratamiento de información sensible
- aislamiento parcial entre trabajo normal y replay mediante rate limiting,
  transición atómica y bulkheads (sin prioridad de selección en el claim,
  ver sección 3)

Quedan como evolución futura:

- API Gateway/BFF real
- OAuth2/OIDC
- proxy de egreso dedicado
- secret manager dedicado
- cuotas de replay a largo plazo
- circuit breaker distribuido por cliente
- origen persistido en `Notification` para priorizar AUTOMATIC sobre
  MANUAL_REPLAY en el claim (ver ADR-006, "Evolución futura")

## Decisiones relacionadas

- ADR-001: [ADR-001-kafka-event-ingestion.md](ADR-001-kafka-event-ingestion.md)
- ADR-002: [ADR-002-postgresql-notification-store.md](ADR-002-postgresql-notification-store.md)
- ADR-003: [ADR-003-decouple-event-sources.md](ADR-003-decouple-event-sources.md)
- ADR-004: [ADR-004-delivery-processing-model.md](ADR-004-delivery-processing-model.md)
- ADR-005: [ADR-005-idempotency-and-concurrency.md](ADR-005-idempotency-and-concurrency.md)
- ADR-006: [ADR-006-delivery-retry-strategy.md](ADR-006-delivery-retry-strategy.md)
- ADR-007: [ADR-007-worker-concurrency.md](ADR-007-worker-concurrency.md)