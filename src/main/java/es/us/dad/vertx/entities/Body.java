package es.us.dad.vertx.entities;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public class Body {

    private List<Transaction> transactions;

    public Body() {
        this.transactions = new ArrayList<>();
    }

    public Body(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    // Constructor desde JsonObject (Para recibir por EventBus)
    public Body(JsonObject json) {
        this.transactions = new ArrayList<>();
        JsonArray txs = json.getJsonArray("transactions");
        if (txs != null) {
            txs.forEach(tx -> this.transactions.add(new Transaction((JsonObject) tx)));
        }
    }

    public JsonObject toJson() {
        JsonArray txArray = new JsonArray();
        // Transaction tiene su propio toJson()
        transactions.forEach(tx -> txArray.add(tx.toJson()));
        return new JsonObject().put("transactions", txArray);
    }

    public String calculateMerkleRoot() {
        // 1. Caso base: Si no hay transacciones, devolvemos hash vacío o constante
        if (transactions == null || transactions.isEmpty()) {
            return Transaction.applySha256("");
        }

        // 2. Paso previo: Convertimos la lista de Objetos Transaction a lista de Strings (Hashes/IDs)
        List<String> treeLayer = transactions.stream()
                .map(Transaction::getTransactionId)
                .collect(Collectors.toList());

        // 3. El Algoritmo de Merkle Tree real
        int count = treeLayer.size();

        // Mientras no lleguemos a la raíz (tamaño 1)
        while (count > 1) {
            List<String> newLayer = new ArrayList<>();

            for (int i = 0; i < count; i += 2) {
                // Elemento Izquierdo
                String left = treeLayer.get(i);

                // Elemento Derecho: Si es impar (no hay pareja), duplicamos el izquierdo
                String right;
                if (i + 1 < count) {
                    right = treeLayer.get(i + 1);
                } else {
                    right = left; // DUPLICACIÓN (Regla de Bitcoin para nudos impares)
                }

                // Hash(Izquierda + Derecha)
                String combinedHash = Transaction.applySha256(left + right);
                newLayer.add(combinedHash);
            }

            // Subimos un nivel en el árbol
            treeLayer = newLayer;
            count = treeLayer.size();
        }

        // El último elemento restante es la Raíz de Merkle
        return treeLayer.get(0);
    }

    public List<Transaction> getTransactions() { return transactions; }
    public void setTransactions(List<Transaction> transactions) { this.transactions = transactions; }
    public void addTransaction(Transaction tx) { this.transactions.add(tx); }
}