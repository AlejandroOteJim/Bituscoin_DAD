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

    // SOLUCIÓN AL TODO: Creación y firma de la transacción
    public Transaction sendFunds(String receiver, long amount) {
        // 1. Instanciamos la TX. El constructor ya le asigna Timestamp y Hash inicial.
        Transaction newTx = new Transaction(this.getAddress(), receiver, amount);

        // 2. Firmamos el Hash (ID) con nuestra llave privada
        byte[] signature = SecurityUtils.applyECDSASig(this.privateKey, newTx.getTransactionId());

        // 3. Convertimos los bytes de la firma a String Base64 para poder enviarla en JSON
        newTx.setSignature(Base64.getEncoder().encodeToString(signature));

        return newTx;
    }
}