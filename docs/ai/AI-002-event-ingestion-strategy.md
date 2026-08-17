# AI-002: Análisis de la estrategia de ingestión de eventos

## Herramienta

Claude Code

## Propósito

Analizar las alternativas para la ingestión y procesamiento de eventos,
especialmente Kafka y una queue tradicional, y evaluar cómo cada
alternativa responde a los requisitos de escalabilidad, resiliencia,
procesamiento asíncrono y manejo de bursts.

También buscaba validar cómo separar la ingestión de eventos del
procesamiento de deliveries y evitar que un volumen elevado de eventos
genere una cantidad excesiva de llamadas simultáneas hacia un mismo
cliente.

## Prompt

quiero profundizar en una de las decisiones que mencionaste: la estrategia de ingestion y procesamiento de eventos.

en mi analisis inicial estaba considerando kafka, pero después de revisar tu respuesta no quiero asumir que kafka sea necesariamente la mejor opción.
quiero comparar estas tres alternativas para este challenge:
-kafka
-una queue tradicional
-usar el json proporcionado como fuente de entrada para la implementación

quiero que las compares especificametne contra los requisitos del challenge considerando:
-escalabilidad
-resiliencia
-recuperación después de una caida
-procesamiento asincrono
-retries
-replay
-backpressure
-concurrencia
-complejidad de implementación
-complejidad operativa
-facilidad para demostrar la solución durante la presentación

y hay un escenario que me interesa especialmente:

supongamos que llegan 300 eventos prácticamente al mismo tiempo.
quiero entender que ocurriría en cada alternativa y como podría evitar que esos 300 eventos se convieran automaticamente en 300 llamadas simultaneas al webhook de un mismo cliente.
tambien quiero saber si tendría sentido utlizar kafka como mecanismo de ingestión y postgresql como fuente de verdad para el estado de las notificaciones sin agregar una segunda tecnologia de mensajería.

por ultimo quiero que cuestiones mi razonamiento y me digas cual alternativa considerarías más adecuada para este challenge y por qué.

## Principales conclusiones

- Kafka y una queue tradicional no resuelven exactamente las mismas
  necesidades que el archivo JSON proporcionado para el challenge.
- El JSON puede utilizarse como un adapter de entrada para la
  implementación, mientras que Kafka puede representar el mecanismo de
  ingestión de la solución de producción.
- Kafka resulta adecuado para el contexto event-driven de la plataforma,
  especialmente por su capacidad para absorber bursts, utilizar
  consumer groups y recuperar el procesamiento mediante offsets.
- El problema de controlar las llamadas simultáneas hacia un mismo
  cliente no lo resuelve por sí sola la tecnología de ingestión.
- Es necesario separar el throughput de ingestión de la concurrencia de
  delivery hacia los clientes.
- PostgreSQL puede actuar como fuente de verdad para el estado de las
  notificaciones, mientras Kafka se utiliza como mecanismo de ingestión.
- No parece necesario introducir una segunda tecnología de mensajería
  únicamente para gestionar los retries, aunque la estrategia concreta
  todavía debe definirse.
- La estrategia de particionamiento de Kafka, el procesamiento de
  deliveries, los retries y el control de concurrencia requieren análisis
  adicional.

## Impacto en mi análisis

A partir de este análisis decidí utilizar Kafka también en la
implementación del proyecto y no únicamente como parte del diseño
conceptual.

También decidí utilizar PostgreSQL como fuente de verdad para el estado
de las notificaciones.

La principal decisión derivada de este análisis fue mantener separadas
las responsabilidades de ingestión y delivery: Kafka debe encargarse de
la recepción y distribución de eventos, mientras que el estado de las
notificaciones y su proceso de entrega deben gestionarse de forma
independiente.

También identifiqué como una preocupación específica el control de la
concurrencia de salida. Recibir un burst de eventos no debería implicar
automáticamente realizar la misma cantidad de requests simultáneos
hacia el webhook de un cliente.

Quedaron pendientes de decisión el modelo exacto de procesamiento entre
Kafka y PostgreSQL, la estrategia de workers, el manejo de retries, el
particionamiento de Kafka y el mecanismo de control de concurrencia.

## Decisión resultante

Se decidió utilizar:

- Kafka como mecanismo de ingestión de eventos.
- PostgreSQL como fuente de verdad del estado de las notificaciones.
- Arquitectura hexagonal para mantener estas tecnologías desacopladas
  del core de la aplicación.

Las decisiones específicas sobre el procesamiento de deliveries y
retries serán documentadas posteriormente mediante ADRs.

## Evidencia

- [Prompt utilizado](screenshots/AI-002-prompt.png)
- [Respuesta obtenida](screenshots/AI-002-response.png)