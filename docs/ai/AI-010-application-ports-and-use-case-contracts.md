# AI-010: Puertos de la capa aplicación y contratos de casos de uso

## Herramienta

Claude Code

## Propósito

Definir con mayor precisión los contratos de los casos de uso y los
puertos de la arquitectura hexagonal antes de crear la estructura
concreta de módulos, paquetes y clases.

El objetivo fue establecer responsabilidades claras entre dominio,
aplicación e infraestructura, evitando tanto el acoplamiento tecnológico
como la sobrearquitectura.

## Prompt

quiero que analices la definición de arquitectura hezagonal que acabamos de establecer ya hora me ayudes a cerrar los contratos de los casos de uso y puertos antes de crear la estructura concetra de paquetes y clases.
No quiero que escribas código todavía ni que modifiques el proyecto.
Quiero que seas crítico y cuestiones las decisiones cuando sea necesario.
El objetivo de este análisis es definir con suficiente precisión que responsabilidades y contratos tendrán los input ports, output ports y casos de uso, pero sin caer en sobrearquitectura.

Contexto de desiciones ya tomadas:
- el sistema utiliza kafka para ingestion de eventos
- tambien tendremos un adapter JSON para ejecutar/demostrar el mismo flujo localmente.
- kafka y json deben utilizar el mismo input port
- postgresql será la fuente de verdad del estadod e las notificaciones
- la arquitectura será hexagonal
- tenemos cinco casos de uso:
1) procesar evento recibido
2) entregar notificación
3) listar notificaciones
4) obtener detalle de una notificación
5) reproducir una notificacion fallida
- la entrega es independiente de la ingestión
- un worker dispara el caso de uso de entrega
- el worker controla los limites de concurrencia global y por cliente
- el claim de notificaciones pertenece al repositorio y no es un caso de uso independiente
- el flujo de entrega es: claim -> llamada HTTP -> persistencia del resultado
- nunca debemos mantener una transacción ni una conexión de postgresql abierta durante la llamada HTTP
- Notification representa la relación entre Event y Subscription
- DeliveryAttempt es append-only
- Notification tiene los estados PENDING, PROCESSING, COMPLETED y FAILED
- Tenemos retry con exponential backoff, cap y full jitter
- tenemos lease para recuperar trabajos de workers que mueren
- tenemos idempotencia mediante contraint unico
- el webhook utiliza idempotency key y HMAC
- La autenticación ocurre en el adapter REST
- la autenticación debe ser garantizada por la aplicación/dominio
- el dominio no debe conocer Kafka, HTTP, postgresql, spring ni DTOs de infraestructura

quiero que analices especificamente:
1) INPUTS PORTS

Analiza estos cinco puertos:
- IngestEventPort
- DeliverNotificationPort
- ListNotificationsPort
- GetNotificationDetailPort
- ReplayNotificationPort

para cada uno quiero saber:
-que responsabilidad exacta tiene
-que información debería recibir
-que información debería devolver
-que NO debería recibir
-que NO debería devolver
-que reglas deben pertenecer al dominio y cuales a la aplicación
-si realmente necesita ser un puerto independiente o si alguno está sobrefragmentado

2) OUTPUT PORTS

Analiza:
-NotificationRepository
-SubscriptionPort
-WebhookSenderPort
-Clock/TimeProvider

para cada uno:
-Responsabilidad
-operaciones que conceptualmente necesita
-que tipos debería intercambiar con la aplicación
-que detalles de infraestructura deben quedar ocultos
-si estoy poniendo demasiadas responsabilidades en algun puerto

Especialmente cuestiona si NotificationRepository debería ser un único puerto grande o si conviene separar algunos contrados de lectura/escritura/claim

3) DELIVERY OUTCOME
Necesitamos definir el contrato conceptual del resultado que devuelve WebhookSenderPort.
Analiza que información debería contener para que el dominio pueda decidir correctamente:
-SUCCESS
-HTTP_ERROR
-TIMEOUT
-DNS_ERROR
-INTERRUPTED
-CIRCUIT_OPEN, si corresponde aunque todavía es opcional

Considera:
-HTTP status code
-Duración
-Información de respuesta
-Tipo de error
-Si el resultado debe contener detalles propios de HTTP o una representación mas abstracta
-Que información debe persistirse en DeliveryAttempt
-Que información NO debería llegar al dominio

Quiero que propongas un modelo que mantenga el dominio desacoplado de HttpClient, Spring y excepciones concretas.

4) MANEJO DE ERRORES
Analiza críticamente estas alternativas:
-Excepciones de dominio/aplicación
-Result/Either
-Una combinación de ambas

necesito especialmente saber:
-Cómo debería comportarse un casod e uso cuando una operación de infraestructura falla.
-Cómo debe traducirse eso en el adapter REST
-Cómo debe comportarse el adapter Kafka
-Que errores deberían representar estados de negocio y cuales deberían propagarse como errores tecnicos.
-Cómo evitar que excepciones de infraestructura contaminin el dominio

