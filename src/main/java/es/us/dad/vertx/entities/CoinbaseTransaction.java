package es.us.dad.vertx.entities;

/**
 * La CoinbaseTransaction es la transacción que RECOMPENSA al minero.
 * Es la única forma de introducir nuevas monedas en el sistema.
 */
public class CoinbaseTransaction extends Transaction {

    // Valor fijo de recompensa (en Bitcoin esto se reduce a la mitad cada 4 años - Halving)
    private static final long BLOCK_REWARD = 5000000000L;

    // Constructor vacío para Vert.x (necesario para el mapeo JSON)
    public CoinbaseTransaction() {
        super();
        this.setSender("COINBASE_SYSTEM"); // El sender siempre es el sistema
    }

    /**
     * Crea una nueva Coinbase Transaction.
     *
     * @param minerAddress La dirección (Clave Pública) del minero que encontró el bloque.
     * @param extraData    Mensaje opcional (ej: "Mined by DadVertx 2026"). Ayuda a que el hash sea único.
     */
    public CoinbaseTransaction(String minerAddress, String extraData) {
        // 1. Llamamos al constructor padre
        // Sender: "NETWORK" o "COINBASE"
        // Receiver: La wallet del minero
        // Amount: La recompensa fija
        super("COINBASE_SYSTEM", minerAddress, BLOCK_REWARD);

        // 2. Usamos el campo signature para guardar datos arbitrarios
        // En Bitcoin, aquí Satoshi Nakamoto escribió el titular del periódico del día.
        // Esto es vital: asegura que el hash de la tx sea único incluso si el minero
        // mina dos bloques seguidos con la misma dirección.
        this.setSignature(extraData + " | Nonce:" + System.nanoTime());

        // 3. Recalculamos el hash porque hemos cambiado la signature
        this.setTransactionId(calculateHash());
    }

    // Sobrescribimos calculateHash para incluir la 'signature' (extra data) en el hash.
    // En tu clase base Transaction, el calculateHash SOLO usaba sender+receiver+amount+timestamp.
    // Si no hacemos esto, dos coinbase al mismo minero tendrían el mismo ID.
    @Override
    public String calculateHash() {
        String baseData = getSender() + getReceiver() + Long.toString(getAmount()) + Long.toString(getTimestamp());
        // Añadimos la 'signature' (extra data) al hash para garantizar unicidad
        String extra = (getSignature() != null) ? getSignature() : "";
        return applySha256(baseData + extra);
    }

    @Override
    public String toString() {
        return super.toString();
    }
}