# AI-007: Seguridad y protección contra repetición

## Herramienta

Claude Code

## Propósito

Analizar los aspectos de seguridad del servicio y, en particular, cómo
proteger la API de consulta y el endpoint de replay sin afectar el flujo
principal de entrega de notificaciones.

También quiero revisar si las preocupaciones que tengo sobre el abuso del
endpoint de replay son válidas y cómo debería aislarse ese tráfico del
procesamiento normal de eventos.

## Prompt

quiero analizar ahora la seguridad del servicio y en contrato de comunicación con los webhooks.
hasta ahora tengo definido:
kafka -> kafka consumer -> postgresql -> delivery workers -> webhooks
y he tomado estas decisiones:
- kafka como mecanismo de ingestion
- postgresql como fuente de verdad
- Notification + DeliveryAttempt
- idempotencia para eventos duplicados
- claim atómico y lease para los workers
- retries con exponential backoof, cap y jitter
- virtual threads para la ejecución de los workers
- limite global y limite por cliente para las llamadas HTTP
- bulkhead por cliente
- circuit breaker considerado para el diseño pero no como prioridad inicial de implementación

el challenge tambien exige analizar vulnerabilidades OWASP Top 10 y proponer mitigaciones.
quiero hacer ahora un analisís de seguridad antes de implementar.

1) seguridad de la API
primero quiero analizar la API que expone el servicio:
   GET /notification_events/
   GET /notification_events/{notification_event_id}
   POST /notification_events/{notification_event_id}/replay

quiero determinar cómo debería identificarse y autorizarse al cliente que realiza estas operaciones.
Me preocupa especialmente el escenario en el que un cliente pueda modificar un client_id en un parámetro de la URL o query string para consultar o hacer replay de una notification que pertenece a otro cliente.

analiza este riesgo como posible BOLA/IDOR y dime cuál sería una forma correcta de garantizar que una notification solo pueda ser consultada o modificada por el cliente al que pertenece.
quiero que cuestiones explicitamente si el client_id debería venir:
- como path parameter
- como query parameter
- en un header
- o derivarse de la identidad autenticada

  quiero entender cuál opciones sería más segura y por qué.

2) autenticación y autorización
quiero analizar autenticación y autorización.
el challenge no define todavía exactamente el mecanismo de autenticación, por lo que quiero que distingas claramente:
- qué exige realmente el challenge
- que estoy asumiendo
- que mecanismo podría utilizarse en una implementación real
- que mecanismo sería razonable implementar para el challenge sin introducir infraestructura inencesaria.
- esos request de los clients ¿si deberían consumirlo directamente a este servicio o debería haber otra API expuesta que se encargue de recibirlos y redirigir el request a este servicio de notificaciones?

no quiero agregar OAuth2, JWT, API Gateway u otros componentes simplemente porque sean tecnologías comunes.
quiero entender primero que problema resuelve cada uno y que sería responsabildiad de este microservicio.

3) protección del endpoint replay
queri analizar especialmente el endpoint:
POST /notification_events/{notification_event_id}/replay
he identificado una preocupación adicional. El endpoint permite que un cliente genere nuevamente trabajo de delivery, por lo tanto, aunque el cliente esté autenticado y autorizado, podría realizar una gran cantidad de requests de replay y consumir recursos del servicio.
por ejemplo:
cliente A:
POST /replay
POST /replay
POST /replay
...
quiero analizar este problema como posible unrestricted resource consumption y determinar cómo debería protegerse.
quero comparar:
-rate limiting por cliente
-limite global de requests de replay
-limite de replays concurrentes por cliente
-cuotas
-priorización entre delivery normal y replay
-combinación de varios de estos mecanismos

no quiero asumir que necesitamos todos.
quiero determinar si el tráfico generado por replay debería tener un presupuesto de recursos separados del procesamiento normal originado por kafka.
por ejemplo, quiero analizar este escenario:
- llegan miles de eventos legítimos desde kafka
- simultaneamente un cliente comienza a realizar muchos replays
- los replays generan nuevo trabajo de delivery
- el procesamiento de eventos nuevos no debería quedar bloqueado o degradado significativamente por el trafico de replay

