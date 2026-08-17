# AI-009: Arquitectura Hexagonal y Casos de Uso

## Herramienta

Claude Code

## Propósito

Validar la definición de la arquitectura hexagonal del servicio,
identificar los casos de uso principales y establecer una separación
clara entre dominio, aplicación, puertos y adapters antes de definir
la estructura concreta de módulos y paquetes.

## Prompt

quiero continuar el análisis del challenge y ahora pasar de la definición del dominio a la arquitectura hexagonal y los casos de uso.
ya tengo definidos varios ADRs y un modelo de dominio basado en Event, Suscription, Notification y DeliveryAttempt.
Por ahora no quiero que escribas codigo ni modifiques el proyecto.
quiero validar primero cómo debería organizarse la arquitectura y cuales deberían ser los casos de uso principales.
Mi intención inciar es utilizar arquitectura hexagonal separando claramente:
- dominio
- aplicación/casos de uso
- puertos de entrada
- puertos de salida
- adapters de entrada
- adapters de salida

los adapters de entrada que estoy considerando son:
- consumer de kafka
- adapter que lea el JSON proporcionado por el challenge
- REST API

los adapters de salida que estoy considerando son:
- Postgresql
- cliente HTTP para enviar webhooks

también estoy considerando que el dominio no debería depender directamente de Spring, Kafka, Postgresql, HTTP, JPA, virtual threads ni otras tecnologías de infraestructura.
Los casos de uso que actualmente estoy considerando son:

1. procesar un evento recibido
2. listar notificaciones de un cliente
3. obtener el detalle de una notificación
4. reproducir una notificación fallida

para el procesamiento de eventos estoy pensando en un flujo parecido a este:
- evento recibido
- buscar subscriptions aplicables
- crear notification
- persistirla de forma idempotente
- confirmar el procesamiento del evento

la entrega del webhook la estoy considerando como un proceso separado del consumo de kafka:
notificación pendiente
- reclamar notificación
- resolver subscription vigente
- aplicar controles de concurrencia
- enviar webhook
- registrar DeliveryAttempt
- actualizar estado de Notification
- programar retry o marcar completed/failed

ya decidí previamente esto:

- kafka será utilizado como mecanismo de ingesta
- postgresql será la fuente de la verdad del estado de las Notifications
- no utilizaré una segunda tecnología de mensajería por ahora
- los workers/virtual threads realizarán la entrega de webhooks
- la concurrencia HTTP tendrá un limite global y otro por cliente
- existirá un lease para recuperar Notifications cuyo worker haya fallado
- Notifications y DeliveryAttempt serán entidades separadas.
- DeliveryAttemp será append-only
- Notification tendrá los estados: PENDING, PROCESSING, COMPLETED y FAILED
- replay solo estará permitido desde FAILED
- el payload de la Notification será inmutable
- la Subscription vigente se resolverá neuvamente en cada intento
- la idempotencia de creación utilizará event_id + subscription_id
- el dominio debe proteger sus propias invariantes

quiero que revises críticamente esta propuesta y me digas:
1) si los casos de uso que estoy planeando son correctos o si falta alguno importante
2) si "procesar un evento recibido" debería ser un unico caso de uso o si debería separarse en responsabilidades/casos de uso más pequeños
3) si la entrega del webhook debería considerarse un caso de uso de aplicación o independiente del procesamiento del evento
4) que debería pertenecer al dominio y que debería pertenecer a la capa de aplicación
5) que puertos de entrada debería tener la aplicación
7) que responsabilidades deberían tener esos puertos y que responsabilidades NO deberían tener
8) si kafka debería ser un puerto de salida o si conceptualmente es un adapter de entrada en este diseño
9) si el adapter JSON del challenge debería implementar el mismo puerto que el consumer de kafka
10) cómo debería manejarse el flujo de claim -> HTTP -> persistencia sin permitir que una transacción o conexión de postgresql permanezca abierta durante la llamada HTTP.
11) dónde deberían vivir los limites de concurrencia global y por cliente
12) donde deberían vivir las decisiones de retry, backoff, clasificación de errores y cálculo de next_attempt_at
13) donde debería vivir el lease y su recuperación
14) donde debería vivir la autenticación/autorización de los endpoints REST.
15) que partes de la arquitectura podría estar sobrearquitecturando para este challenge
16) que decisiones arquitectonicas importantes todavía faltan antes de comenzar a crear paquetes y clases

