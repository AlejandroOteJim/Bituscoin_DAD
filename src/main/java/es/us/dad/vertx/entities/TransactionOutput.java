package es.us.dad.vertx.entities;

public class TransactionOutput {

    // Direccion pública (Base64 de clave EC) del propietario de este output
    private final String recipientAddress;

    // Valor de este output en la unidad monetaria de BitusCoin
    private final long amount;

    // true si este output ya fue consumido por una transaccion posterior
    private boolean spent;

    public TransactionOutput(String recipientAddress, long amount,boolean spent) {
        this.recipientAddress = recipientAddress;
        this.amount = amount;
        this.spent = spent;
    }

    public TransactionOutput(String recipientAddress, long amount) {
        this(recipientAddress,amount,false);
    }

    public String getRecipientAddress() {
        return recipientAddress;
    }

    public long getAmount() {
        return amount;
    }

    public boolean isSpent() {
        return spent;
    }

    public void markAsSpent(boolean spent) {
        this.spent = true;
    }

    @Override
    public String toString() {
        int len = Math.min(16, recipientAddress.length());
        return "Output{to=" + recipientAddress.substring(0, len) + "..., amount=" + amount + ", spent=" + spent + "}";
    }
}
