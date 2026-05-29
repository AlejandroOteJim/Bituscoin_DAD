package es.us.dad.vertx.pool;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.entities.TransactionValidator;
import es.us.dad.vertx.network.BusAddresses;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;


// ✅ Requisito 1: Creo nueva clase propia para pool
public class MempoolManager {

    // ✅ Requisito 2: Uso de priorityQuene en vez de arrayList y defino su comparacion de tiempo timeStamp
    private PriorityQueue<Transaction> transactionPool = new PriorityQueue<>((tx1,tx2) ->{
        return Long.compare(tx1.getTimestamp(),tx2.getTimestamp());
    });

    // ✅ Requisito 3: Uso de concurrentHasnMap para la busqueda O(1)
    private ConcurrentHashMap<String, Transaction> txMap = new ConcurrentHashMap<>();

    private static final int BLOCK_SIZE = 3;  //Cantidad de tx de un bloque
    private static final int POOL_SIZE = 5000;
    private static final Long TIME_EXPIRE = 100000000L;
    private static final Long TIME_PERIOD_CHECK_EXPIRE = 30000L;
    private static final Long TIME_SEND_STATUS = 5000L;
    private Vertx vertx;

    private TransactionValidator validator;

    private int tx_size;

    public MempoolManager(Vertx vertx, TransactionValidator validator) {
        this.vertx = vertx;
        this.validator = validator;
        //Inicializa el cantidad
        memPoolSize();
        //Requesito 8: En lugar de usar esta funcion, debe poner un tempolizador para que esta funcion se usa cada una cierta tiempo
        expireTx();
        //Requesito 9: enviar a modulo 7 la cantidad de tx
        memPoolStatus();
        //Requesito 7: Cuando recibo nuevo tx, envia a modulo 5 para validar
        txListen();
    }



    //Cuando recibo nuevo tx desde INCOMING_TRANSACTION, validar
    private void txListen(){
        System.out.println("Estoy-------------------------------------------------------");
        vertx.eventBus().consumer(BusAddresses.INCOMING_TRANSACTION, msg -> {
            validaTx((JsonObject) msg.body());
        });

        vertx.eventBus().consumer(BusAddresses.NEW_TRANSACTION, msg -> {
            validaTx((JsonObject) msg.body());
        });
        System.out.println("Ya estoy puesta en eventBus para recibir Txs-------------------------------------------------------");
    }

    // Añadir
    private void addTransactionToPool(Transaction tx) {

        //Convertir en tipo Transaction

        tx.setTransactionId(tx.calculateHash());

        /* creo ya no es necesario porque ya ha verificado modulo 5
        if (!tx.verifySignature()) {
            System.err.println("🚨 HACKER DETECTADO: Firma inválida en la TX " + tx.getTransactionId());
            return;
        }
        */

        //Evitar duplicar, usando concureentHashMap, O(1)
        if(!txMap.containsKey(tx.getTransactionId())){
            txMap.put(tx.getTransactionId(),tx);
            transactionPool.add(tx);
        }else{
            return;
        }

        //actualiza tx_size
        memPoolSize();

        System.out.println("📥 TXs válida añadida a Mempool. Total: " + tx_size+ "/" + BLOCK_SIZE);

        //Requesito 6: Pool lleno
        if(tx_size>POOL_SIZE){
            System.out.println("Pool lleno, elimino los mas viejos");
            overTx();
        }

    }

    // ✅ Requesito 4: Sacar los trasactiones sin eliminar desde pool
    public List<Transaction> pullTransactions(int limit){
        List<Transaction> transactions = new ArrayList<Transaction>();
        PriorityQueue<Transaction> tempPool = new PriorityQueue<>(transactionPool);
        for(int cont = 0; cont< limit; cont++){
            if(tempPool.isEmpty()) break;
            transactions.add(tempPool.poll());
        }
        System.out.println("Saco " + transactions.size() + " Txs (limit = " + limit + ")");
        return transactions;
    }

    // ✅ Requesito 5: Cuando recibe la comfirmacion de Modelo 4 y los txs minados, elimino desde pool
    public void purgeComfirmed(List<Transaction> minedTxs){
        for(Transaction tx: minedTxs) {
            if (transactionPool.contains(tx)) {
                transactionPool.remove(tx);
                txMap.remove(tx.getTransactionId(), tx);
                System.out.println("Tx minado: " + tx.getTransactionId());
            }
        }
        memPoolSize();
    }

    // ✅ Requisito 6: eliminar los mas viejo cuando sobre carga pool
    private void overTx(){
        int cont = tx_size - POOL_SIZE;
        if(cont<=0) return;
        for(int i = 0; i< cont; i++){
            Transaction tx = transactionPool.peek();
            txMap.remove(tx.getTransactionId(), tx);
            transactionPool.poll();
        }
        memPoolSize();
    }

    // ✅ Requesito 7: Validar
    private void validaTx(JsonObject txJson){
        Transaction tx = new Transaction(txJson);
        boolean isValid = validator.validateForMempool(tx);
        if(isValid){
            addTransactionToPool(tx);
        }else{
            System.out.println("Error validacion");
        }
    }

    // ✅ Requisito 8: cada un periodo de tiempo, en este caso 30s, se revisa los txs si esta expirado
    private void expireTx(){
        vertx.setPeriodic(TIME_PERIOD_CHECK_EXPIRE, id -> {
            long now = System.currentTimeMillis();
            List<Transaction> expiredTxs = new ArrayList<>();

            // apunta los txs expirados
            for (Transaction tx : transactionPool) {
                if (now - tx.getTimestamp() > TIME_EXPIRE) {
                    expiredTxs.add(tx);
                }
            }
            memPoolSize();
            // elimina
            for (Transaction tx : expiredTxs) {
                transactionPool.remove(tx);
                txMap.remove(tx.getTransactionId());
                System.out.println("TX expirada: " + tx.getTransactionId()+ "...");
            }

        });
    }

    // ✅ Requesito 9: enviar a modulo 7 la cantidad de tx
    private void memPoolStatus(){
        vertx.setPeriodic(TIME_SEND_STATUS, id -> {
            JsonObject status = new JsonObject()
                    .put("size", tx_size)
                    .put("maxSize", POOL_SIZE)
                    .put("txsEachBlock", BLOCK_SIZE)
                    .put("isReady", tx_size >= BLOCK_SIZE);

            vertx.eventBus().publish("mempool.status", status);
        });
    }

    //Actualiza tx_size
    private void memPoolSize(){
        tx_size = transactionPool.size();
        checkTxSize();
    }

    // ✅ requisito 10: aviso a minero cuando hay suficiente txs
    private void checkTxSize(){
        if(tx_size>=BLOCK_SIZE){
            System.out.println("Hay suficientes txs. Aviso a Mineros");
            vertx.eventBus().send("mempool.ready", tx_size);
        }
    }

    public int getSize(){
        return tx_size;
    }

}