Quiero que revises si estoy confundiendo conceptos de dominio con mecanismos de infraestructura.
No quiero qye simplemente confirmes mis decisiones. si encuentras una mejor alternativa quiero que la plantees y expliques el trade-off.
Tampoco quiero todavia una estructura de paquetes definitiva ni código.
El objetivo de este análisis es salis con una arquitectura hexagonal conceptualmente clara y una lista de casos de uso y puertos suficientemente definida para despues poder diseñar la estructura del proyecto.

## Principales conclusiones

- Se identificaron cinco casos de uso principales:
    - Procesar evento recibido.
    - Entregar notificación pendiente.
    - Listar notificaciones.
    - Obtener detalle de una notificación.
    - Reproducir una notificación fallida.

- El procesamiento de un evento recibido debe mantenerse como un único
  caso de uso de aplicación, aunque internamente utilice colaboradores
  como la resolución de suscripciones y la creación de notificaciones.

- La entrega de una notificación debe ser un caso de uso independiente
  del procesamiento de eventos.

- El Worker actúa como adapter de entrada para el caso de uso de entrega.
  No es necesario que todos los adapters de entrada representen tráfico
  externo.

- Kafka y el adapter JSON deben implementar el mismo puerto de entrada
  para la ingestión de eventos.

- Kafka debe permanecer completamente aislado del dominio y de la capa
  de aplicación. El core no debe conocer topics, offsets ni
  ConsumerRecord.

- El adapter JSON debe transformar los datos del archivo al objeto Event
  de dominio antes de invocar el puerto de entrada.

- Los adapters REST deben encargarse de traducir HTTP hacia los casos de
  uso, pero no deben contener reglas de negocio.

- La autenticación pertenece al adapter REST, mientras que la
  autorización relacionada con la propiedad de las notificaciones debe
  ser garantizada por la capa de aplicación/dominio.

- Los límites de concurrencia global y por cliente pertenecen al Worker,
  no al dominio ni al caso de uso de entrega.

- El claim de notificaciones es una operación del repositorio y no un
  caso de uso independiente.

- El flujo de entrega debe mantener separadas las tres etapas:
  claim → llamada HTTP → persistencia del resultado.

- No se debe mantener una transacción ni una conexión de PostgreSQL
  abierta durante la llamada al webhook.

- La clasificación de errores y la política de retry/backoff representan
  reglas del dominio, mientras que la traducción de respuestas HTTP y
  excepciones de infraestructura hacia resultados de dominio pertenece
  a las capas externas.

- El lease representa una invariante importante del proceso de entrega,
  aunque la consulta SQL utilizada para implementarlo pertenezca a
  infraestructura.

- No todos los colaboradores internos necesitan convertirse en puertos.
  Los puertos deben representar fronteras reales entre el core y
  infraestructura externa.

## Arquitectura resultante

La arquitectura se organiza conceptualmente alrededor del dominio:

    Domain
       │
       v
    Application
       │
       ├── Input Ports
       │
       └── Output Ports
              │
              v
         Adapters / Infrastructure

Los adapters de entrada invocan los casos de uso mediante los input
ports.

Los casos de uso utilizan output ports para interactuar con elementos
externos como PostgreSQL, suscripciones y el cliente HTTP de webhooks.

El dominio permanece independiente de Spring, Kafka, PostgreSQL,
HTTP y cualquier otra tecnología de infraestructura.

## Input Ports identificados

### IngestEventPort

Responsable de procesar un evento recibido y crear las notificaciones
correspondientes de forma idempotente.

Será utilizado por:

- Kafka consumer.
- JSON event adapter.

El puerto recibe un Event de dominio y no conoce detalles de Kafka ni
del formato del archivo JSON.

### DeliverNotificationPort

Responsable de ejecutar la entrega de una notificación previamente
reclamada.

Será invocado por el Worker.

### ListNotificationsPort

Responsable de obtener las notificaciones pertenecientes al cliente
autenticado, aplicando los filtros correspondientes.

### GetNotificationDetailPort

Responsable de obtener el detalle de una notificación perteneciente al
cliente autenticado.

### ReplayNotificationPort

Responsable de solicitar el replay de una notificación que se encuentra
en estado FAILED.

La transición debe seguir estando protegida por la condición:

    FAILED → PENDING

## Output Ports identificados

