# AI-006: Concurrencia de los workers y llamadas HTTP

## Herramienta

Claude Code

## Propósito

Analizar cómo deberían ejecutarse los workers encargados de entregar las
notificaciones mediante webhooks y determinar cómo controlar la
concurrencia de las llamadas HTTP.

El objetivo fue evaluar el uso de Java Virtual Threads y diferenciar la
concurrencia de ejecución de la concurrencia real de llamadas HTTP hacia
los clientes.

## Prompt

ahora necesito analizar cómo deberían ejecutarse los Delivery Workers y cómo controlar la concurrencia de las llamas HTTP hacia los webhooks.
hasta ahora tengo definido:
kafka -> kafka consumer -> postgresql -> delivery workers -> webhook
y las siguientes decisones:
- kafka como mecanismo de ingestión
- postgresql como fuente de verdad
- separación entre ingestion y delivery
- claim atomico mediante postgresql
- lase para recuperar trabajos abandonados
- Notification + DeliveryAttempt
- retries con exponential backoff, cap y jitter
- idempotencia para evitar efectos duplicados.

ahora quiero analizar la tecnología y estrategia de ejecución de los workes.
estoy considerando utilizar Java Virual Threads porque el trabajo del worker es principalmente I/O-bound, especialmente durante la llamada HTTP al webhook.
pero no quiero asumir que utilizar Virtual Threads significa que automaticamente puedo ejecutar una cantidad ilimitada de llamadas concurrentes.
quiero que analices primero que problema resuelven realmente los virutal threads y cuál no resuelve.
quiero diferenciar claramente entre:
- cantidad de virtual threads
- cantidad de workes
- cantidad d requests HTTP simultaneos
- cantidad de conexiones HTTP disponibles
- concurrencia global del servicio
- concurrencia por cliente
- concurrencia por webhook/host

despues quiero analizar este escenario:
llegan 300 eventos practicamente al mismo tiempo. de esos 300 eventos 250 pertenecen al mismo lciente y su webhook está respondiendo muy lentamente.
quiero saber cómo debería comportarse el sistema para evitar que esos 250 eventos saturen nuestros recursos o al cliente, pero sin bloquear innecesariamente el procesamiento de eventos de otros clientes.
quiero que compares diferentes estrategias, por ejemplo:
- limite global de concurrencia
- limite de concurrencia por cliente
- bulkhead
- rate limiting
- combinación de limite global + limite por cliente

quiero que expliques que mecanismo usarías y por que.
también quiero analizar que ocurre si:
- un cliente tiene su webhook caido
- un cliente responde muy lentamente
- un cliente devuelve constantemente 429
- un cliente devuelve 500 durante varios minutos
- tenemos muchos clientes diferentes funcionando correctamente al mismo tiempo

despues quiero analizar Circuit Breaker
quiero saber:
- si realmente necesitamos uno
- qué problema resolvería que no resolvamos ya con retries y bulkhead
- si debería existir uno global o uno por cliente/webhook
- que ocurre cuando se abre
- cómo interactua con el estado de la notificación
- cómo interactua con los retries y next_attempt_at

quiero también que cuestiones si introducir Circuit Breaker + Bulkhead + virtual theads puede ser sobrearquitectura para este challenge.
quiero una recomendación concreta pero basada en los tread-offs.
tambien quiero que consideres la interacción con postgresql:
- si usamos muchos virtual threads ¿podríamos saturar el connection pool de postgresql?
- ¿que relación debería existir entre la cantidad de workers, conexiones a postgresql y concurrencia HTTP?
- ¿debería el worker mantener una conexión/transacción abierta mientras espera la respuesta del webhook?

quiero que prestes especial atención a esto ultimo porque no quiero repetir el error de mantener una trasacción postgresql abierta durante una llamada HTTP externa.
finalmente, quiero una recomendación sobre la estrategia que usarías para este challenge.
por ahora no quiero código ni que modifiques el proyecto. tampoco quiero analizar todabía el particionamiento de kafka ni detalles de kubernetes o infraestructura de deployment.
quiero concentrarme exclusivamente en:
- virual threads
- workers
- concurrencia HTTP
- limites globales y por cliente
- bulkhead
- circuit breaker
- connection pools
- relación entre estos mecanismos

