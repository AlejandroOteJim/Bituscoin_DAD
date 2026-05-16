package es.us.dad.vertx.miner;

import es.us.dad.vertx.entities.Block;
import es.us.dad.vertx.entities.BlockChain;
import es.us.dad.vertx.entities.Body;
import es.us.dad.vertx.entities.CoinbaseTransaction;
import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MinerVerticle extends AbstractVerticle {

    // El Minero necesita mantener el estado de la cadena
    private BlockChain blockchain;

    // 1. LA MEMPOOL: Sala de espera de transacciones
    private List<Transaction> transactionPool = new ArrayList<>();

    // Configuración: ¿Cuántas tx necesito para minar un bloque?
    private static final int BLOCK_SIZE = 3;

    // CAMBIO 2: Sustituimos "boolean isMining" por AtomicBoolean.
    private AtomicBoolean isMining = new AtomicBoolean(false);

    // CAMBIO 1: Instancia del worker de Proof-of-Work.
    private PoWMiner poWMiner = new PoWMiner();

    @Override
    public void start() {
        // Inicializamos la blockchain (esto crea el bloque Génesis internamente)
        this.blockchain = new BlockChain();

        // ---------------------------------------------------------
        // 1. Escuchar bloques que vienen de INTERNET (P2P)
        // ---------------------------------------------------------
        vertx.eventBus().consumer(BusAddresses.INCOMING_BLOCK, msg -> {
            try {
                JsonObject blockJson = (JsonObject) msg.body();
                Block receivedBlock = new Block(blockJson);
                System.out.println("📦 Bloque recibido de la red: " + receivedBlock.getHash());

                // CAMBIO 4: poner isMining a false para que el bucle PoW en PoWMiner.mine() se
                // detenga inmediatamente
                isMining.set(false);

                blockchain.addBlock(receivedBlock);
                transactionPool.clear();
                System.out.println("✅ Bloque #" + receivedBlock.getHeader().getIndex() + " añadido a la cadena.");
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
        if (!tx.verifySignature() || !tx.getTransactionId().equals(tx.calculateHash())) {
            System.err.println("🚨 HACKER DETECTADO: Firma inválida en la TX " + tx.getTransactionId());
            return; // Descartamos la transacción inmediatamente
        }

        // Evitar duplicados simples
        if (transactionPool.stream().anyMatch(t -> t.getTransactionId().equals(tx.getTransactionId()))) {
            return;
        }

        transactionPool.add(tx);
        System.out.println("📥 TX válida añadida a Mempool. Total: " + transactionPool.size() + "/" + BLOCK_SIZE);

        // CAMBIO 2: Usamos isMining.get()
        if (transactionPool.size() >= BLOCK_SIZE && !isMining.get()) {
            mineBlock();
        }
    }

    private void mineBlock() {
        System.out.println("⛏️ ¡Mempool llena! Iniciando minado...");

        // CAMBIO 2: Usamos isMining.set(true)
        isMining.set(true);

        // A. Cogemos las 3 primeras transacciones de la pool
        // (En un caso real, seleccionaríamos las que pagan más fee)
        int limit = Math.min(BLOCK_SIZE, transactionPool.size());
        List<Transaction> transactionsForBlock = new ArrayList<>(transactionPool.subList(0, limit));

        // Al limpiar la sublista, desaparecen de la transactionPool original
        transactionPool.subList(0, limit).clear();

        // CAMBIO 5: Creación de la CoinbaseTransaction
        CoinbaseTransaction coinbase = new CoinbaseTransaction("admin", "Mined by Bituscoin");
        transactionsForBlock.add(0, coinbase);
        System.out.println("💰 CoinbaseTransaction generada. Recompensa para: admin");

        Body body = new Body(transactionsForBlock);

        // CAMBIO 6:blockchain.createNextBlock(body) solicita dinámicamente el
        // previousHash y la difficulty actual de la cadena
        Block newBlock = blockchain.createNextBlock(body);

        // CAMBIO 9: Aislamiento de errores en vertx.executeBlocking
        vertx.executeBlocking(() -> {
            try {
                // CAMBIO 1: Delegamos el minado al worker PoWMiner en vez de tener el bucle
                // directamente aquí
                poWMiner.mine(newBlock, isMining);
                return newBlock;
            } catch (Exception e) {
                System.err.println("❌ Error fatal durante el minado: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }).onComplete(res -> {
            if (res.succeeded() && res.result() != null) {
                Block minedBlock = (Block) res.result();

                // Comprobamos si el minado fue cancelado
                if (!isMining.get()) {
                    System.out.println("⚠️ Minado cancelado (otro nodo encontró el bloque primero).");
                    // CAMBIO 10: Reintegramos las TXs a la mempool
                    reintegrateTransactions(transactionsForBlock);
                    return;
                }

                System.out.println("✅ ¡BLOQUE MINADO! Hash: " + minedBlock.getHash());

                try {
                    // Intentamos añadir a la cadena y difundir
                    blockchain.addBlock(minedBlock);
                    vertx.eventBus().publish(BusAddresses.MINED_BLOCK, minedBlock.toJson());

                } catch (RuntimeException e) {
                    // ¡HEMOS PERDIDO LA CARRERA!
                    System.err.println("⚠️ Bloque descartado (Stale Block): Alguien minó más rápido que nosotros.");
                    System.err.println("Motivo: " + e.getMessage());

                    // CAMBIO 10: Reintegrar transacciones a la cola.
                    // Si otro nodo minó el mismo bloque antes, las transacciones que habíamos
                    // sacado de la mempool se devuelven
                    reintegrateTransactions(transactionsForBlock);

                } finally {
                    // 🛑 ¡VITAL! PASE LO QUE PASE, QUITAMOS EL CERROJO
                    // CAMBIO 2: Usamos isMining.set(false)
                    isMining.set(false);

                    // Comprobamos si hay más transacciones esperando
                    if (transactionPool.size() >= BLOCK_SIZE) {
                        mineBlock();
                    }
                }
            } else {
                // CAMBIO 9: Si res.result() es null
                System.err.println("⚠️ Minado fallido o cancelado.");

                // CAMBIO 10: Reintegramos las transacciones en caso de fallo
                reintegrateTransactions(transactionsForBlock);

                // Limpiamos el cerrojo
                isMining.set(false);

                // Reintentamos si hay suficientes transacciones
                if (transactionPool.size() >= BLOCK_SIZE) {
                    mineBlock();
                }
            }
        });
    }

    // CAMBIO 10: Método auxiliar para reintegrar las transacciones a la mempool
    // cuando el minado es interrumpido o el bloque es rechazado.

    private void reintegrateTransactions(List<Transaction> transactionsForBlock) {
        int reintegrated = 0;
        for (Transaction tx : transactionsForBlock) {
            // No reintegramos la CoinbaseTransaction
            if (tx instanceof CoinbaseTransaction) {
                continue;
            }
            // Solo reintegramos si no está ya en la pool
            if (transactionPool.stream().noneMatch(t -> t.getTransactionId().equals(tx.getTransactionId()))) {
                transactionPool.add(tx);
                reintegrated++;
            }
        }
        System.out.println(
                "♻️ Reintegradas " + reintegrated + " transacciones a la mempool. Total: " + transactionPool.size());
    }
}