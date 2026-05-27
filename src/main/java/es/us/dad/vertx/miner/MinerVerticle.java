package es.us.dad.vertx.miner;

import es.us.dad.vertx.entities.*;
import es.us.dad.vertx.network.BusAddresses;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class MinerVerticle extends AbstractVerticle {

    // El Minero necesita mantener el estado de la cadena
    private BlockChain blockchain;

    // 1. LA MEMPOOL: Sala de espera de transacciones
    private List<Transaction> transactionPool = new ArrayList<>();

    // Configuración: ¿Cuántas tx necesito para minar un bloque?
    private static final int BLOCK_SIZE = 3;

    // Cerrojo para evitar que se inicie más de un proceso de minado a la vez
    private boolean isMining = false;
    private TransactionValidator transactionValidator;

    public MinerVerticle(BlockChain blockChain, TransactionValidator transactionValidator) {
        this.blockchain = blockChain;
        this.transactionValidator = transactionValidator;
    }
    @Override
    public void start() {
        // Inicializamos la blockchain (esto crea el bloque Génesis internamente)
        //this.blockchain = new BlockChain();

        // ---------------------------------------------------------
        // 1. Escuchar bloques que vienen de INTERNET (P2P)
        // ---------------------------------------------------------
        vertx.eventBus().consumer(BusAddresses.BLOCK_ACCEPTED, msg -> {
            try {
                JsonObject blockJson = (JsonObject) msg.body();
                Block receivedBlock = new Block(blockJson);
                System.out.println("📦 Bloque recibido de la red: " + receivedBlock.getHash());
                //blockchain.addBlock(receivedBlock);
                transactionPool.clear(); // ← limpiar tras añadir
                isMining = false;        // ← cancelar carrera
                //System.out.println("✅ Bloque #" + receivedBlock.getHeader().getIndex() + " añadido a la cadena.");
            } catch (Exception e) {
                System.err.println("❌ Error procesando bloque entrante: " + e.getMessage());
                // No relanzar — nunca dejar escapar excepciones de un consumer
            }
        });

        // Tanto si vienen de fuera (INCOMING) como de dentro (NEW), van a la pool.
        vertx.eventBus().consumer(BusAddresses.INCOMING_TRANSACTION, msg -> {
            addTransactionToPool((JsonObject) msg.body());
        });

        vertx.eventBus().consumer(BusAddresses.NEW_TRANSACTION, msg -> {
            addTransactionToPool((JsonObject) msg.body());
        });
    }

    private void addTransactionToPool(JsonObject txJson) {
        Transaction tx = new Transaction(txJson);

        // 🛡️ BARRERA CRIPTOGRÁFICA
//        if (!tx.verifySignature() || !tx.getTransactionId().equals(tx.calculateHash())) {
//            System.err.println("🚨 HACKER DETECTADO: Firma inválida en la TX " + tx.getTransactionId());
//            return; // Descartamos la transacción inmediatamente
//        }
        if (!transactionValidator.validateForMempool(tx)) {
            System.err.println("🚨 Transacción inválida o sin fondos rechazada de la Mempool: " + tx.getTransactionId());
            return; // Descartamos la transacción inmediatamente
        }

        // Evitar duplicados simples
        if (transactionPool.stream().anyMatch(t -> t.getTransactionId().equals(tx.getTransactionId()))) {
            return;
        }

        transactionPool.add(tx);
        System.out.println("📥 TX válida añadida a Mempool. Total: " + transactionPool.size() + "/" + BLOCK_SIZE);

        if (transactionPool.size() >= BLOCK_SIZE && !isMining) {
            mineBlock();
        }
    }

    private void mineBlock() {
        System.out.println("⛏️ ¡Mempool llena! Iniciando minado...");

        // Activamos el cerrojo
        isMining = true;

        // A. Cogemos las 3 primeras transacciones de la pool
        // (En un caso real, seleccionaríamos las que pagan más fee)
        int limit = Math.min(BLOCK_SIZE, transactionPool.size());
        List<Transaction> transactionsForBlock = new ArrayList<>(transactionPool.subList(0, limit));

        // Al limpiar la sublista, desaparecen de la transactionPool original
        transactionPool.subList(0, limit).clear();

        Body body = new Body(transactionsForBlock);

        // B. Creamos la plantilla del bloque
        // Asumiendo que tu Blockchain tiene este método
        Block newBlock = blockchain.createNextBlock(body);

        // 2. MINADO ASÍNCRONO (La magia de Vert.x)
        // Mandamos el trabajo pesado a un hilo secundario (Worker Thread)
        vertx.executeBlocking(() -> {
            mineBlockPoW(newBlock);
            return newBlock;
        }).onComplete(res -> {
            if (res.succeeded()) {
                Block minedBlock = (Block) res.result();
                System.out.println("✅ ¡BLOQUE MINADO! Hash: " + minedBlock.getHash());

                try {
                    // Intentamos añadir a la cadena y difundir
                    //blockchain.addBlock(minedBlock);

                    System.out.println("📤 Enviando bloque minado al Validador...");
                    vertx.eventBus().publish(BusAddresses.INCOMING_BLOCK, minedBlock.toJson());

                } catch (RuntimeException e) {
                    // ¡HEMOS PERDIDO LA CARRERA!
                    System.err.println("⚠️ Bloque descartado (Stale Block): Alguien minó más rápido que nosotros.");
                    System.err.println("Motivo: " + e.getMessage());
                    // En una blockchain real, devolveríamos las TXs a la Mempool si no están
                    // ya incluidas en el bloque ganador. Por simplicidad ahora, lo dejamos pasar.

                } finally {
                    // 🛑 ¡VITAL! PASE LO QUE PASE, QUITAMOS EL CERROJO
                    isMining = false;

                    // Comprobamos si hay más transacciones esperando
                    if (transactionPool.size() >= BLOCK_SIZE) {
                        mineBlock();
                    }
                }
            }
        });
    }

    /**
     * Método auxiliar simple para realizar la Prueba de Trabajo (PoW).
     */
    private void mineBlockPoW(Block block) {
        int difficulty = 4;
        String target = new String(new char[difficulty]).replace('\0', '0');
        String hash;
        do {
            block.getHeader().setNonce(block.getHeader().getNonce() + 1);
            hash = block.calculateHash();
        } while (!hash.startsWith(target));

        // ✅ Guardar el hash final explícitamente (calculateHash ya no tiene side effects)
        block.setHash(hash);
    }
}