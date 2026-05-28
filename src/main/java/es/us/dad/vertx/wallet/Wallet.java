package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.utils.SecurityUtils;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

public class Wallet {

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public Wallet() {
        KeyPair pair = SecurityUtils.generateECKeyPair();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
    }

    public String getAddress() {
        return SecurityUtils.encodeKey(publicKey);
    }

    // Módulo 9: Punto 2 - Acceso a la clave privada para el KeyManager/Builder
    public PrivateKey getPrivateKey() {
        return this.privateKey;
    }

    // SOLUCIÓN AL TODO: Creación y firma de la transacción (legacy)
    public Transaction sendFunds(String receiver, long amount) {
        // 1. Instanciamos la TX. El constructor ya le asigna Timestamp y Hash inicial.
        Transaction newTx = new Transaction(this.getAddress(), receiver, amount);

        // 2. Firmamos el Hash (ID) con nuestra llave privada
        byte[] signature = SecurityUtils.applyECDSASig(this.privateKey, newTx.getTransactionId());

        // 3. Convertimos los bytes de la firma a String Base64 para poder enviarla en JSON
        newTx.setSignature(Base64.getEncoder().encodeToString(signature));

        return newTx;
    }

    /**
     * Módulo 9: Método que usa TransactionBuilder para crear transacciones.
     * Encadena el patrón Builder: from() -> to() -> amount() -> fee() -> build()
     * @param receiver Dirección pública del receptor
     * @param amount   Cantidad a transferir
     * @param fee      Comisión para el minero (puede ser 0)
     * @return Transaction firmada lista para el EventBus
     */
    public Transaction buildTransaction(String receiver, long amount, long fee) {
        try {
            return new TransactionBuilder()
                    .from(this.getAddress())
                    .to(receiver)
                    .amount(amount)
                    .fee(fee)
                    .build(this.privateKey);
        } catch (Exception e) {
            System.err.println("[Wallet] Error construyendo transacción: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Variante sin fee (fee = 0).
     */
    public Transaction buildTransaction(String receiver, long amount) {
        return buildTransaction(receiver, amount, 0);
    }
}