quiero que analices si sería suficiente con limitar los request al endpoint o si tambien deberíamos controlar como el trabajo de replay entra al pipeline de delivery.
también quiero que analices que debería ocurrir si dos requests de replay llegan simultaneamente para la misma notification.
hasta ahora tengo pensado utilizar transición condicional:
FAILED -> PENDING
para permitir que solamente uno de los requests pueda solamente realizar el cambio.
quiero que evalues si esto es suficiente junto con el rate limiting o necesitamos controles adicionales.

importante: no quiero asumir que el rate limiting utilizado para este endppint debe ser el mismo mecanismo que los limites de concurrencia utilizados para las llamadas HTTP hacia los webhooks.

Quiero que anlices ambos problemas por separado.

4) SSRF
despues quiero analizar el webhook de salida. La URL del webhook pertenece a una suscripción del cliente y el servicio la utilizará para realizar requests HTTP. esto me preocua porque significa que el servidor realizará requests hacia una URL potencialmente controlada por un tercero.
quiero analizar en profundidad el riesgo SSRF.
No quiero una respuesta superficial como "Validar qu sea HTTPS".
quiero que analices escenarios como:
- localhost
- 127.0.0.1
- ::1
- RFC1918/private IPs
- link-local addresses
- cloud metadata endpoints
- dominios que resuelven unicamente una IP pública pero despues resuelven una IP privada
- DNS rebinding
- redirects hacia destinos internos
- URLs con formatos o representaciones alternativas de IP

quiero saber que controles serían razonables para este servicio.
también quiero analizar si el cliente HTTP debería:
- seguir redirects
- bloquear redirects
- resolver DNS y valdiar la IP
- permitir solamente HTTPS
- restringir puertos
- mantener un allwlist de dominios
- validar el destino antes de cada conexión

quiero que distinguas entre una mitigación adecuada para producción y una mitigación razonable para este challenge

5) autenticación e integridad de los webhooks
quiero analizar la autenticidad e integridad de los webhooks, estoy considerando enviar un header de firma HMAC para que el cliente pueda verificar que:
1. el request proviene realmente de nuestro servicio
2. el payload no fue alterado

tambien estoy considerando incluir una idempotency key probablemente basada en notification_event_id
quiero que analices:
- si HMAC tiene sentido
- que información debería firmarse
- dónde debería almacenarse el secreto
- cómo evitar replay attacks
- si debemos incluir un timestamps
- cómo debería construirse conceptualmente la firma
- que diferencia hay entre HMAC e idempotency key
- que responsabilidad corresponde al emisor y cual al receptor

No quiero codigo todavía

6) información sensible
los eventos pueden contener información relacionada con pagos, transacciones y otros datos potencialmente sensibles.
quiero determinar:
- que información debería almacenarse en Notification
- que información debería almacenarse en DeliveryAttempt
- que información debería evitarse guardar
- que información debería truncarse
- que información debería evitarse en logs
- cómo evitar que errores HTTP o respuestas de terceros expongan información sensible.

quiero relacionar esto con el riesgo de exposición de información sensible.
también quiero analizar si almacenar headers y cuerpos de respuesta del webhook puede representar un riesgo y que información sería razonable conservar para debugging, retries y auditoria.

7) OWASP top 10
despues quiero identificar las vulnerabilidades OWASP Top 10 más relevantes para este challenge.
no quiero elegir tres 3 vulnerabilidades simplemente porque aparezcan en una lista.
quiero que analices cuáles tienen una relación directa con los requisitos del challenge y cuales puedo demostrar de forma convincente en mi implementación.

quiero especialmente evaluar:
- BOLA/IDOR
- SSRF
- sensitive data exposure
- broken authentication
- injection
- security misconfiguration
- unrestricted resource consumption

si alguna de estas no aplica realmente quiero que me lo digas.

quiero que finalmente me recomiendes las tres vulnerabilidades que debería presentar en la Task 3, explicando:
- por qué aplican
- cuál sería el escenario de ataque
- que parte del sistema está afectada
- cuál sería la mitigación
- cómo podría demostrar la mitigación en el codigo o test

8) alcance de implementación
quiero que seas critico con mis ideas. no quiero implementar controles solamente porque sean buenas practicas generales si no aportan valor real al challenge.

