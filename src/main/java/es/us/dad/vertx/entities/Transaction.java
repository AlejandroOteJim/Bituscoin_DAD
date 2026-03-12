package es.us.dad.vertx.entities;

import es.us.dad.vertx.utils.SecurityUtils;
import io.vertx.core.json.JsonObject;

import java.security.PublicKey;

public class Transaction {

    // 1. ATRIBUTOS
    private String transactionId; // El Hash de la transacción (Su DNI único)
    private String sender;        // Clave pública del que paga (o dirección)
    private String receiver;      // Clave pública del que recibe
    private long amount;        // Cantidad
    private long timestamp;       // Momento exacto

    // Este campo lo usaremos en el Laboratorio de Criptografía (Wallet)
    // De momento puede ir vacío o null.
    private String signature;

    // 2. CONSTRUCTORES

    // Constructor vacío: OBLIGATORIO para que Vert.x pueda reconstruir el objeto desde JSON
    public Transaction() {
    }

    public Transaction(JsonObject tx) {
        this.transactionId = tx.getString("transactionId");
        this.sender = tx.getString("sender");
        this.receiver = tx.getString("receiver");
        this.amount = tx.getLong("amount");
        this.timestamp = tx.getLong("timestamp");
        this.signature = tx.getString("signature");
    }

    // Constructor para crear una nueva transacción
    public Transaction(String sender, String receiver, long amount) {
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
        // Calculamos el ID inmediatamente al crearla
        this.transactionId = calculateHash();
    }

    // 3. LÓGICA CORE

    /**
     * Calcula el Hash de la transacción basándose en sus datos.
     * Si alguien cambia 1 céntimo (amount), el ID cambia totalmente.
     */
    public String calculateHash() {
        String dataToHash = sender + receiver + Long.toString(amount) + Long.toString(timestamp);
        return applySha256(dataToHash);
    }

    // SOLUCIÓN: Método de validación criptográfica
    public boolean verifySignature() {
        // Excepción de sistema: Las CoinbaseTransactions no se verifican por ECDSA
        if (this.sender.equals("COINBASE_SYSTEM")) {
            return true;
        }

        if (this.signature == null || this.signature.isEmpty()) {
            return false;
        }

        try {
            PublicKey pubKey = SecurityUtils.decodePublicKey(this.sender);
            byte[] sigBytes = java.util.Base64.getDecoder().decode(this.signature);
            // Comprobamos si el Hash de la TX fue firmado por la clave pública del sender
            return SecurityUtils.verifyECDSASig(pubKey, this.transactionId, sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convierte el objeto a JSON para enviarlo por el EventBus o Red.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("transactionId", this.transactionId)
                .put("sender", this.sender)
                .put("receiver", this.receiver)
                .put("amount", this.amount)
                .put("timestamp", this.timestamp);

        // Si tienes firma, añádela también
        if (this.signature != null) {
            json.put("signature", this.signature);
        }

        return json;
    }

    // Helper estático para SHA-256 (Puedes moverlo a una clase StringUtil si prefieres)
    public static String applySha256(String input) {
        try {
             return SHA256.applySha256(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 4. GETTERS Y SETTERS (Necesarios para Vert.x Mapper)

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    @Override
    public String toString() {
        return String.format("[%s] %s -> %s : %d BTC", transactionId, sender, receiver, amount);
    }
}