# Concurrencia y reintentos

Este diagrama muestra cómo el sistema coordina múltiples workers sobre una misma notificación y cómo recupera el trabajo cuando un worker deja de responder durante su procesamiento.

La vista se centra en tres mecanismos: **claim con concurrencia segura, leases para recuperar trabajo abandonado y fencing para evitar que un worker antiguo sobrescriba el resultado de un worker que tomó posteriormente la notificación**.

```mermaid
sequenceDiagram
    participant A as Worker A
    participant DB as PostgreSQL
    participant B as Worker B
    participant Webhook as Webhook del cliente

    A->>DB: Reclama Notification N1
    Note over DB: SELECT ... FOR UPDATE SKIP LOCKED
    DB-->>A: N1 + lease A

    B->>DB: Intenta reclamar N1
    DB-->>B: N1 no disponible

    A->>Webhook: Entrega N1
    Note over A,Webhook: A tarda más que el lease

    B->>DB: Reclama N1 después de expirar el lease
    DB-->>B: N1 + lease B

    B->>Webhook: Entrega N1
    Webhook-->>B: 200 OK
    B->>DB: COMPLETED (fencing válido)

    Webhook-->>A: Respuesta tardía
    A->>DB: Intenta guardar resultado
    DB-->>A: 0 filas actualizadas

    Note over A,DB: Fencing descarta el resultado obsoleto
```

### ¿Qué comunica esta vista?

El sistema utiliza un mecanismo de **claim + lease** para distribuir las notificaciones entre múltiples workers sin que dos workers procesen simultáneamente la misma notificación.

El `lease` también permite recuperar una notificación cuando el worker que la reclamó deja de responder o permanece procesándola durante más tiempo del permitido.

El mecanismo de **fencing** protege contra respuestas tardías: si un segundo worker recupera la notificación después de expirar el lease del primero, cualquier actualización posterior del worker original es rechazada.

### Decisiones relevantes

- **SKIP LOCKED:** evita que varios workers bloqueados sobre el mismo conjunto de filas reclamen simultáneamente el mismo trabajo.
- **Lease:** permite que otro worker recupere una notificación cuyo procesamiento quedó abandonado.
- **Fencing:** evita que el propietario anterior sobrescriba el estado después de que otro worker haya tomado la notificación.
- **Retry con backoff:** los errores transitorios vuelven a `PENDING` y se programa el siguiente intento sin bloquear el procesamiento de otras notificaciones.