quiero diferenciar entre:
-controles que debería implementar realmente
- controles que debería documentar en el diseño
- controles que podría quedar como evolución futura

tampoco quiero introducir infraestructura innecesaria.
por ahora no quiero código ni modificaciones del proyecto.
quiero concentrarme en:
- autenticación
- autorización
- aislamiento entre clientes
- protección del endpoint de replay
- consumo abusivo de recursos
- SSRF
- seguridad del webhook
- HMAC
- idempotency key
- información sensible
- OWASP top 10

al final quiero una recomendación concreta sobre que controles debería implementar realmente en el challenge y cuales dejaría documentados como evolución futura.

## Principales conclusiones

- El `client_id` utilizado para autorización no debe provenir de un valor
  controlado directamente por el cliente.
- La identidad del cliente debe resolverse a partir de una credencial
  autenticada.
- Los endpoints de consulta y replay deben validar que la notificación
  pertenece al cliente autenticado.
- Para el challenge se puede utilizar una API Key por cliente como mecanismo
  sencillo de autenticación.
- El endpoint de replay debe tener rate limiting por cliente.
- El rate limiting del endpoint no es suficiente para proteger el pipeline
  de entrega, por lo que el procesamiento normal debe tener prioridad sobre
  trabajo generado por replay.
- La transición `FAILED -> PENDING` debe realizarse de forma atómica para
  evitar dos replays simultáneos sobre la misma notificación.
- La URL del webhook introduce un riesgo de SSRF y requiere validación de
  destino antes de realizar la conexión.
- Los redirects automáticos deben deshabilitarse para las llamadas webhook.
- La entrega debe utilizar HMAC para garantizar autenticidad e integridad.
- La idempotency key y HMAC resuelven problemas diferentes y deben utilizarse
  de forma complementaria.
- El payload de las notificaciones debe tratarse como información sensible.
- Los tres riesgos OWASP principales para este challenge serán BOLA/IDOR,
  SSRF y Unrestricted Resource Consumption.

## Impacto en mi análisis

Esta revisión confirmó una preocupación que tenía sobre el endpoint de replay:
aunque las notificaciones normales llegan al sistema desde el flujo de
eventos, un cliente también puede generar trabajo adicional mediante
requests de replay.

Por esta razón, decidí que la protección debe existir en dos niveles.

Primero, el endpoint de replay tendrá un límite de requests por cliente para
evitar un uso excesivo de la API.

Segundo, el trabajo generado mediante replay no debe poder desplazar al
procesamiento normal de nuevos eventos. Para esto se tendrá en cuenta el
origen del trabajo durante el procesamiento de las notificaciones, dando
prioridad a las notificaciones provenientes del flujo normal de eventos.

También decidí que la autorización debe estar basada en la identidad
autenticada del cliente y no en un `client_id` enviado libremente en la
request. Esto será importante para evitar problemas de BOLA/IDOR en los
endpoints de listado, detalle y replay.

El análisis también permitió definir SSRF como uno de los principales riesgos
del servicio, debido a que la URL del webhook será utilizada por el servidor
para realizar requests HTTP hacia infraestructura controlada por terceros.

Finalmente, se decidió utilizar HMAC e idempotency keys en las notificaciones
salientes y tratar el payload como información sensible, evitando exponerlo
innecesariamente en listados y logs.

## Decisiones derivadas

- Autenticación mediante API Key por cliente para la implementación del
  challenge.
- Autorización basada en la identidad autenticada.
- Rate limiting por cliente para `POST /notification_events/{id}/replay`.
- Transición atómica `FAILED -> PENDING`.
- Priorización del procesamiento normal frente al replay.
- Validación SSRF antes de cada entrega.
- No seguir redirects automáticamente.
- HMAC por suscripción.
- Idempotency key por notificación.
- No registrar payloads sensibles en logs.
- BOLA/IDOR, SSRF y Unrestricted Resource Consumption como principales
  vulnerabilidades a presentar en Task 3.

## Evidencia

- [AI-007 Prompt](screenshots/AI-007-prompt.png)
- [AI-007 Response](screenshots/AI-007-response.png)