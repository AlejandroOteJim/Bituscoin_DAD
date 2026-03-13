package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;

import java.security.PrivateKey;
import java.util.ArrayList;
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
    private double amount; // Cantidad a transferir
    private double fee; // Propina para el minero

    // Inputs UTXO que se consumiran en esta transacción
    private final List<PendingInput> pendingInputs = new ArrayList<>();

    // --- Clase auxiliar para inputs pendientes --------

    private static class PendingInput {
        final String prevTxId;
        final int uotputIndex;
        final long utxoValue;

        PendingInput (String prevTxId, int uotputIndex, long utxoValue) {
            this.prevTxId = prevTxId;
            this.uotputIndex = uotputIndex;
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

    public TransactionBuilder fee(double fee){
        if(fee <= 0){
            throw new IllegalArgumentException("[TransactionBuilder] fee() debe ser un valor positivo y no cero. Recibida: " + fee);
        }
        this.fee = fee;
        return this;
    }

    public TransactionBuilder withInputs(String prevTxId, int uotputIndex, long utxoValue){
        if(prevTxId == null || prevTxId.isBlank()){
            throw new IllegalArgumentException("[TransactionBuilder] withInputs() prevTxId no puede ser nulo o estar vacío");
        }
        if(uotputIndex <= 0){
            throw new IllegalArgumentException("[TransactionBuilder] withInputs() uotputIndex debe ser un valor positivo. Recibida: " + uotputIndex);
        }
        pendingInputs.add(new PendingInput(prevTxId, uotputIndex, utxoValue));
        return this;
    }


    private static void validateAmount(long amount){
        if(amount <= 0){
            throw new IllegalArgumentException("[TransactionBuilder] amount() precisa ser un positivo y no nula. Recibida: " + amount);
        }
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
        return null;
    }

}