5) DTOs VS DOMAIN OBJECTS
Define donde deberían existir:
-Rest request/response DTOs
-kafka DTOs
-JSON input DTOs
-Domain Event
-Notification
-DeliveryAttempt
-DeliveryOutcome

quiero que señales explicitamente cualquier lugar donde estaría tentado a pasar un DTO de infraestructura directamente al dominio

6) TRANSACCIONES

Analiza exactamente donde deberían comenzar y terminar las transacciones en:
-procesamiento de un evento
-claim de notificaciones
-persistencia del resultado de una entega
-replay

quiero que detectes especialmente cualquier diseño que accidentalmente termine manteniendo una transacción abierta durante una llamada HTTP

7) CLOC / TIME PROVIDER

Determinar el contrato mínimo necesario para que pueda probar de forma deterministica:
-exponential backoff
-next_attempt_at
-lease expiration
-max retry window
-replay

No quiero una abstracción de tiempo innecesariamente compleja

8) SUBSCRIPTION PORT

El challenge no proporciona un CRUD de suscriptions.
Define que necesita realmente el caso de uso para resolver las subscriptions activas crrespondiente a un Event.

Analiza si debería recibir:
-Event
-event_type
-clientid
-alguna combinación

y que debería devolver.

Recuerda que Notification representa Event + Subscription

9)AUTORIZACIÓN
Analiza dónde debe aplicarse la autorización para:
-GET /notification_events
-GET /notification_events/{id}
-POST /notification_events/{id}/replay

la identidad del cliente ya viene resuelta por autenticación.
Quiero evitar que el adapter REST sea el único responsable de recordar las reglas de ownership

10) RESULTADOS DE LOS CASOS DE USO
Analiza que deberíad devolver los casos de uso y cómo debería traducirse eso hacia HTTP.
por ejemplo:
-éxito
-recurso inexistente
-recurso perteneciente a otro cliente
-replay no permitido
-conflicto de replay concurrente
-error tecnico

No necesito todavía codigos HTTP definitivos si no hay sificiente información, pero si quiero definir la frontera entre aplicación y adapter.

11) NIVEL DE ABSTRACCIÓN

Finalmente quiero que seas especialmente critico con la posibilidad de sobrearquitectura

Dime:
- que interfaces realmente aportan valor
- que clases no deberían convertirse en ports
- que objetos deberían ser simples clases/records
- que abstracciones parecen innecesarias para este challenge

la meta no es contruir una arquitectura académica perfecta, sino una arquitectura hexagonal clara, defendible y pragmática.
Al finalizar quiero una recomendación concreta de los contratos que deberíamos implementar despues, pero NO escribas el código.
También quiero que señales cualquier decisión arquitectonica nueva que en tu opinion merezca registrarse en un ADR separado en lugar de quedar simplemente como detalle de implementación.

## Principales conclusiones

- Se mantienen cinco casos de uso:
  - Procesar evento recibido.
  - Entregar notificación pendiente.
  - Listar notificaciones.
  - Obtener detalle de una notificación.
  - Reproducir una notificación fallida.

- Los puertos de entrada definidos son:
  - IngestEventPort.
  - DeliverNotificationPort.
  - NotificationQueryPort.
  - ReplayNotificationPort.

- ListNotificationsPort y GetNotificationDetailPort se agrupan en un
único NotificationQueryPort porque ambos representan operaciones de
consulta y no existe suficiente complejidad que justifique dos
interfaces independientes.

- Los puertos de salida definidos son:
  - NotificationRepository.
  - NotificationClaimRepository.
  - SubscriptionPort.
  - WebhookSenderPort.

- El tiempo se manejará mediante java.time.Clock en lugar de crear una
abstracción propia de TimeProvider.

- NotificationRepository y NotificationClaimRepository se mantienen
separados porque el claim representa una operación de posesión
temporal de trabajo y no una operación CRUD convencional.

- No se aplicará una separación adicional entre repositorios de lectura
y escritura, evitando introducir CQRS sin una necesidad concreta.

## Input Ports

### IngestEventPort

Responsable de procesar un Event de dominio ya traducido.

El caso de uso:

1. Resuelve las subscriptions aplicables.
2. Crea las Notification correspondientes.
3. Persiste las Notification de forma idempotente.
4. Devuelve un resultado que permita al adapter de entrada determinar
   si puede confirmar el procesamiento.

El puerto no conoce:

- Kafka.
- ConsumerRecord.
- Topics.
- Partitions.
- Offsets.
- Formato JSON.

Kafka y el adapter JSON utilizan este mismo puerto.

### DeliverNotificationPort

