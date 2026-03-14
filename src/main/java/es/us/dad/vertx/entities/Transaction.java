package es.us.dad.vertx.entities;

import es.us.dad.vertx.utils.SecurityUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

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

    // -- Campos nuevos del modulo 9 ------
    private long fee;

    /*
        Outputs UTXO generados (Punto 6)
        output[0] -> cantidad al receptor
        output[1] -> "change" de vuelta al emisor
     */
    private List<TransactionOutput> outputs = new ArrayList<>();

    /*
        Inputs UTXO consumidos (Punto 6)
        Cada input referencia un al transactionId de una TX anterior y su outputIndex.
     */
    private List<TransactionInput> inputs = new ArrayList<>();

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
        this.fee = tx.getLong("fee");
    }

    // Constructor para crear una nueva transacción
    public Transaction(String sender, String receiver, long amount) {
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
        this.outputs = new ArrayList<>();
        this.inputs = new ArrayList<>();
        // Calculamos el ID inmediatamente al crearla
        this.transactionId = calculateHash();
    }

    // Constructor con fee explícito - usado por TransactionBuilder (Punto 4).
    public Transaction(String sender, String receiver, long amount,long fee) {
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.fee = fee;
        this.timestamp = System.currentTimeMillis();
        this.outputs = new ArrayList<>();
        this.inputs = new ArrayList<>();
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
                .put("fee", this.fee)
                .put("timestamp", this.timestamp);

        // Si tienes firma, añádela también
        if (this.signature != null) {
            json.put("signature", this.signature);
        }

        // Serializar outputs UTXO
        JsonArray outputsArray = new JsonArray();
        for (TransactionOutput out : outputs) {
            outputsArray.add(new JsonObject()
                    .put("address", out.getRecipientAddress())
                    .put("amount",  out.getAmount())
                    .put("spent",   out.isSpent())
            );
        }
        json.put("outputs", outputsArray);

        // Serializar inputs UTXO
        JsonArray inputsArray = new JsonArray();
        for (TransactionInput in : inputs) {
            inputsArray.add(new JsonObject()
                    .put("previousTxId",    in.getPreviousTransactionId())
                    .put("outputIndex",     in.getOutputIndex())
                    .put("unlockingScript", in.getUnlockingScript())
            );
        }
        json.put("inputs", inputsArray);

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

    /** Comisión para el minero (Punto 4) */
    public long getFee()           { return fee;  }
    public void setFee(long fee)   { this.fee = fee; }

    /** Outputs UTXO generados (Punto 6) */
    public List<TransactionOutput> getOutputs() {
        return outputs;
    }
    /** Solo para uso interno por TransactionBuilder */
    public void setOutputs(List<TransactionOutput> outputs) { this.outputs = new ArrayList<>(outputs); }

    /** Inputs UTXO consumidos (Punto 6) */
    public List<TransactionInput> getInputs() {
        return inputs;
    }

    /** Solo para uso interno por TransactionBuilder */
    public void setInputs(List<TransactionInput> inputs) { this.inputs = new ArrayList<>(inputs); }

    @Override
    public String toString() {
        return String.format("[%s] %s -> %s : %d BTC (fee = %d)", transactionId, sender, receiver, amount,fee);
    }
}