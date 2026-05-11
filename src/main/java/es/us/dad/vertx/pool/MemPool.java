package es.us.dad.vertx.pool;

import es.us.dad.vertx.entities.Transaction;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public class MemPool {

    private PriorityQueue<Transaction> transactionPool = new PriorityQueue<>((tx1,tx2) ->{
        return Long.compare(tx1.getTimestamp(),tx2.getTimestamp());
    });

    private ConcurrentHashMap<String, Transaction> txMap = new ConcurrentHashMap<>();

    private static final int BLOCK_SIZE = 3;  //Cantidad de tx de un bloque
    private static final int POOL_SIZE = 5000;
    private static final Long TIME_EXPIRE = 100000000L;


    //Cada vez recibo el nuevo tx
    private void addTransactionToPool(JsonObject txJson) {
        Transaction tx = new Transaction(txJson);
        tx.setTransactionId(tx.calculateHash());

        if (!tx.verifySignature()) {
            System.err.println("🚨 HACKER DETECTADO: Firma inválida en la TX " + tx.getTransactionId());
            return;
        }
        //Evitar duplicar, usando concureentHashMap, O(1)
        if(!txMap.containsKey(tx.getTransactionId())){
            txMap.put(tx.getTransactionId(),tx);
        }else{
            return;
        }

        if(transactionPool.size()>POOL_SIZE){
            System.out.println("Pool lleno, elimino los mas viejos");
            overTx();
        }

        System.out.println("📥 TX válida añadida a Mempool. Total: " + transactionPool.size() + "/" + BLOCK_SIZE);

        if (transactionPool.size() >= BLOCK_SIZE) {
            //Aqui tengo que enviar el mensaje a minero para que empezar a minar

        }
    }

    private List<Transaction> pullTransactions(int limit){
        List<Transaction> transactions = new ArrayList<Transaction>();
        PriorityQueue<Transaction> tempPool = new PriorityQueue<>(transactionPool);
        for(int cont = 0; cont< limit; cont++){
            transactions.add(tempPool.poll());
        }
        return transactions;
    }

    private void purgeComfirmed(List<Transaction> minedTxs){
        for(Transaction tx: minedTxs){
            transactionPool.remove(tx);
            txMap.remove(tx.getTransactionId(),tx);   //verifica si existe, pero creo que no es tan necesario
        }
    }

    private void overTx(){
        int cont = transactionPool.size() - POOL_SIZE;
        for(int i = 0; i< cont; i++){
            if(!transactionPool.isEmpty()) {              //No creo que es necesario, pero si no lo pongo produce un warning
                Transaction tx = transactionPool.peek();
                txMap.remove(tx.getTransactionId(), tx);
                transactionPool.poll();
            }
        }
    }

    private void requesito7(){
        //relacionado con modulo5
    }

    //En lugar de usar esta funcion, debe poner un tempolizador para que esta funcion se usa cada una cierta tiempo
    private void expireTx(){
        while(transactionPool.peek()!=null && transactionPool.peek().getTimestamp()>TIME_EXPIRE) {
            if(!transactionPool.isEmpty()) {              //Lo mismo que anterior,no creo que es necesario, pero si no lo pongo produce un warning
                Transaction tx = transactionPool.peek();
                txMap.remove(tx.getTransactionId(), tx);
                transactionPool.poll();
            }
        }
    }

    private void mempoolStatus(){
        vertx.eventBus().publish(“mempool.status”, bloqueJson);
    }


}
