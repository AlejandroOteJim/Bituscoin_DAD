package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.entities.TransactionInput;
import es.us.dad.vertx.entities.TransactionOutput;
import es.us.dad.vertx.network.BusAddresses;
import es.us.dad.vertx.utils.SecurityUtils;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class TransactionBuilder {
    // -- Punto 10 --------------------------

    /*
        Canal EventBus al que este Builder emite transacciones

        Coincide con BusAddresses.NEW_TRANSACTION para no romper integridad del proyecto
     */

    public static final String EVENTBUS_CHANNEL = BusAddresses.NEW_TRANSACTION;

    // -- Atributos -------------

    private String sender; // Direccion pública del emisor
    private String receiver; // Direccion pública del receptor
    private long amount; // Cantidad a transferir
    private long fee; // Propina para el minero

    // Inputs UTXO que se consumiran en esta transacción
    private final List<PendingInput> pendingInputs = new ArrayList<>();

    // --- Clase auxiliar para inputs pendientes --------

    private static class PendingInput {
        final String prevTxId;
        final int outputIndex;
        final long utxoValue;

        PendingInput (String prevTxId, int outputIndex, long utxoValue) {
            this.prevTxId = prevTxId;
            this.outputIndex = outputIndex;
            this.utxoValue = utxoValue;
        }
    }

    // --- Punto 1 ------------
    public TransactionBuilder from(String sender){
        if(sender == null || sender.isBlank()){
            throw new IllegalArgumentException("[TransactionBuilder] from() no puede ser nulo o estar vacío");
        }
        this.sender = sender;
        return this;
    }

    public TransactionBuilder to(String receiver){
        if(receiver == null || receiver.isBlank()){
            throw new IllegalArgumentException("[TransactionBuilder] to() no puede ser nulo o estar vacío");
        }
        this.receiver = receiver;
        return this;
    }

    // -- Punto 5: rechaza valores <= 0 antes de firmar
    public TransactionBuilder amount(long amount){
        validateAmount(amount);
        this.amount = amount;
        return this;
    }

    public TransactionBuilder fee(long fee){
        if(fee < 0){
            throw new IllegalArgumentException("[TransactionBuilder] fee() debe ser un valor no negativo. Recibida: " + fee);
        }
        this.fee = fee;
        return this;
    }

    public TransactionBuilder withInputs(String prevTxId, int outputIndex, long utxoValue){
        if(prevTxId == null || prevTxId.isBlank()){
            throw new IllegalArgumentException("[TransactionBuilder] withInputs() prevTxId no puede ser nulo o estar vacío");
        }
        if(outputIndex < 0){
            throw new IllegalArgumentException("[TransactionBuilder] withInputs() outputIndex debe ser >= 0. Recibida: " + outputIndex);
        }
        pendingInputs.add(new PendingInput(prevTxId, outputIndex, utxoValue));
        return this;
    }




    // --- Método principal (Puntos 2,3,5,6,8) ----------

    /*
        Construye y firma la transaccion

        Orden criptográfico garantizado (Punto 3):
            1. Validación preventiva de todos los campos
            2. Crear Transaction con datos crudos -> calculateHash()
            3. SecurityUtils.applyECDSASig(privateKey, transactionId) -> firma
            4. tx.setSignature(Base64(firma))
            5. Construir outputs UTXO (receptor + change si aplica)
            6. Construir inputs UTXO con el script de desbloqueo (la firma)

            @param privateKey Clave privada del emisor - obtenida de Wallet
            @return Transaction inmutable y firmada, lista para toNetworkjson()
     */

    public Transaction build(PrivateKey privateKey){
        // -- 1. Validación preventiva de campos (Punto 5) -----------------
        assertAllFieldsValid();

        // -- 2. Verificar fondos si hay inputs UTXO ---------------
        long totalInputValue = pendingInputs.stream().mapToLong(p -> p.utxoValue).sum();
        long totalRequired = amount + fee;

        if(!pendingInputs.isEmpty() && totalInputValue < totalRequired){
            throw new IllegalArgumentException("[TransactionBuilder] build() fondos insuficientes. Total inputs: " + totalInputValue + " < amount (" + amount + ") + fee(" + fee + ")");
        }

        // -- 3. Crear Transaction base - calculateHash() se invoca en constructor
        // transactionId = SHA-256 hex (Punto 8, ya era así en el proyecto)
        Transaction tx = new Transaction(sender,receiver,amount,fee);

        // -- 4. Firmar el transactionId con SecurityUtils del Modulo 8 (Punto 2) --
        byte[] signatureBytes = SecurityUtils.applyECDSASig(privateKey,tx.getTransactionId());
        String signatureB64 = Base64.getEncoder().encodeToString(signatureBytes);
        tx.setSignature(signatureB64);

        // -- 5. Construir outputs UTXO con lógica de change (Punto 6) -----
        List<TransactionOutput> txOutputs = new ArrayList<>();

        // Output 0: cantidad del receptor
        txOutputs.add(new TransactionOutput(receiver,amount));

        // Output 1 (change): si hay inputs UTXO y sobra dinero, vuelve al emisor
        if (!pendingInputs.isEmpty()) {
            long change = totalInputValue - totalRequired;
            if(change > 0){
                txOutputs.add(new TransactionOutput(sender,change));
                System.out.printf("[TransactionBuilder] Change generado: %d unidades devueltas a %s...%n", change, sender.substring(0,Math.min(16,sender.length())));
            }
        }
        tx.setOutputs(txOutputs);

        // -- 6. Construir inputs UTXO (el unlockingScript es la firma ECDSA) --
        List<TransactionInput> txInputs = new ArrayList<>();
        for(PendingInput pendingInput : pendingInputs){
            txInputs.add(new TransactionInput(pendingInput.prevTxId, pendingInput.outputIndex, signatureB64));
        }
        tx.setInputs(txInputs);

        System.out.printf(
                "[TransactionBuilder] ✔ TX construida → id=%s... | %s... → %s... | amount=%d fee=%d%n", tx.getTransactionId().substring(0, 8),
                sender.substring(0, Math.min(16, sender.length())),
                receiver.substring(0, Math.min(16, receiver.length())),
                amount, fee);

        return tx;
    }

    // -- Metodos privados de la validacion --------
    /*
        Validacion preventiva completa antes de firma (Punto 5).
        Lanza excepciones tipadas para que WalletVerticle las gestione con fail()
    */
    private void assertAllFieldsValid(){
        if (sender == null || sender.isBlank()){
            throw new IllegalStateException("[TransactionBuilder] from() es obligatorio");
        }
        if (receiver == null || receiver.isBlank()){
            throw new IllegalStateException("[TransactionBuilder] to() es obligatorio");
        }
        if (sender.equals(receiver)){
            throw new IllegalStateException("[TransactionBuilder] El emisor y receptor no pueden ser la misma dirección");
        }
        validateAmount(amount);
    }

    /*
        Validacion estática de amount (Punto 5)
        Estatica para poder llamarse tanto en amount() como en assertAllFieldsValid()
     */


    private static void validateAmount(long amount){
        if(amount <= 0){
            throw new IllegalArgumentException("[TransactionBuilder] amount() precisa ser un positivo y no nula. Recibida: " + amount);
        }
    }

}

