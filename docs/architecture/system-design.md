# Diseño de solución

## Event Notification Service

### 1. Objetivo

Event Notification Service es un servicio encargado de recibir eventos generados por la plataforma, determinar si deben ser notificados a un cliente mediante una suscripción activa y entregar dichas notificaciones a su endpoint webhook.

La solución también proporciona una API de self-service para que cada cliente pueda consultar el estado y detalle de sus notificaciones y solicitar el reenvío de aquellas que hayan fallado definitivamente.

Los objetivos principales de la solución son:

- Entregar cada evento únicamente al cliente al que pertenece.
- Desacoplar la generación de eventos de su entrega mediante procesamiento asíncrono.
- Garantizar que una notificación no sea procesada simultáneamente por múltiples workers.
- Manejar errores transitorios mediante reintentos con backoff.
- Persistir el estado y resultado de cada entrega.
- Proporcionar observabilidad cercana al tiempo real.
- Aislar los datos y operaciones de cada cliente.
- Proteger la aplicación frente a riesgos asociados con endpoints webhook configurables.

---

## 2. Requisitos principales

La solución cubre las dos capacidades principales definidas en el challenge.

### Entrega de notificaciones

- Confirmar mediante una suscripción si un evento debe ser entregado.
- Garantizar que el evento pertenece al cliente que recibirá la notificación.
- Entregar la notificación mediante HTTPS al endpoint configurado.
- Firmar la solicitud mediante HMAC.
- Manejar errores mediante una política de reintentos.
- Persistir el estado final de la entrega y sus intentos.
- Exponer métricas para detectar desviaciones del comportamiento esperado.

### API de self-service

- Consultar las notificaciones de un cliente.
- Filtrar por fecha de creación y estado.
- Obtener el detalle de una notificación.
- Solicitar el reenvío de una notificación que haya fallado definitivamente.

### Seguridad

La API puede estar expuesta públicamente, por lo que la solución considera, entre otros:

- aislamiento de recursos entre clientes,
- protección contra SSRF,
- limitación de consumo,
- autenticación mediante API Key.

---

# 3. Arquitectura de alto nivel

La solución sigue una arquitectura hexagonal.

El núcleo de aplicación contiene los casos de uso y el dominio, sin depender directamente de Spring, JPA, Kafka o Micrometer.

Los mecanismos externos se conectan mediante puertos y adapters:

- Kafka y JSON actúan como mecanismos de entrada de eventos.
- REST actúa como entrada para el self-service.
- PostgreSQL proporciona persistencia.
- El webhook del cliente constituye la salida del sistema.
- Prometheus/Micrometer proporciona observabilidad operacional.

Esta separación permite cambiar los mecanismos de infraestructura sin modificar las reglas principales del dominio.

## 3.1 C4 - Contexto

El siguiente diagrama muestra el sistema desde el exterior y sus principales relaciones, sin entrar en detalles internos.

> El diagrama C4 de contexto se mantiene en [`diagrams/c4-diagrams.md`](diagrams/c4-diagrams.md).

**Qué comunica:** el servicio se encuentra entre la plataforma que genera eventos, el almacenamiento del estado y los consumidores externos. En este nivel no se muestran componentes internos.

---

# 4. Vista de contenedores

El sistema se despliega actualmente como una aplicación Spring Boot única, acompañada por PostgreSQL y Kafka.

Dentro del proceso de Spring Boot conviven los diferentes mecanismos de entrada, procesamiento, delivery y observabilidad. Esta vista mantiene esa realidad de despliegue y evita representar clases internas como containers independientes.

> El diagrama C4 de contenedores se mantiene en [`diagrams/c4-diagrams.md`](diagrams/c4-diagrams.md).

**Qué comunica:** la aplicación es una unidad de despliegue única. Kafka y PostgreSQL son dependencias externas del proceso. Los detalles internos de la aplicación se reservan para la vista de componentes.

---

# 5. Vista de componentes

La siguiente vista muestra las principales responsabilidades internas involucradas en el procesamiento y delivery de una notificación.

> El diagrama C4 de componentes se mantiene en [`diagrams/c4-diagrams.md`](diagrams/c4-diagrams.md).