Responsable de ejecutar la entrega de una única Notification previamente
reclamada.

Recibe el notification_event_id y coordina:

1. Resolución de la subscription vigente.
2. Obtención de la información necesaria.
3. Llamada mediante WebhookSenderPort.
4. Interpretación del DeliveryOutcome.
5. Aplicación de las reglas de dominio.
6. Persistencia del resultado.

La llamada HTTP ocurre fuera de cualquier transacción de PostgreSQL.

### NotificationQueryPort

Agrupa las operaciones de consulta:

- Listar notificaciones.
- Obtener detalle de una notificación.

Las consultas siempre reciben el client_id ya autenticado.

El listado debe aplicar el scoping por cliente directamente en la
consulta.

El detalle debe verificar ownership y no revelar mediante un 403 la
existencia de una notificación perteneciente a otro cliente.

El listado no debe exponer innecesariamente el payload completo.

### ReplayNotificationPort

Responsable de solicitar el replay de una Notification.

Debe:

1. Verificar ownership.
2. Verificar que la Notification se encuentre en FAILED.
3. Ejecutar la transición condicional FAILED → PENDING.
4. Informar si el replay fue aceptado o si perdió una condición de
concurrencia.

La transición debe ser atómica.

## Output Ports

### NotificationRepository

Responsable de las operaciones generales relacionadas con
Notification y DeliveryAttempt.

Incluye conceptualmente:

- Creación idempotente de Notification.
- Persistencia del resultado de una entrega.
- Inserción de DeliveryAttempt.
- Consultas de Notification.
- Transición condicional necesaria para replay.

La actualización de Notification y la creación del DeliveryAttempt
deben realizarse dentro de la misma transacción cuando forman parte del
mismo resultado de entrega.

### NotificationClaimRepository

Responsable exclusivamente de reclamar trabajo pendiente.

Su operación principal es conceptualmente:

    claimDueBatch(limit)

Debe considerar:

- PENDING.
- next_attempt_at.
- leases expirados.
- concurrencia entre múltiples instancias.
- SELECT ... FOR UPDATE SKIP LOCKED.
- transición a PROCESSING.
- claimed_by.
- lease_expires_at.

La transacción de claim termina antes de realizar cualquier llamada HTTP.

### SubscriptionPort

Responsable de resolver las subscriptions activas aplicables a un
evento.

El contrato conceptual recibe:

- client_id.
- event_type.

Devuelve una lista de subscriptions activas, posiblemente vacía.

La implementación concreta de este puerto pertenece a infraestructura.

### WebhookSenderPort

Responsable de enviar una notificación al webhook externo.

El puerto recibe la información necesaria para realizar la entrega y
devuelve un DeliveryOutcome.

No debe exponer al dominio:

- HttpClient.
- ResponseEntity.
- excepciones concretas de Java.
- tipos específicos de Spring.
- objetos de librerías HTTP.

El cálculo concreto de HMAC pertenece al adapter de infraestructura,
mientras que el contrato sobre qué información debe firmarse pertenece
a la aplicación/dominio.

### Clock

Se utilizará directamente:

    java.time.Clock

No se creará una interfaz adicional.

Esto permite utilizar:

- Clock.systemUTC() en producción.
- Clock.fixed(...) en pruebas.

El mismo mecanismo permite probar:

- retry backoff.
- next_attempt_at.
- lease expiration.
- max retry window.

## DeliveryOutcome

WebhookSenderPort debe devolver un objeto inmutable que represente el
resultado de la comunicación.

Los tipos considerados son:

- SUCCESS.
- HTTP_ERROR.
- TIMEOUT.
- CONNECTION_ERROR.
- DNS_ERROR.
- INTERRUPTED.
- CIRCUIT_OPEN.

El resultado puede contener conceptualmente:

- outcomeType.
- HTTP status code, cuando exista.
- duración.
- detalle de error acotado.
- fragmento acotado de la respuesta.
- Retry-After interpretado como una duración o instante.

No deben atravesar esta frontera:

- excepciones concretas de infraestructura
- objetos del cliente HTTP
- headers sin filtrar
- cuerpos de respuesta sin truncar.

El adapter HTTP es responsable de traducir los errores técnicos a
DeliveryOutcome.

## Manejo de errores

Se utilizará una combinación de resultados explícitos y excepciones.

Los resultados de negocio esperables se representan mediante tipos
explícitos.

Ejemplos:

- recurso no encontrado;
- recurso no perteneciente al cliente;
- notification no disponible para replay;
- conflicto por replay concurrente.

Los fallos técnicos inesperados, como una caída de PostgreSQL, se
propagan como excepciones y no se convierten artificialmente en
resultados de negocio.

### REST

Un handler centralizado traduce las excepciones técnicas a respuestas
HTTP sin exponer detalles internos.

Los resultados de negocio se traducen explícitamente a los códigos
HTTP correspondientes.

