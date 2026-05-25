package es.us.dad.vertx.entities;

import es.us.dad.vertx.utils.SecurityUtils;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TransactionValidator {

    // =========================================================
    // PUNTO 2 — Estructuras de datos para el estado en memoria
    // =========================================================

    // UTXO Set confirmado
    private final Map<String, TransactionOutput> utxoSet = new HashMap<>();

    // UTXO Set pendiente (mempool)
    private final Map<String, TransactionOutput> pendingUtxoSet = new HashMap<>();

    // IDs ya procesados
    private final Set<String> seenTransactionIds = new HashSet<>();


    // =========================================================
    // RECONSTRUCCIÓN DEL ESTADO DESDE BLOCKCHAIN
    // =========================================================

    // Reconstruye completamente el estado UTXO a partir de la blockchain.
    // Debe llamarse una vez al arrancar el nodo.
    public void rebuildState(List<Block> blockchain) {

        utxoSet.clear();

        pendingUtxoSet.clear();

        seenTransactionIds.clear();

        for (Block block : blockchain) {

            applyBlockToState(block);
        }

        pendingUtxoSet.putAll(utxoSet);

        System.out.println("📒 [Notario] Estado reconstruido: " + utxoSet.size() + " UTXOs sin gastar.");
    }


    // =========================================================
    // PUNTO 3 — Fondos suficientes
    // =========================================================

    // Comprueba que la suma de los inputs UTXO cubre amount + fee.
    // Las coinbase se omiten porque crean monedas nuevas.
    public boolean checkFunds(Transaction tx) {

        if ("COINBASE_SYSTEM".equals(tx.getSender())) return true;

        if (tx.getInputs() == null || tx.getInputs().isEmpty()) {

            warn(tx, "TX normal sin inputs");

            return false;
        }

        long totalInput = 0L;

        for (TransactionInput input : tx.getInputs()) {

            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            TransactionOutput utxo = utxoSet.get(key);

            if (utxo != null) {

                totalInput += utxo.getAmount();
            }
        }

        long total = tx.getAmount() + tx.getFee();

        if (totalInput < total) {

            warn(tx, "fondos UTXO insuficientes. Total inputs=" + totalInput + " Necesario=" + total);

            return false;
        }

        return true;
    }


    // =========================================================
    // PUNTO 4 — Reglas de formato
    // =========================================================

    // Reglas mínimas:
    // - amount > 0
    // - sender != receiver
    public boolean checkFormat(Transaction tx) {

        if (tx.getAmount() <= 0) {

            warn(tx, "amount debe ser > 0");

            return false;
        }

        if (tx.getSender().equals(tx.getReceiver())) {

            warn(tx, "sender y receiver son iguales");

            return false;
        }

        return true;
    }


    // =========================================================
    // PUNTO 5 — Integridad del ID
    // =========================================================

    // Recalcula el hash y comprueba que coincide con transactionId.
    public boolean checkIntegrity(Transaction tx) {

        String expected = tx.calculateHash();

        if (!expected.equals(tx.getTransactionId())) {

            warn(tx, "transactionId no coincide con el hash real");

            return false;
        }

        return true;
    }


    // =========================================================
    // PUNTO 6 — Autenticidad criptográfica (ECDSA)
    // =========================================================

    // Verifica criptográficamente la firma ECDSA.
    // La clave pública se obtiene del sender.
    public boolean validateAuthenticity(Transaction tx) {

        // Coinbase no requiere firma
        if ("COINBASE_SYSTEM".equals(tx.getSender())) return true;

        if (tx.getSignature() == null || tx.getSignature().isBlank()) {

            warn(tx, "sin firma");

            return false;
        }

        try {

            PublicKey pubKey = SecurityUtils.decodePublicKey(tx.getSender());

            byte[] sigBytes = java.util.Base64.getDecoder().decode(tx.getSignature());

            boolean valid = SecurityUtils.verifyECDSASig(pubKey, tx.getTransactionId(), sigBytes);

            if (!valid) warn(tx, "firma ECDSA inválida");

            return valid;

        } catch (Exception e) {

            warn(tx, "error verificando firma: " + e.getMessage());

            return false;
        }
    }


    // =========================================================
    // PUNTO 7 — Actualizar estado tras confirmar un bloque
    // =========================================================

    // Consume inputs y añade outputs nuevos al estado confirmado.
    // También actualiza el estado pendiente de mempool.
    public void updateState(Block block) {

        applyBlockToState(block);

        System.out.println("📒 [Notario] Estado actualizado. Bloque #" + block.getHeader().getIndex() + " | " + block.getBody().getTransactions().size() + " TX" + " | " + utxoSet.size() + " UTXOs sin gastar.");
    }


    // =========================================================
    // PUNTO 8 — Validación para la Mempool
    // =========================================================

    // Igual que validateTransaction() pero usando el estado pendiente.
    // Detecta double-spend entre TX aún no minadas.
    public boolean validateForMempool(Transaction tx) {

        if (!checkFormat(tx)) return false;

        if (!checkIntegrity(tx)) return false;

        if (!validateAuthenticity(tx)) return false;

        if (!checkAntiReplay(tx)) return false;

        List<TransactionInput> inputs = tx.getInputs();

        if ("COINBASE_SYSTEM".equals(tx.getSender())) return true;

        if (inputs == null || inputs.isEmpty()) {

            warn(tx, "[Mempool] TX normal sin inputs");

            return false;
        }

        long totalInput = 0L;

        // =====================================================
        // FASE 1 — VALIDAR
        // =====================================================

        for (TransactionInput input : inputs) {

            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            TransactionOutput utxo = pendingUtxoSet.get(key);

            if (utxo == null) {

                warn(tx, "[Mempool] UTXO no disponible: " + key);

                return false;
            }

            // Verificar propietario del UTXO
            if (!utxo.getRecipientAddress().equals(tx.getSender())) {

                warn(tx, "[Mempool] sender no es propietario del UTXO");

                return false;
            }

            totalInput += utxo.getAmount();
        }

        long totalOutput = tx.getOutputs().stream().mapToLong(TransactionOutput::getAmount).sum();

        if (totalOutput > totalInput) {

            warn(tx, "[Mempool] outputs superan inputs UTXO");

            return false;
        }

        // =====================================================
        // FASE 2 — APLICAR CAMBIOS
        // =====================================================

        // Consumir UTXOs pendientes
        for (TransactionInput input : inputs) {

            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            pendingUtxoSet.remove(key);
        }

        // Añadir nuevos outputs
        List<TransactionOutput> outputs = tx.getOutputs();

        for (int i = 0; i < outputs.size(); i++) {

            pendingUtxoSet.put(utxoKey(tx.getTransactionId(), i), outputs.get(i));
        }

        seenTransactionIds.add(tx.getTransactionId());

        System.out.println("✅ [Notario/Mempool] TX aceptada: " + tx.getTransactionId());

        return true;
    }


    // =========================================================
    // PUNTO 9 — Verificación UTXO e anti-replay
    // =========================================================

    // Verifica:
    // - Que cada input referencia un UTXO existente
    // - Que el sender es dueño del UTXO
    // - Que outputs <= inputs
    public boolean checkUtxoInputs(Transaction tx) {

        List<TransactionInput> inputs = tx.getInputs();

        // Coinbase no consume UTXOs
        if ("COINBASE_SYSTEM".equals(tx.getSender())) return true;

        if (inputs == null || inputs.isEmpty()) {

            warn(tx, "TX normal sin inputs");

            return false;
        }

        long totalInput = 0L;

        for (TransactionInput input : inputs) {

            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            TransactionOutput utxo = utxoSet.get(key);

            if (utxo == null) {

                warn(tx, "UTXO no disponible: " + key);

                return false;
            }

            // El sender debe ser propietario del output
            if (!utxo.getRecipientAddress().equals(tx.getSender())) {

                warn(tx, "sender no es propietario del UTXO");

                return false;
            }

            totalInput += utxo.getAmount();
        }

        long totalOutput = tx.getOutputs().stream().mapToLong(TransactionOutput::getAmount).sum();

        if (totalOutput > totalInput) {

            warn(tx, "outputs (" + totalOutput + ") superan inputs (" + totalInput + ")");

            return false;
        }

        return true;
    }


    // Rechaza una TX cuyo ID ya fue procesado anteriormente
    public boolean checkAntiReplay(Transaction tx) {

        if (seenTransactionIds.contains(tx.getTransactionId())) {

            warn(tx, "ID duplicado (anti-replay)");

            return false;
        }

        return true;
    }


    // =========================================================
    // PUNTO 10 — Validación completa para bloques
    // =========================================================

    // Validación conjunta de todos los métodos anteriores.
    public boolean validateTransaction(Transaction tx) {

        if (!checkFormat(tx)) return false;

        if (!checkIntegrity(tx)) return false;

        if (!validateAuthenticity(tx)) return false;

        if (!checkFunds(tx)) return false;

        if (!checkUtxoInputs(tx)) return false;

        if (!checkAntiReplay(tx)) return false;

        return true;
    }


    // =========================================================
    // HELPERS PRIVADOS
    // =========================================================

    // Aplica todas las TX de un bloque confirmado al estado local.
    private void applyBlockToState(Block block) {

        for (Transaction tx : block.getBody().getTransactions()) {

            applyTransactionToUtxoSet(tx, utxoSet);

            applyTransactionToUtxoSet(tx, pendingUtxoSet);

            seenTransactionIds.add(tx.getTransactionId());
        }
    }

    // Consume inputs y crea outputs nuevos dentro de un UTXO Set.
    private void applyTransactionToUtxoSet(Transaction tx, Map<String, TransactionOutput> utxos) {

        // Consumir inputs
        if (tx.getInputs() != null) {

            for (TransactionInput input : tx.getInputs()) {

                String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

                utxos.remove(key);
            }
        }

        // Añadir outputs nuevos
        List<TransactionOutput> outputs = tx.getOutputs();

        for (int i = 0; i < outputs.size(); i++) {

            utxos.put(utxoKey(tx.getTransactionId(), i), outputs.get(i));
        }
    }

    // Clave única de un output UTXO:
    // Formato -> txId:outputIndex
    // txId: id de la transacción referenciada
    // outputIndex: índice correspondiente al output de esa transacción
    private String utxoKey(String txId, int outputIndex) {

        return txId + ":" + outputIndex;
    }

    // Función auxiliar para informar de fallos
    private void warn(Transaction tx, String motivo) {

        String shortId = tx.getTransactionId() != null ? tx.getTransactionId().substring(0, Math.min(12, tx.getTransactionId().length())) : "null";

        System.out.println("❌ [Notario] TX " + shortId + "... rechazada: " + motivo);
    }
}