**Qué comunica:** esta vista muestra cómo se organiza internamente el flujo de delivery y dónde están ubicadas las principales responsabilidades, manteniendo el dominio y los casos de uso separados de la infraestructura.

---

# 6. Flujo principal: evento → webhook

El flujo principal es asíncrono. La recepción del evento no depende de que el webhook del cliente esté disponible en ese momento.

> El diagrama completo se encuentra en [`diagrams/sequence-event-to-delivery.md`](diagrams/sequence-event-to-delivery.md).

### Decisión clave

Kafka desacopla la generación del evento de su entrega. La disponibilidad temporal del webhook no bloquea al productor del evento ni provoca pérdida del estado de la notificación.

---

# 7. Retry y concurrencia

El delivery worker puede procesar múltiples notificaciones concurrentemente, pero cada notificación debe tener un único propietario en cada instante.

El mecanismo de claim utiliza persistencia transaccional, lease y fencing para evitar que dos workers procesen simultáneamente la misma notificación.

> El diagrama completo se encuentra en [`diagrams/sequence-retry-and-concurrency.md`](diagrams/sequence-retry-and-concurrency.md).

### Principios

- La concurrencia se controla globalmente y por cliente.
- Una notificación reclamada queda asociada a un lease.
- Los errores transitorios pueden generar nuevos intentos.
- Los fallos definitivos dejan la notificación en `FAILED`.
- El cliente puede solicitar un replay únicamente para una notificación fallida.

---

# 8. Self-service API

Cada cliente interactúa con el servicio mediante una API autenticada con API Key.

El `client_id` no es recibido como parámetro de confianza. Se obtiene de la identidad autenticada y se utiliza para limitar las consultas y operaciones al ámbito del cliente.

### Endpoints

| Método | Endpoint | Propósito |
|---|---|---|
| GET | `/notification_events` | Lista las notificaciones del cliente |
| GET | `/notification_events/{id}` | Obtiene el detalle de una notificación |
| POST | `/notification_events/{id}/replay` | Solicita el reenvío de una notificación `FAILED` |

El listado permite filtrar por:

- `created_from`
- `created_to`
- `status`

El acceso a una notificación de otro cliente produce el mismo resultado que una notificación inexistente, evitando revelar información sobre recursos ajenos.

---

# 9. Seguridad

La solución considera tres riesgos relevantes para una API expuesta públicamente.

| Riesgo | Impacto | Mitigación |
|---|---|---|
| BOLA / Broken Object Level Authorization | Un cliente podría intentar acceder a notificaciones pertenecientes a otro cliente | El `client_id` proviene de la identidad autenticada y las consultas se realizan dentro del ámbito del cliente |
| SSRF | Un webhook configurable podría utilizarse para acceder a recursos internos | Validación del destino, resolución segura de direcciones y restricciones de conexión |
| Unrestricted Resource Consumption | Un cliente podría generar una carga excesiva sobre el sistema | Rate limiting, límites de payload/response y límites de concurrencia |

La autenticación de la API utiliza `X-Api-Key`.

Las entregas hacia los clientes utilizan HTTPS y una firma HMAC para permitir la verificación de autenticidad del mensaje recibido.

Para el detalle de las decisiones de seguridad, consultar `ADR-008`.

---

# 10. Observabilidad

El challenge requiere que la capacidad de delivery pueda ser observada en un enfoque cercano al tiempo real.

La solución expone métricas mediante Micrometer en formato compatible con Prometheus:

`GET /actuator/prometheus`

Las principales métricas son:

| Métrica | Tipo | Propósito |
|---|---|---|
| `notification.delivery.attempts` | Counter | Volumen y resultado de los intentos |
| `notification.delivery.duration` | Timer | Latencia de las entregas |
| `notification.retry.scheduled` | Counter | Cantidad de reintentos programados |
| `notification.failed.definitive` | Counter | Fallos definitivos |
| `notification.backlog.pending` | Gauge | Cantidad actual de trabajo pendiente |

No se utiliza `client_id` como tag de las métricas para evitar una cardinalidad potencialmente no acotada.

