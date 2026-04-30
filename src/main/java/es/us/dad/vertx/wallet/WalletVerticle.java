package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;
import es.us.dad.vertx.utils.SecurityUtils;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;

import java.util.Base64;
import java.util.UUID;

public class WalletVerticle extends AbstractVerticle {

    private String identity;
    private Wallet myWallet;

    // Dirección pública codificada en Base64 (usada como campo "sender" en la TX)
    private String myPublicKeyEncoded;

    @Override
    public void start() {
        this.identity = "Wallet-" + UUID.randomUUID().toString().substring(0, 4);

        // CORRECCIÓN PUNTO 1: Leer la contraseña desde variable de entorno.
        // Ejecutar el nodo con: export WALLET_PASSWORD=miContraseña
        // Si no está definida, usamos un valor por defecto de desarrollo.
        String password = System.getenv("WALLET_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.out.println("⚠️  [WalletVerticle] WALLET_PASSWORD no definida. Usando contraseña por defecto (solo para desarrollo).");
            password = "dev-default-password";
        }

        // CORRECCIÓN PUNTO 2: Wallet ahora exige contraseña en el constructor
        this.myWallet = new Wallet(password);

        // Guardamos la clave pública en Base64 para usarla como "sender" en las transacciones.
        // La verificación ECDSA en Transaction.verifySignature() espera este formato.
        this.myPublicKeyEncoded = SecurityUtils.encodeKey(myWallet.getPublicKey());

        System.out.println("💰 " + this.identity + " iniciada.");
        System.out.println("🔑 Mi dirección Hash160: " + myWallet.getAddress());

        vertx.setPeriodic(5000, id -> generateAndBroadcastTransaction());
    }

    private void generateAndBroadcastTransaction() {
        // CORRECCIÓN PUNTO 3: Wallet ya no tiene sendFunds(). Construimos la TX aquí.
        // El Módulo 9 (TransactionBuilder) asumirá esta responsabilidad en el futuro,
        // pero por ahora el Verticle realiza los pasos manualmente de forma correcta.

        // Paso 1: Crear la transacción con la clave pública como sender
        Transaction tx = new Transaction(myPublicKeyEncoded, "Bob", 10);

        // Paso 2: Calcular el hash de los metadatos (es el "documento" a firmar)
        String hashToSign = tx.calculateHash();

        // Paso 3: Pedir al KeyManager (a través de Wallet) que firme los bytes del hash.
        // Se pasan los bytes RAW del String hexadecimal del hash — esto es consistente
        // con cómo Transaction.verifySignature() invoca verifyECDSASig(pubKey, transactionId, sigBytes).
        byte[] signatureBytes = myWallet.signTransaction(hashToSign.getBytes());

        // Paso 4: Codificar la firma en Base64 y asignarla a la transacción
        String signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes);
        tx.setSignature(signatureBase64);
        tx.setTransactionId(hashToSign); // El ID es el hash calculado antes de firmar

        System.out.println("💸 " + this.identity + " TX firmada: " + tx.getTransactionId().substring(0, 8) + "...");

        // Paso 5: Convertir a JSON y publicar
        JsonObject transactionData = tx.toJson();

        // Enviar al minero local
        vertx.eventBus().publish(BusAddresses.NEW_TRANSACTION, transactionData);

        // Preparar el envoltorio Gossip para la red P2P
        JsonObject p2pMessage = new JsonObject()
                .put("type", "TRANSACTION")
                .put("hash", tx.getTransactionId())
                .put("data", transactionData);

        vertx.eventBus().publish(BusAddresses.BROADCAST_REQUEST, p2pMessage);
    }
}