### NotificationRepository

Responsable de persistir y consultar Notification y DeliveryAttempt,
además de ejecutar operaciones relacionadas con el claim y las
transiciones necesarias.

La implementación concreta utilizará PostgreSQL.

### SubscriptionPort

Responsable de resolver las suscripciones activas que correspondan a
un evento.

La implementación concreta es responsabilidad de infraestructura y
puede mantenerse mínima para el alcance del challenge.

### WebhookSenderPort

Responsable de realizar la comunicación HTTP con el webhook externo.

El dominio y la aplicación no deben conocer detalles del cliente HTTP
concreto.

El resultado de esta operación debe transformarse a un objeto de
dominio como DeliveryOutcome.

### Clock / TimeProvider

Responsable de proporcionar la hora actual de forma inyectable.

Esto permite probar determinísticamente:

- Backoff.
- next_attempt_at.
- Expiración de leases.
- Ventanas máximas de retry.

## Flujo de entrega

El flujo recomendado queda conceptualmente definido como:

    Worker
       │
       v
    Claim notifications
       │
       v
    DeliverNotification
       │
       v
    WebhookSenderPort
       │
       v
    DeliveryOutcome
       │
       v
    Domain decision
       │
       ├── COMPLETED
       ├── PENDING + retry
       └── FAILED
       │
       v
    Persist result

El claim y la persistencia del resultado deben ejecutarse en
transacciones cortas e independientes.

La llamada HTTP no debe ejecutarse dentro de una transacción de
PostgreSQL.

## Separación de responsabilidades

### Dominio

El dominio será responsable de:

- Estados de Notification.
- Transiciones válidas.
- Invariantes.
- Replay.
- Clasificación de resultados de entrega.
- Política de retry.
- Backoff.
- Lease como concepto de dominio.
- Reglas relacionadas con DeliveryAttempt.

### Aplicación

La aplicación será responsable de:

- Orquestar los casos de uso.
- Invocar los puertos de salida.
- Coordinar el flujo de entrega.
- Aplicar las reglas de autorización.
- Traducir resultados entre colaboradores.
- Coordinar las transacciones cortas.

### Adapters

Los adapters serán responsables de:

- HTTP/REST.
- Kafka.
- JSON.
- PostgreSQL.
- Cliente HTTP para webhooks.
- Autenticación específica del protocolo.
- Serialización y deserialización.

## Decisiones que permanecen pendientes

Este análisis identificó varias decisiones que deben resolverse antes
de comenzar la implementación:

- Definir las firmas exactas de los input ports.
- Definir las firmas exactas de los output ports.
- Definir el contrato de DeliveryOutcome.
- Definir la estrategia de manejo de errores entre las capas.
- Definir el contrato exacto de SubscriptionPort.
- Definir cómo se traducen los resultados de los casos de uso a
  respuestas HTTP.
- Confirmar la representación de los puertos como interfaces Java
  explícitas.
- Definir el mecanismo concreto de Clock / TimeProvider.

Estas decisiones serán analizadas antes de crear la estructura definitiva
de paquetes y clases.

## Impacto en mi análisis

Este análisis permitió pasar de una definición conceptual de
arquitectura hexagonal a una primera separación explícita entre
casos de uso, dominio, aplicación, puertos y adapters.

La principal conclusión fue que los adapters deben permanecer delgados
y que la orquestación debe vivir en los casos de uso de aplicación.

También quedó claro que Kafka y JSON pueden utilizar exactamente el
mismo puerto de entrada, evitando que la implementación del caso de uso
dependa de una tecnología específica de ingestión.

Otro punto importante fue separar completamente el proceso de
ingestión del proceso de entrega. El Worker será responsable de
determinar cuándo existe capacidad para ejecutar entregas, mientras que
el caso de uso de entrega se concentra en ejecutar correctamente una
notificación.

El análisis también permitió establecer que la concurrencia no debe
formar parte del dominio. Los límites globales y por cliente serán
responsabilidad del Worker.

Finalmente, se identificaron varios contratos que todavía deben
definirse antes de comenzar a crear las clases concretas, especialmente
los puertos de entrada y salida, DeliveryOutcome, Clock y la estrategia
de manejo de errores.

## Evidencia

- [AI-009 Prompt](screenshots/AI-009-prompt.png)
- [AI-009 Response](screenshots/AI-009-response.png)