quiero que seas crítico con mis ideas y que señales cualquier parte que consideres innecesarias o sobrearquitecturada.

## Principales conclusiones

- Los Virtual Threads son adecuados para el trabajo principalmente
  I/O-bound de los workers.
- Los Virtual Threads no deben utilizarse como mecanismo de control de
  concurrencia.
- La cantidad de Virtual Threads y la cantidad de llamadas HTTP
  simultáneas son conceptos diferentes.
- Se utilizará un Virtual Thread por notification reclamada.
- Se utilizará un límite global para controlar la cantidad total de
  llamadas HTTP simultáneas.
- Se utilizará un límite de concurrencia por cliente para evitar que un
  cliente lento o con problemas monopolice los recursos del servicio.
- El límite por cliente implementará el patrón Bulkhead.
- El rate limiting se considera un mecanismo diferente al límite de
  concurrencia y queda fuera del alcance inicial.
- Se considera conveniente un Circuit Breaker por cliente/webhook en el
  diseño de producción.
- La implementación del Circuit Breaker queda como una posible evolución
  posterior de la implementación del challenge.
- El pool de conexiones de PostgreSQL debe dimensionarse de forma
  independiente de la concurrencia HTTP.
- No se debe mantener una conexión ni una transacción de PostgreSQL
  abierta durante la llamada al webhook.
- El claim de una notification y la persistencia del resultado deben
  realizarse mediante transacciones cortas e independientes de la
  llamada HTTP.

## Impacto en mi análisis

Este análisis permitió aclarar que utilizar Virtual Threads no significa
permitir una cantidad ilimitada de llamadas HTTP.

La principal decisión fue separar la capacidad de ejecución de la
política de concurrencia. Los Virtual Threads permiten manejar de forma
eficiente muchas tareas que permanecen bloqueadas esperando operaciones
de I/O, mientras que los límites de concurrencia determinan cuántas
llamadas pueden estar realmente en vuelo.

También decidí utilizar dos niveles de control de concurrencia: un
límite global y un límite por cliente. Esto permite evitar que un cliente
con un webhook lento o caído consuma toda la capacidad disponible y
afecte a los demás clientes.

El análisis también reforzó una decisión importante sobre PostgreSQL:
las transacciones utilizadas para reclamar una notification y guardar
el resultado deben ser cortas. La conexión de base de datos debe
liberarse antes de realizar la llamada HTTP externa.

Finalmente, decidí considerar Circuit Breaker como parte del diseño de
la solución, pero no como una prioridad inicial de implementación. El
flujo central de delivery, retries, concurrencia e idempotencia tiene
mayor prioridad para el alcance del challenge.

## Decisión resultante

Los Delivery Workers utilizarán Java Virtual Threads como mecanismo de
ejecución.

La concurrencia HTTP será controlada mediante:

- un límite global de llamadas simultáneas
- un límite de llamadas simultáneas por cliente.

El límite por cliente será considerado la implementación del patrón
Bulkhead.

El procesamiento seguirá conceptualmente este flujo:

1. Reclamar la notification mediante una transacción corta.
2. Liberar la conexión de PostgreSQL.
3. Adquirir los permisos de concurrencia global y por cliente.
4. Realizar la llamada HTTP al webhook.
5. Liberar los permisos de concurrencia.
6. Persistir el resultado mediante otra transacción corta.

No se mantendrán transacciones ni conexiones de PostgreSQL durante la
espera de la respuesta HTTP.

Circuit Breaker por cliente/webhook será considerado en el diseño, pero
su implementación queda inicialmente fuera del camino crítico del
challenge.

## Evidencia

- [Prompt utilizado](screenshots/AI-006-prompt.png)
- [Respuesta obtenida](screenshots/AI-006-response.png)