### Kafka

Si el procesamiento del evento termina correctamente, incluyendo un
caso idempotente, el adapter puede confirmar el offset.

Si ocurre un fallo técnico, el offset no se confirma y Kafka puede
reentregar el evento.

## DTOs y objetos de dominio

Los DTOs pertenecen exclusivamente a sus adapters:

- REST DTOs → adapter REST.
- Kafka DTOs → adapter Kafka.
- JSON DTOs → adapter JSON.
- JPA entities → adapter PostgreSQL.

Los objetos de dominio permanecen independientes de frameworks:

- Event.
- Subscription.
- Notification.
- DeliveryAttempt.
- DeliveryOutcome.
- Trigger.

No se utilizarán entidades JPA como objetos de dominio.

Los mappers entre infraestructura y dominio serán explícitos y simples.

## Transacciones

### Procesamiento de eventos

El procesamiento de la Notification se realiza mediante una transacción
corta.

El commit de PostgreSQL debe ocurrir antes de confirmar el offset de
Kafka.

### Claim

El claim utiliza una transacción corta independiente.

Conceptualmente:

    SELECT ... FOR UPDATE SKIP LOCKED
    UPDATE ... SET status = PROCESSING
    COMMIT

La transacción termina antes de realizar la llamada HTTP.

### Persistencia del resultado

Después de finalizar la llamada HTTP se ejecuta otra transacción corta
que:

- actualiza Notification
- registra DeliveryAttempt.

Ambas operaciones deben ser atómicas entre sí.

### Replay

El replay utiliza una transacción corta que ejecuta únicamente la
transición condicional:

    FAILED → PENDING

No debe envolver ninguna llamada HTTP.

## Regla importante sobre transacciones

El caso de uso DeliverNotification no debe estar envuelto en una única
transacción mediante @Transactional.

La arquitectura debe evitar accidentalmente mantener una conexión o
transacción de PostgreSQL abierta durante la llamada al webhook.

## Autorización

La autenticación ocurre en el adapter REST.

El client_id autenticado se pasa hacia los casos de uso.

La autorización debe permanecer garantizada en la aplicación/dominio.

Para una Notification individual se utiliza una regla de ownership,
conceptualmente:

    notification.belongsTo(clientId)

Para los listados, el scoping por client_id debe formar parte de la
consulta al repositorio.

## Nivel de abstracción

Se consideran puertos explícitos las interfaces que representan
fronteras tecnológicas reales o contratos de entrada relevantes:

- IngestEventPort.
- DeliverNotificationPort.
- ReplayNotificationPort.
- NotificationQueryPort.
- NotificationRepository.
- NotificationClaimRepository.
- SubscriptionPort.
- WebhookSenderPort.

No se crearán puertos para lógica de dominio pura como:

- NotificationFactory.
- DeliveryErrorClassifier.
- RetryPolicy.

Estas responsabilidades pueden representarse mediante clases,
records o servicios de dominio simples.

Tampoco se crearán abstracciones adicionales para:

- Circuit Breaker.
- Bulkhead.
- Clock.
- mapeos genéricos.

## Decisiones pendientes identificadas

El análisis identificó una necesidad de transportar información sobre el
origen de un replay hasta el DeliveryAttempt que eventualmente se cree.

Para el alcance actual del challenge no se implementará una entidad o
mecanismo adicional para resolver esta correlación.

Este aspecto queda documentado como una posible evolución futura.

## Impacto en mi análisis

Este análisis permitió cerrar la definición conceptual de los puertos y
casos de uso antes de comenzar la implementación.

La principal conclusión fue que la arquitectura puede mantenerse
hexagonal sin crear interfaces innecesarias. Los puertos deben
representar fronteras reales entre la aplicación y la infraestructura,
mientras que la lógica de dominio pura puede permanecer como clases o
servicios internos.

También se estableció que NotificationRepository y
NotificationClaimRepository deben mantenerse separados, ya que el claim
representa una operación de posesión temporal y concurrencia que tiene
una responsabilidad diferente a las operaciones generales del
repositorio.

Se decidió utilizar java.time.Clock directamente en lugar de crear una
abstracción propia para el tiempo.

El análisis también permitió definir DeliveryOutcome como la frontera
entre la infraestructura HTTP y las reglas de dominio, evitando que
excepciones o tipos concretos del cliente HTTP contaminen el core.

Finalmente, se identificó la necesidad de transportar información
relacionada con el origen de un replay hasta el DeliveryAttempt
correspondiente. Debido al alcance limitado del challenge y al tiempo
disponible, esta preocupación queda explícitamente fuera de la
implementación actual y se considera una posible evolución futura.

## Evidencia

- [AI-010 Prompt](screenshots/AI-010-prompt.png)
- [AI-010 Response](screenshots/AI-010-response.png)