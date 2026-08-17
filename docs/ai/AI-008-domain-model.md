# AI-008: Modelo de dominio

## Herramienta

Claude Code

## Propósito

Definir y validar el modelo de dominio del sistema de notificaciones antes
de comenzar el diseño de puertos, adapters y estructura de paquetes.

El objetivo es identificar las entidades y conceptos principales del dominio,
sus responsabilidades, relaciones, invariantes y reglas de negocio.

## Prompt

quiero continuar el análisis del challenge, pero todavía no quiero escribir codigo ni modificar el proyecto. hasta este momento ya tomé varias decisiones arquitectonicas sobre ingesta con kafka, postgresql, workers con virtual threads, concurrencia, retries, leases, idempotencia, seguridad y replay.
ahora quiero concentrarme unicamente en el modleo de dominio antes de diseñar la estructura de paquetes de la arquitectura hexagonal.
revisa nuevamente el challenge adjunto y considera las decisiones que ya documenté en los ADR existentes.
quiero que me ayudes a validar y cuestionar el modelo de dominio que estoy pensando.
por ahora estou considerando conceptos como:
- Event
- Subscription
- Notification
- DeliveryAttempt

mi principal duda es como deberían relacionarse entre ellos.
quiero que analices especialmente:
- que representa exactamente cada concepto dentro del dominio
- cuál debería ser la identidad de cada entidad
- que relación debería existir entre Event, Subscription y Notification
- si una Notification debería representar la relación entre un Event y un Client o entre un Event y una Suscription
- que ocurre si un cliente tiene más subscription que coincida con el mismo event_type
- que información pertenece realmente al dominio y cuál debería quedar en infraestructura
- cual debería ser la maquina de estados definitiva de Notifications
- que invariantes debería proteger el dominio
- que reglas corresponden al dominio y cuales deberían pertenecer a la capa de aplicación
- que información necesita Notification para soportar retries, replay y observabilidad
- que información debería ser inmutable y cual puede cambiar durante el cliclo de vida.
- cómo debería modelarse la relación entre los intentos automaticos y los intentos generados por replay.
- cómo debería representar un fallo definitivo
- cómo debería afectar un lease expirado al modelo de dominio
- que decisiones de modelo podrían complicar innecesariamente la implementación.

tambien quiero que revises si estoy introduciendo conceptos que realmente no necesito o si falta alguna entidad importante.
No quiero que diseñes todavía las tablas de postgresql ni los paquetes Java. tampoco quiero codigo. quiero primero una propuesta de modelo conceptual y que seas critico con ella.
si existen variables alternativas razonables, compáralas y explicame sus trade-offs antes de recomendar una.
Es importante que distingas claramente entre:
1. lo que exige explicitamente el challenge
2. lo que estoy asumiendo
3. lo que estas recomendando como decisión de diseño

al final dame una recomendación concreta del modelo de dominio que debería adoptar para poder pasar después al diseño de la arquitectura hexagonal y la estructura del proyecto.
@challenge\ @docs/architecture/decisions\

## Principales conclusiones

- El dominio debe distinguir entre Event, Subscription, Notification y DeliveryAttempt.
- Event representa un hecho ocurrido en la plataforma y es inmutable.
- Subscription representa la configuración mediante la cual un cliente solicita recibir notificaciones.
- Notification representa una obligación concreta de entrega asociada a un Event y una Subscription.
- DeliveryAttempt representa un intento individual de entrega y debe ser append-only.
- Notification debe modelarse como la relación entre Event y Subscription, no entre Event y Client.
- La unicidad de una Notification debe garantizarse mediante `(event_id, subscription_id)`.
- Notification es el agregado raíz que controla el ciclo de vida de la entrega.
- La máquina de estados continúa siendo PENDING, PROCESSING, COMPLETED y FAILED.
- PENDING representa tanto notificaciones nuevas como aquellas esperando un retry.
- Los retries se controlan mediante `attempt_count`, `next_attempt_at` y una política de retry.
- El lease (`claimed_by`, `lease_expires_at`) forma parte del estado necesario para garantizar la recuperación ante fallos de workers.
- DeliveryAttempt debe conservar el historial completo de intentos y nunca modificarse después de creado.
- El payload de la notificación debe quedar congelado al momento de crear Notification.
- La URL y el estado de la Subscription deben resolverse nuevamente en cada intento de entrega.
- Cada DeliveryAttempt debe registrar la URL utilizada realmente durante ese intento.
- Las transiciones de estado deben estar protegidas por invariantes del propio dominio.
- Replay solo está permitido desde FAILED.
- Replay no reinicia `attempt_count`.
- Los intentos automáticos y los provocados por replay deben distinguirse mediante el origen del intento.
- No se considera necesario crear una entidad ReplayRequest.
- Client no será modelado como una entidad rica dentro de este bounded context.
- No se considera necesario crear entidades adicionales para WebhookEndpoint o EventType.