El detalle por cliente se obtiene mediante la API de self-service, mientras que las métricas están orientadas a la salud agregada del sistema.

---

# 11. Resiliencia y escalabilidad

La solución está diseñada para permitir crecimiento horizontal del procesamiento de delivery.

### Procesamiento asíncrono

Kafka desacopla la producción de eventos del procesamiento posterior.

### Persistencia del estado

PostgreSQL mantiene el estado durable de cada notificación y sus intentos de entrega.

### Concurrencia controlada

Los workers pueden procesar múltiples notificaciones en paralelo, con límites globales y por cliente.

### Claim y lease

El mecanismo de claim evita que múltiples workers procesen simultáneamente la misma notificación y permite recuperar trabajo cuyo worker haya fallado.

### Retry con backoff

Los errores transitorios no provocan una pérdida inmediata de la notificación. El sistema programa nuevos intentos siguiendo una política de backoff.

### Idempotencia

La ingesta utiliza identificadores del evento y la suscripción para evitar la creación de notificaciones duplicadas.

---

# 12. Decisiones arquitectónicas principales

Las decisiones más relevantes están documentadas individualmente mediante ADRs.

| Decisión | Motivo |
|---|---|
| Arquitectura hexagonal | Separar reglas de negocio de infraestructura |
| Kafka para ingestión | Desacoplar productores y consumidores |
| PostgreSQL como fuente de verdad | Persistir de forma transaccional el estado de delivery |
| Delivery asíncrono | Evitar que la generación del evento dependa de la disponibilidad del webhook |
| Claim + lease + fencing | Controlar concurrencia y recuperar trabajo ante fallos |
| Retry con backoff | Recuperar errores transitorios sin perder notificaciones |
| API Key + scoping por cliente | Aislar los recursos entre clientes |
| HTTPS + HMAC | Proteger y autenticar las notificaciones entregadas |
| Micrometer + Prometheus | Exponer señales operacionales en tiempo cercano al real |

Para el razonamiento completo de cada decisión, consultar:

`docs/architecture/decisions/`

---

# 13. Pruebas y validación

La solución cuenta con pruebas unitarias e integración contra PostgreSQL real.

Se validaron, entre otros:

- ingestión de eventos;
- creación de notifications;
- idempotencia;
- clientes sin suscripción;
- delivery exitoso;
- errores y reintentos;
- concurrencia entre workers;
- recuperación de leases;
- replay de notificaciones fallidas;
- aislamiento entre clientes;
- protección SSRF;
- exposición de métricas;
- consumo de eventos desde Kafka.

También se verificó el flujo completo:

**Kafka → PostgreSQL → Delivery Worker → Webhook**

con una notificación que terminó en `COMPLETED` después de recibir una respuesta HTTP `200` del webhook.

---

# 14. Trade-offs

La solución prioriza simplicidad operacional y separación de responsabilidades frente a introducir infraestructura adicional innecesaria para el alcance del challenge.

### Aplicación Spring Boot única

Los diferentes adapters y casos de uso se ejecutan dentro del mismo proceso. Esto simplifica el despliegue manteniendo separación lógica mediante arquitectura hexagonal.

### PostgreSQL

Se utiliza como fuente de verdad del estado de delivery. Esto permite realizar operaciones transaccionales de claim, lease y actualización de estado.

### Kafka

Se utiliza para desacoplar la ingestión del procesamiento. El servicio no necesita depender de Kafka para ejecutar el self-service API ni para consultar el estado persistido.

### Prometheus

Se expone el endpoint de métricas, pero la infraestructura de monitoreo externa no forma parte del servicio. Esto permite que el equipo de monitoreo integre las métricas con su stack existente.

---

# 15. Estructura de documentación

La documentación arquitectónica se complementa con:

- `docs/architecture/diagrams/` — diagramas C4 y de secuencia.
- `docs/architecture/decisions/` — ADRs con las decisiones arquitectónicas y su razonamiento.
- `docs/ai/` — documentación del uso de herramientas de IA durante el desarrollo.

Los diagramas muestran **qué es el sistema y cómo fluye el trabajo**; los ADR explican **por qué se tomaron las decisiones arquitectónicas**.
