# Módulo 5 — TransactionValidator (El Notario)

## Descripción

`TransactionValidator` es el componente encargado de validar transacciones antes de que entren en un bloque o en la Mempool. Implementa el modelo **UTXO (Unspent Transaction Output)**, igual que Bitcoin: no hay saldo total por dirección, sino outputs concretos sin gastar.

Extiende `AbstractVerticle` de Vert.x, por lo que se despliega como un verticle independiente y escucha eventos del EventBus.

---

## Estado en memoria

El validador mantiene tres estructuras en memoria:

- **`utxoSet`** — outputs sin gastar de bloques ya confirmados. Clave: `txId:outputIndex`.
- **`pendingUtxoSet`** — igual que `utxoSet` pero incluyendo el efecto de las TX pendientes en Mempool. Permite detectar doble gasto entre TX aún no minadas.
- **`seenTransactionIds`** — IDs de transacciones ya procesadas, para el anti-replay.

---

## Ciclo de vida

Al arrancar el nodo, el `MainVerticle` instancia el validador con la blockchain compartida:

```java
TransactionValidator validador = new TransactionValidator(sharedBlockchain);
vertx.deployVerticle(validador, options);
```

En `start()`, el validador:
1. Reconstruye el estado UTXO recorriendo todos los bloques ya cargados desde disco (`rebuildState`).
2. Se suscribe al EventBus en `BusAddresses.BLOCK_ACCEPTED` para actualizar el estado cada vez que el Módulo 4 confirma un bloque.

---

## Métodos públicos

### Punto 2 — Estado en memoria
Las estructuras `utxoSet`, `pendingUtxoSet` y `seenTransactionIds` se inicializan vacías y se reconstruyen al arrancar.

### Punto 3 — `checkFunds(Transaction tx)`
Comprueba que la suma de los inputs UTXO del sender cubre `amount + fee`. Las coinbase se omiten porque introducen dinero nuevo por diseño.

### Punto 4 — `checkFormat(Transaction tx)`
Verifica que `amount > 0`. No se comprueba `sender != receiver` porque en el modelo UTXO es válido que el cambio vuelva al emisor.

### Punto 5 — `checkIntegrity(Transaction tx)`
Verifica que la TX tiene outputs y que el `transactionId` coincide con el hash SHA-256 recalculado de sus datos. Si alguien modificó `amount`, `sender`, `receiver`, etc., el hash no coincidirá.

### Punto 6 — `validateAuthenticity(Transaction tx)`
Verifica criptográficamente la firma ECDSA con la clave pública del sender. Las coinbase no tienen firma ECDSA y se aceptan por diseño.

### Punto 7 — `updateState(Block block)`
Aplica un bloque confirmado sobre `utxoSet` y `pendingUtxoSet`: consume los inputs (los elimina del mapa) y añade los outputs nuevos. Lo llama automáticamente el consumer del EventBus cuando llega un `BLOCK_ACCEPTED`.

### Punto 8 — `validateForMempool(Transaction tx)`
Igual que `validateTransaction()` pero trabajando sobre `pendingUtxoSet`. Si la TX es válida, reserva los UTXOs consumidos en el set pendiente para que no puedan gastarse de nuevo por otra TX de la Mempool. Opera en dos fases: primero valida, luego aplica los cambios.

### Punto 9 — `checkUtxoInputs(Transaction tx)` y `checkAntiReplay(Transaction tx)`
`checkUtxoInputs` verifica que cada input referencia un UTXO existente en el set confirmado, que el sender es su propietario, que no hay inputs duplicados dentro de la misma TX, y que la suma de outputs no supera la de inputs.

`checkAntiReplay` rechaza cualquier TX cuyo ID ya haya sido procesado anteriormente.

### Punto 10 — `validateTransaction(Transaction tx)`
Pipeline completo de validación para bloques. El Módulo 4 debe llamar a este método por cada TX del bloque entrante; si alguna comprobación falla, el bloque entero debe ser rechazado. Las coinbase tienen sus propias reglas: no pueden tener inputs y deben tener al menos un output.

---

## Modelo UTXO

A diferencia del modelo de saldos tradicional, en UTXO no existe un "saldo de Alice". En su lugar, Alice posee outputs concretos de transacciones anteriores. Para gastar, Alice referencia esos outputs como inputs de su nueva TX y genera outputs nuevos para el receptor y el cambio de vuelta a ella misma si corresponde.

La clave de cada UTXO en el mapa es `txId:outputIndex`, donde `txId` es el ID de la transacción que creó el output y `outputIndex` es su posición dentro de esa TX.