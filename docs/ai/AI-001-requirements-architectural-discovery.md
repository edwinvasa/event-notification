# AI-001: Requirements & Architectural Discovery

## Herramienta

Claude Code

## Propósito

Revisar críticamente mi interpretación inicial del challenge y detectar
requisitos, riesgos y decisiones que pudiera estar pasando por alto.

## Prompt

quiero que me ayudes a analizar este challenge como si fueras un senior software architect.
ya leí el documento y tengo algunas ideas iniciales sobre cómo podría resolverlo pero todavia estoy en la etapa de análisis y no quiero empezar a programar antes de tener más claras las decisiones importantes.
por ahora no quiero que escribas código ni que modifiques el proyecto pues primero quiero validar si estoy entendiendo correctamente el problema y saber que cosas importantes podría estar ignorando.
por lo que entiendo, Cobre tiene una plataforma basada en microservicios y eventos, y este nuevo servicio tendría que recibir eventos, revisar si existe una suscripción para este evento y cliente y despues enviar una notificación através de un webhook.
tambien entiendo que debemos guardar información sobre la entrega y permitir que los clientes consulten sus notificaciones mediante varios endpoints como:
GET /notification_events
GET /notification_events/{notification_event_id}
POST /notification_events/{notification_event_id}/replay

tambien debo manejar retries, tener buena observabilidad y pensar en escalabilidad y resiliencia.
tengo algunas dudas e ideas que todavía no sé si son las mejores:
-estoy pensando en kafka para recibir los eventos porque permitiría mantener los mensajes y procesarlos despues de una caida del servicio.
-no tengo claro si despues de kafka necesitaré otra queue o si afka podría ser suficiente.
-estoy considerando usar workers para procesar los webhooks.
-tambien estoy pensando en postgreSQL para guardar el estado de las notificaciones.
-me pregunto si Virtual Threads serían utiles para el envio de webhooks porque es una operación principalmente I/O bounds y entiendo que Cobre prioriza resolver correctamente la alta concurrencia.
-me preocupa que varias instancias del servicio puedan intentar procesar la misma notificación al tiempo.
-estoy pensando en idempotencia y algun mecanismo de lockin o atomic clain para evitar duplicados
-para los errores estoy epnsando en retries con exponential backoff y posiblemente jitter.
-tambien estoy considerando si tendría sentido usar circuit-breaker o rate limiting.

pero ninguna de estas ideas es una decisión todavía.
quiero que seas crítico con ellas y me digas si estoy enfocando bien el problema o si estoy sobrearquitecturando alguna parte.

tambien quiero que revices el challenge que adjunté y me señales:
-que requisitos son explicitos y cuales estoy simplemente asumiendo.
-que conceptos del dominio debería tener claros antes de diseñar.
-que escenarios de fallo debería contemplar
-que problemas de concurrencia, duplicados o recuperación podrían aparecer.
-que preguntas importantes debería resolver antes de elegir tecnologias.
-que partes de mi propuesta cuestionarias y por que
-que cosas importantes crees que todiavia no estoy considerando.

no necesito todavia una arquitectura definitiva ni que elijas las tecnogias por mi.
prefiero que me digas las alternativas y los trade-offs para poder tomar las decisiones despues.

por favor, si detectas que estoy interpretando algo del challenge de forma incorrecta dimelo directamente y explicame por que.

## Principales conclusiones

- Separación entre Task 1 y Task 2.
- Distinción entre Notification y Delivery Attempt.
- Riesgo de duplicados.
- Problema entre webhook delivery y persistencia.
- SSRF.
- BOLA.
- Retry storms.
- Necesidad de analizar con mayor profundidad la estrategia de ingestión de eventos

## Impacto en mi análisis

El análisis me ayudó a identificar varios aspectos que no había
considerado con suficiente profundidad.

En particular, decidí profundizar en la diferencia entre una
notificación y sus intentos individuales de entrega, ya que esta
distinción afecta directamente el manejo de retries, la observabilidad
y el replay.

También identifiqué la concurrencia y la idempotencia como
preocupaciones importantes, especialmente ante escenarios donde un mismo
evento pueda procesarse más de una vez o donde el servicio falle después
de enviar el webhook pero antes de persistir el resultado.

El análisis también permitió identificar preocupaciones de seguridad
como BOLA y SSRF, que deberán considerarse al definir el modelo de
autorización de la API y las validaciones relacionadas con los webhooks.

Finalmente, decidí analizar con mayor profundidad la estrategia de
ingestión de eventos, particularmente los trade-offs entre Kafka y una
queue tradicional, así como el comportamiento esperado ante bursts de
eventos.

## Evidence
- [AI-001 Prompt](screenshots/AI-001-prompt.png)
- [AI-001 Response](screenshots/AI-001-response.png)