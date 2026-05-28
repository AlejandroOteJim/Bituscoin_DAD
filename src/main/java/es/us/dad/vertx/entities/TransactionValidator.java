package es.us.dad.vertx.entities;

import es.us.dad.vertx.network.BusAddresses;
import es.us.dad.vertx.utils.SecurityUtils;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TransactionValidator extends AbstractVerticle {

    // =========================================================
    // PUNTO 2 — Estructuras de datos para el estado en memoria
    // =========================================================

    // UTXO Set confirmado
    private final Map<String, TransactionOutput> utxoSet = new HashMap<>();

    // UTXO Set pendiente (mempool)
    private final Map<String, TransactionOutput> pendingUtxoSet = new HashMap<>();

    // IDs ya procesados
    private final Set<String> seenTransactionIds = new HashSet<>();

    // Blockchain compartida con el resto de módulos
    private final BlockChain blockchain;

    // Constructor para tests: estado vacío, sin blockchain
    public TransactionValidator() {
        this.blockchain = null;
    }

    public TransactionValidator(BlockChain blockchain) {
        this.blockchain = blockchain;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        if (blockchain != null) rebuildState(blockchain.getChain());
        System.out.println("📒 [Notario] TransactionValidator desplegado.");
        vertx.eventBus().<JsonObject>consumer(BusAddresses.BLOCK_ACCEPTED, msg -> {
            Block block = new Block(msg.body());
            updateState(block);
        });
        startPromise.complete();
    }


    // =========================================================
    // RECONSTRUCCIÓN DEL ESTADO DESDE BLOCKCHAIN
    // =========================================================

    // Reconstruye el estado UTXO desde cero recorriendo todos los bloques.
    // Se llama automáticamente en start() al arrancar el nodo.
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
        if (isCoinbase(tx)) return true;

        if (tx.getInputs() == null || tx.getInputs().isEmpty()) {
            warn(tx, "TX normal sin inputs");
            return false;
        }

        long totalInput = 0L;
        for (TransactionInput input : tx.getInputs()) {
            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());
            TransactionOutput utxo = utxoSet.get(key);
            if (utxo != null) totalInput += utxo.getAmount();
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

    // Reglas mínimas: amount > 0 y sender != receiver
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

    // Verifica que los outputs no están vacíos y que el transactionId coincide con el hash real.
    public boolean checkIntegrity(Transaction tx) {
        if (tx.getOutputs() == null || tx.getOutputs().isEmpty()) {
            warn(tx, "TX sin outputs");
            return false;
        }

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

    // Verifica criptográficamente la firma ECDSA con la clave pública del sender.
    public boolean validateAuthenticity(Transaction tx) {
        if (isCoinbase(tx)) return true;

        if (tx.getSignature() == null || tx.getSignature().isBlank()) {
            warn(tx, "sin firma");
            return false;
        }
        try {
            PublicKey pubKey = SecurityUtils.decodePublicKey(tx.getSender());
            byte[] sigBytes  = java.util.Base64.getDecoder().decode(tx.getSignature());
            boolean valid    = SecurityUtils.verifyECDSASig(pubKey, tx.getTransactionId(), sigBytes);
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

    // Aplica el bloque confirmado sobre utxoSet y pendingUtxoSet, y registra los IDs.
    public void updateState(Block block) {
        for (Transaction tx : block.getBody().getTransactions()) {
            applyTransactionToUtxoSet(tx, utxoSet);
            applyTransactionToUtxoSet(tx, pendingUtxoSet);
            seenTransactionIds.add(tx.getTransactionId());
        }

        System.out.println("📒 [Notario] Estado actualizado. Bloque #" + block.getHeader().getIndex()
                + " | " + block.getBody().getTransactions().size() + " TX"
                + " | " + utxoSet.size() + " UTXOs sin gastar.");
    }


    // =========================================================
    // PUNTO 8 — Validación para la Mempool
    // =========================================================

    // Igual que validateTransaction() pero sobre el estado pendiente.
    // Detecta double-spend entre TX aún no minadas y reserva los UTXOs consumidos.
    public boolean validateForMempool(Transaction tx) {
        if (!checkFormat(tx))          return false;
        if (!checkIntegrity(tx))       return false;
        if (!validateAuthenticity(tx)) return false;
        if (!checkAntiReplay(tx))      return false;

        if (isCoinbase(tx)) return true;

        List<TransactionInput> inputs = tx.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            warn(tx, "[Mempool] TX normal sin inputs");
            return false;
        }

        long totalInput = 0L;
        Set<String> usedInputs = new HashSet<>();

        // FASE 1 — VALIDAR
        for (TransactionInput input : inputs) {
            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            if (!usedInputs.add(key)) {
                warn(tx, "[Mempool] input duplicado");
                return false;
            }

            TransactionOutput utxo = pendingUtxoSet.get(key);
            if (utxo == null) {
                warn(tx, "[Mempool] UTXO no disponible: " + key);
                return false;
            }
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

        // FASE 2 — APLICAR CAMBIOS
        for (TransactionInput input : inputs) {
            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());
            pendingUtxoSet.remove(key);
        }

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

    // Verifica que cada input referencia un UTXO existente, que el sender es su dueño,
    // que no hay inputs duplicados y que outputs <= inputs.
    public boolean checkUtxoInputs(Transaction tx) {
        if (isCoinbase(tx)) return true;

        List<TransactionInput> inputs = tx.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            warn(tx, "TX normal sin inputs");
            return false;
        }

        long totalInput = 0L;
        Set<String> usedInputs = new HashSet<>();

        for (TransactionInput input : inputs) {
            String key = utxoKey(input.getPreviousTransactionId(), input.getOutputIndex());

            if (!usedInputs.add(key)) {
                warn(tx, "input duplicado");
                return false;
            }

            TransactionOutput utxo = utxoSet.get(key);
            if (utxo == null) {
                warn(tx, "UTXO no disponible: " + key);
                return false;
            }
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

    // Rechaza una TX cuyo ID ya fue procesado anteriormente.
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

    // Pipeline completo de validación. Las coinbase tienen sus propias reglas.
    public boolean validateTransaction(Transaction tx) {
        if (isCoinbase(tx)) {
            if (tx.getInputs() != null && !tx.getInputs().isEmpty()) {
                warn(tx, "coinbase con inputs");
                return false;
            }
            if (tx.getOutputs() == null || tx.getOutputs().isEmpty()) {
                warn(tx, "coinbase sin outputs");
                return false;
            }
            return true;
        }

        if (!checkFormat(tx))          return false;
        if (!checkIntegrity(tx))       return false;
        if (!validateAuthenticity(tx)) return false;
        if (!checkFunds(tx))           return false;
        if (!checkUtxoInputs(tx))      return false;
        if (!checkAntiReplay(tx))      return false;
        return true;
    }


    // =========================================================
    // HELPERS PRIVADOS
    // =========================================================

    // Devuelve true si la TX es una coinbase (creación de moneda nueva).
    private boolean isCoinbase(Transaction tx) {
        return "COINBASE_SYSTEM".equals(tx.getSender());
    }

    // Aplica todas las TX de un bloque confirmado al utxoSet y registra sus IDs.
    private void applyBlockToState(Block block) {
        for (Transaction tx : block.getBody().getTransactions()) {
            applyTransactionToUtxoSet(tx, utxoSet);
            seenTransactionIds.add(tx.getTransactionId());
        }
    }

    // Consume los inputs eliminándolos del mapa y añade los outputs nuevos.
    private void applyTransactionToUtxoSet(Transaction tx, Map<String, TransactionOutput> utxos) {
        if (tx.getInputs() != null) {
            for (TransactionInput input : tx.getInputs()) {
                utxos.remove(utxoKey(input.getPreviousTransactionId(), input.getOutputIndex()));
            }
        }
        List<TransactionOutput> outputs = tx.getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            utxos.put(utxoKey(tx.getTransactionId(), i), outputs.get(i));
        }
    }

    // Clave única de un UTXO: txId:outputIndex
    private String utxoKey(String txId, int outputIndex) {
        return txId + ":" + outputIndex;
    }

    // Log auxiliar de rechazo.
    private void warn(Transaction tx, String motivo) {
        String shortId = tx.getTransactionId() != null
                ? tx.getTransactionId().substring(0, Math.min(12, tx.getTransactionId().length()))
                : "null";
        System.out.println("❌ [Notario] TX " + shortId + "... rechazada: " + motivo);
    }
}