## Impacto en mi análisis

El análisis permitió profundizar en el modelo de dominio y detectar que
Notification no debería representar simplemente la relación entre un evento
y un cliente.

La decisión inicial consideraba una relación:

`Event + Client`

Sin embargo, el análisis mostró que la unidad real de entrega es una
Subscription. Esto permite representar correctamente el caso en que un mismo
evento pueda generar entregas independientes hacia diferentes subscriptions
de un mismo cliente.

Por este motivo, la identidad de Notification se definirá conceptualmente
como:

`Event + Subscription`

y la constraint de unicidad utilizada para garantizar idempotencia deberá
ser:

`(event_id, subscription_id)`

Esta decisión refina la decisión registrada anteriormente en ADR-005 sin
cambiar el mecanismo general de idempotencia basado en una constraint única.

También se decidió que Notification será el agregado raíz responsable de
proteger sus propias transiciones de estado e invariantes, evitando que la
capa de aplicación dependa de setters libres o de que cada consumidor recuerde
manualmente las reglas válidas.

El análisis también permitió establecer una separación más clara entre el
dominio y la infraestructura. Conceptos como Kafka, offsets, SKIP LOCKED,
connection pools y semáforos pertenecen a infraestructura, mientras que las
reglas de transición, retry y ciclo de vida pertenecen al dominio.

Finalmente, se determinó que el payload debe congelarse al crear la
Notification, mientras que la URL y el estado de la Subscription deben
resolverse nuevamente en cada intento de entrega. Esto permite que una
corrección de configuración del cliente pueda beneficiar automáticamente a
los retries pendientes.

## Decisiones derivadas

### Event

Representa un hecho ocurrido en la plataforma.

- Inmutable.
- Identificado mediante `event_id`.
- Su identidad proviene del sistema upstream.
- No contiene lógica de ciclo de vida de la notificación.

### Subscription

Representa el destino y configuración de entrega de un cliente.

Conceptualmente contiene:

- `subscription_id`
- `client_id`
- webhook URL
- HMAC secret
- estado activo/inactivo

Para el alcance actual se considera una subscription por cliente, sin
implementar filtros complejos por `event_type`.

### Notification

Representa una obligación concreta de entrega.

Su identidad está asociada a:

`(event_id, subscription_id)`

Debe contener, entre otros:

- `notification_event_id`
- `event_id`
- `subscription_id`
- payload
- status
- attempt_count
- next_attempt_at
- last_attempted_at
- failure_reason
- claimed_by
- lease_expires_at

El payload queda congelado al momento de crear la Notification.

### DeliveryAttempt

Representa un intento individual de entrega.

Debe ser append-only y registrar información suficiente para reconstruir la
historia de la entrega.

Entre otros datos:

- identificador del intento
- número de intento
- fecha/hora
- duración
- outcome
- HTTP status
- error
- URL utilizada
- trigger/origen del intento

Los intentos no deben modificarse posteriormente.

## Máquina de estados

La máquina de estados definida previamente se mantiene:

```text
PENDING
   │
   ▼
PROCESSING
   │
   ├── éxito ──────────────► COMPLETED
   │
   ├── fallo retryable ────► PENDING
   │
   └── fallo definitivo ───► FAILED

FAILED
   │
   └── replay ─────────────► PENDING
```

## Evidencia

- [AI-008 Prompt](screenshots/AI-008-prompt.png)
- [AI-008 Response](screenshots/AI-008-response.png)