package es.us.dad.vertx.entities;

import es.us.dad.vertx.utils.SecurityUtils;

import java.security.PublicKey;
import java.util.*;

public class TransactionValidator {

    // ===================================================
    // 2. Estado en memoria (balances)
    // ===================================================
    private Map<String, Long> balances = new HashMap<>();

    // ===================================================
    // 9. Anti-replay (transacciones ya procesadas)
    // ===================================================
    private Set<String> processedTransactions = new HashSet<>();


    // ===================================================
    // 8. Validación para la mempool
    // Tiene en cuenta transacciones pendientes
    // ===================================================
    public boolean validateForMempool(Transaction tx, List<Transaction> mempool) {

        // 9. Anti-replay (bloques confirmados)
        if (processedTransactions.contains(tx.getTransactionId()))
            return false;

        // 9. Anti-replay (duplicados en mempool)
        if (mempool.stream().anyMatch(t -> t.getTransactionId().equals(tx.getTransactionId())))
            return false;

        // 4. Validación de formato
        if (!validateFormat(tx)) return false;

        // 5. Verificación del ID (hash)
        if (!validateTransactionId(tx)) return false;

        // 6. Verificación de firma
        if (!validateAuthenticity(tx)) return false;

        // 3. Comprobación de fondos
        // 8. Considerando mempool
        if (!checkFundsWithMempool(tx, mempool)) return false;

        return true;
    }


    // ===================================================
    // 4. Reglas de formato
    // ===================================================
    private boolean validateFormat(Transaction tx) {

        if (tx.getAmount() <= 0) return false;

        if (tx.getSender().equals(tx.getReceiver())) return false;

        return true;
    }


    // ===================================================
    // 5. Verificación matemática del ID
    // ===================================================
    private boolean validateTransactionId(Transaction tx) {

        String recalculated = tx.calculateHash();

        return recalculated.equals(tx.getTransactionId());
    }


    // ===================================================
    // 1. Verificación de firma
    // 6. Implementación ECDSA en método aislado
    // ===================================================
    public boolean validateAuthenticity(Transaction tx) {

        // Coinbase no se firma
        if (tx.getSender().equals("COINBASE_SYSTEM"))
            return true;

        if (tx.getSignature() == null)
            return false;

        try {

            PublicKey pubKey = SecurityUtils.decodePublicKey(tx.getSender());

            byte[] sigBytes = Base64.getDecoder().decode(tx.getSignature());

            return SecurityUtils.verifyECDSASig(
                    pubKey,
                    tx.getTransactionId(),
                    sigBytes
            );

        } catch (Exception e) {
            return false;
        }
    }


    // ===================================================
    // 3. Comprobación de fondos
    // 8. Considerando mempool
    // ===================================================
    private boolean checkFundsWithMempool(Transaction tx, List<Transaction> mempool) {

        if (tx.getSender().equals("COINBASE_SYSTEM"))
            return true;

        long balance = balances.getOrDefault(tx.getSender(), 0L);

        // Restar transacciones pendientes
        for (Transaction pendingTx : mempool) {
            if (pendingTx.getSender().equals(tx.getSender())) {
                balance -= pendingTx.getAmount();
            }
        }

        return balance >= tx.getAmount();
    }


    // ===================================================
    // 7. Actualizar estado tras bloque confirmado
    // ===================================================
    public void updateState(Block block) {

        for (Transaction tx : block.getBody().getTransactions()) {

            // 9. Marcar como procesada
            processedTransactions.add(tx.getTransactionId());

            // Restar saldo al emisor
            if (!tx.getSender().equals("COINBASE_SYSTEM")) {

                long senderBalance =
                        balances.getOrDefault(tx.getSender(), 0L);

                balances.put(
                        tx.getSender(),
                        senderBalance - tx.getAmount()
                );
            }

            // Sumar saldo al receptor
            long receiverBalance =
                    balances.getOrDefault(tx.getReceiver(), 0L);

            balances.put(
                    tx.getReceiver(),
                    receiverBalance + tx.getAmount()
            );
        }
    }
}