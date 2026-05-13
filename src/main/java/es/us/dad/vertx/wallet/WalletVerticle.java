package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import java.util.Random;

public class WalletVerticle extends AbstractVerticle {

    private String identity;
    private Wallet myWallet; // ⬅️ Usamos nuestra nueva clase criptográfica

    @Override
    public void start() {
        // Módulo 9: Punto 9 - Identidad sin UUID aleatorio (hash de la dirección pública)
        String address = new Wallet().getAddress();
        this.identity = "Wallet-" + address.substring(0, 8);

        // Instanciamos la Wallet (generará sus claves automáticamente)
        this.myWallet = new Wallet();

        System.out.println("💰 " + this.identity + " iniciada.");
        System.out.println("🔑 Mi dirección pública: " + myWallet.getAddress().substring(0, 20) + "...");

        vertx.setPeriodic(5000, id -> generateAndBroadcastTransaction());
    }

    private void generateAndBroadcastTransaction() {
        try {
            // Módulo 9: Punto 9 - Usar TransactionBuilder directamente (patrón puro)
            // Invocación ordenada: from().to().amount().fee().build()
            String receiver = "Receiver-" + new Random().nextInt(1000);
            long amount = 5 + new Random().nextInt(20);
            long fee = 1; // Comisión para el minero

            // PATRÓN BUILDER ENCADENABLE
            Transaction tx = new TransactionBuilder()
                    .from(myWallet.getAddress())
                    .to(receiver)
                    .amount(amount)
                    .fee(fee)
                    .build(myWallet.getPrivateKey());

            System.out.println("💸 " + this.identity + " generando TX firmada: " + tx.getTransactionId().substring(0, 8) + "...");

            // Módulo 9: Punto 7 - Usar toNetworkJson() para serialización estandarizada
            JsonObject transactionData = tx.toNetworkJson();

            // Módulo 9: Punto 10 - Un solo canal de EventBus para el Builder
            // El Módulo 6 (Minero) es responsable de aceptar o rechazar
            vertx.eventBus().publish(BusAddresses.NEW_TRANSACTION, transactionData);

            // Opcionalmente, difundir por red P2P (la red decide si propagar)
            JsonObject p2pMessage = new JsonObject()
                    .put("type", "TRANSACTION")
                    .put("hash", tx.getTransactionId())
                    .put("data", transactionData);

            vertx.eventBus().publish(BusAddresses.BROADCAST_REQUEST, p2pMessage);

        } catch (Exception e) {
            System.err.println("❌ Error generando transacción: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
