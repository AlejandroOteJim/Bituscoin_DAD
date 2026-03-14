package es.us.dad.vertx.entities;

public class TransactionInput {
    /** transactionId de la transacción anterior cuyo output se consume */
    private final String previousTxId;

    /** Índice del output dentro de esa transacción anterior */
    private final int outputIndex;

    /**
     * Script de desbloqueo: firma ECDSA en Base64 del emisor.
     * Equivale al scriptSig de Bitcoin real.
     */
    private final String unlockingScript;

    public TransactionInput(String previousTxId, int outputIndex, String unlockingScript) {
        this.previousTxId    = previousTxId;
        this.outputIndex     = outputIndex;
        this.unlockingScript = unlockingScript;
    }

    public String getPreviousTransactionId() {
        return previousTxId;
    }

    public int getOutputIndex() {
        return outputIndex;
    }

    public String getUnlockingScript(){
        return unlockingScript;
    }

    @Override
    public String toString() {
        return "Input{prevTx=" + previousTxId.substring(0, Math.min(8, previousTxId.length()))
                + "...[" + outputIndex + "]}";
    }
}
