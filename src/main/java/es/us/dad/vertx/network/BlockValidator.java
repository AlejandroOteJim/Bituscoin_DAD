package es.us.dad.vertx.network;

import es.us.dad.vertx.entities.Block;
import es.us.dad.vertx.entities.BlockChain;
import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.entities.TransactionValidator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockValidator extends AbstractVerticle {

    // Referencia al Módulo 3 (Almacenamiento pasivo)
    private final BlockChain blockChain;

    // PASO 6: Lógica de "Orphan Blocks" (Memoria temporal)
    private final Map<String, Block> orphanBlocks;

    private final TransactionValidator validator;

    // Constructor donde inyectamos la Blockchain local
    public BlockValidator(BlockChain blockChain, TransactionValidator validator) {
        this.blockChain = blockChain;
        this.validator = validator;
        this.orphanBlocks = new HashMap<>();
    }

    @Override
    public void start() {
        // PASO 9: Suscribirse al EventBus en INCOMING_BLOCK
        vertx.eventBus().consumer(BusAddresses.INCOMING_BLOCK, message -> {
            try {
                JsonObject blockJson = (JsonObject) message.body();
                Block incomingBlock = new Block(blockJson);

                // Iniciamos el proceso de aduanas
                processIncomingBlock(incomingBlock);

            } catch (Exception e) {
                System.err.println("❌ ERROR al parsear el bloque entrante: " + e.getMessage());
            }
        });
        System.out.println("🛡️ Block Validator (Agente de Aduanas) desplegado y escuchando en: " + BusAddresses.INCOMING_BLOCK);
    }

    /**
     * Orquestador de la validación. Delega en los métodos estáticos (Paso 1).
     */
    private void processIncomingBlock(Block newBlock) {
        try {
            // Dificultad actual (en un sistema real esto se consultaría a la red, aquí fijamos 4)

            int currentDifficulty = 4;

            // 1. VALIDACIONES MATEMÁTICAS PURAS (No dependen del estado local)
            validatePoW(newBlock, currentDifficulty); // Paso 2
            validateMerkleRoot(newBlock);             // Paso 5
            //validateTransactions(newBlock);           // Pasos 7 y 8

            // 2. VALIDACIONES DE ESTADO (Dependen de nuestra cadena)
            Block lastBlock = blockChain.getLatestBlock();

            // PASO 6: Lógica de Orphan Blocks
            // Si el bloque dice que su padre no es nuestro último bloque...
            if (!newBlock.getHeader().getPreviousHash().equals(lastBlock.getHash())) {

                // Buscamos si el padre existe en algún lugar de nuestra cadena
                boolean parentExistsLocally = blockChain.getChain().stream()
                        .anyMatch(b -> b.getHash().equals(newBlock.getHeader().getPreviousHash()));

                if (!parentExistsLocally) {
                    // El padre no existe. Lo guardamos en cuarentena.
                    orphanBlocks.put(newBlock.getHash(), newBlock);
                    System.out.println("⚠️ WARNING: Bloque #" + newBlock.getHeader().getIndex() + " huérfano. Padre desconocido (" + newBlock.getHeader().getPreviousHash() + "). Guardado temporalmente.");
                    return; // Abortamos el guardado en BD, pero NO es un error de validación.
                }
            }
            // Si llegamos aquí, el padre existe. Validamos enlace y tiempo.
            validateContinuity(newBlock, lastBlock);  // Paso 3
            validateTime(newBlock, lastBlock);        // Paso 4

            // PASO 9b: Invocamos la escritura en el Módulo 3
            blockChain.addBlock(newBlock);
            System.out.println("✅ BLOQUE #"+ newBlock.getHeader().getIndex()+ " VALIDADO" );
            vertx.eventBus().publish(BusAddresses.BLOCK_ACCEPTED, newBlock.toJson());
            // Opcional: Si este bloque era el padre que estábamos esperando, procesar sus huérfanos
            checkAndRescueOrphans(newBlock.getHash());

        } catch (RuntimeException e) {
            // PASO 10: Política estricta de rechazo (al primer fallo aborta y hace log)
            System.err.println("❌ RECHAZADO: " + e.getMessage() + " | Bloque descartado.");
        }
    }

    // --- METODOS DE VALIDACIÓN EXTRAÍDOS ---

    // PASO 2
    private void validatePoW(Block block, int currentDifficulty) {
        if (!block.calculateHash().equals(block.getHash())) {
            throw new RuntimeException("El hash del bloque no es consistente con sus datos.");
        }
        String target = new String(new char[currentDifficulty]).replace('\0', '0');
        if (block.getHeader().getDifficulty() < currentDifficulty || !block.getHash().startsWith(target)) {
            throw new RuntimeException("El hash no cumple la Prueba de Trabajo (PoW).");
        }
    }

    // PASO 3
    private void validateContinuity(Block newBlock, Block lastBlock) {
        if (newBlock.getHeader().getIndex() != lastBlock.getHeader().getIndex() + 1) {
            throw new RuntimeException("El índice no es secuencial.");
        }
        if (!newBlock.getHeader().getPreviousHash().equals(lastBlock.getHash())) {
            throw new RuntimeException("El previousHash no apunta matemáticamente a nuestro último bloque.");
        }
        if (newBlock.getHeader().getIndex() > 0 && !newBlock.getHeader().getPreviousHash().equals(lastBlock.getHash())) {
            throw new RuntimeException("❌ Rechazado: El bloque no apunta al último bloque de la cadena");
        }
    }

    // PASO 4
    private void validateTime(Block newBlock, Block lastBlock) {
        long currentTimestamp = System.currentTimeMillis();
        long twoHoursInMillis = 2 * 60 * 60 * 1000;

        if (newBlock.getHeader().getTimestamp() <= lastBlock.getHeader().getTimestamp()) {
            throw new RuntimeException("El timestamp es anterior o igual al bloque padre.");
        }
        if (newBlock.getHeader().getTimestamp() > (currentTimestamp + twoHoursInMillis)) {
            throw new RuntimeException("El timestamp es inválido (más de 2 horas en el futuro).");
        }
    }

    // PASO 5
    private void validateMerkleRoot(Block block) {
        if (block.getBody() == null || block.getBody().getTransactions().isEmpty()) {
            throw new RuntimeException("El bloque está vacío (sin transacciones).");
        }
        String recalculatedRoot = block.getBody().calculateMerkleRoot();
        if (!recalculatedRoot.equals(block.getHeader().getMerkleRoot())) {
            throw new RuntimeException("El Merkle Root es fraudulento.");
        }
    }

    // PASOS 7 y 8
    private void validateTransactions(Block block) {
        List<Transaction> txs = block.getBody().getTransactions();

        // PASO 7: Verificar que el primer elemento es estrictamente la Coinbase
        Transaction firstTx = txs.get(0);
        if (!"COINBASE_SYSTEM".equals(firstTx.getSender())) {
            throw new RuntimeException("La primera transacción NO es una recompensa Coinbase.");
        }

        // PASO 8: Iterar el resto de transacciones
        for (int i = 1; i < txs.size(); i++) {
            Transaction tx = txs.get(i);

            // 7b: Comprobar que no hay NINGUNA otra Coinbase infiltrada
            if ("COINBASE_SYSTEM".equals(tx.getSender())) {
                throw new RuntimeException("Fraude: Se encontró más de una transacción Coinbase en el bloque.");
            }

            // 8: Delegar la verificación de firmas al Notario (SecurityUtils a través de Transaction)
            if (!validator.validateAuthenticity(tx)) {
                throw new RuntimeException("Firma ECDSA inválida en la transacción: " + tx.getTransactionId());
            }
        }
    }

    // MÉTODO EXTRA: Para "rescatar" bloques huérfanos si acaba de llegar su padre
    private void checkAndRescueOrphans(String newBlockHash) {
        if (orphanBlocks.containsKey(newBlockHash)) {
            Block rescuedBlock = orphanBlocks.remove(newBlockHash);
            System.out.println("🔄 Rescatando bloque huérfano #" + rescuedBlock.getHeader().getIndex() + "...");
            processIncomingBlock(rescuedBlock); // Lo volvemos a procesar, ahora su padre sí existe
        }
    }
}
