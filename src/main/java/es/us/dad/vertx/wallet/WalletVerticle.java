package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import java.util.Random;
import java.util.UUID;

public class WalletVerticle extends AbstractVerticle {

    private String identity;
    private Wallet myWallet; // ⬅️ Usamos nuestra nueva clase criptográfica

    @Override
    public void start() {
        this.identity = "Wallet-" + UUID.randomUUID().toString().substring(0, 4);

        // Instanciamos la Wallet (generará sus claves automáticamente)
        this.myWallet = new Wallet();

        System.out.println("💰 " + this.identity + " iniciada.");
        System.out.println("🔑 Mi dirección pública: " + myWallet.getAddress().substring(0, 20) + "...");

        vertx.setPeriodic(5000, id -> generateAndBroadcastTransaction());
    }

    private void generateAndBroadcastTransaction() {
        // 1. SOLUCIÓN AL TODO: Crear TX firmada desde la Wallet
        Transaction tx = myWallet.sendFunds("Bob", 10);
        System.out.println("💸 " + this.identity + " generando TX firmada: " + tx.getTransactionId().substring(0,8) + "...");

        // 2. Convertir a JSON
        JsonObject transactionData = tx.toJson();

        // 3. Enviar localmente al minero (EventBus interno)
        vertx.eventBus().publish(BusAddresses.NEW_TRANSACTION, transactionData);

        // 4. Preparar el envoltorio Gossip para el P2PManager y enviarlo a la red
        JsonObject p2pMessage = new JsonObject()
                .put("type", "TRANSACTION")
                .put("hash", tx.getTransactionId())
                .put("data", transactionData);

        vertx.eventBus().publish(BusAddresses.BROADCAST_REQUEST, p2pMessage);
    }
}
