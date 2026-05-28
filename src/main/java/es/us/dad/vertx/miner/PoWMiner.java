package es.us.dad.vertx.miner;

import es.us.dad.vertx.entities.Block;
import java.util.concurrent.atomic.AtomicBoolean;

public class PoWMiner {
    public void mine(Block block, AtomicBoolean isMining) {

        // CAMBIO 6: La dificultad se obtiene del Header del bloque
        int difficulty = block.getHeader().getDifficulty();
        String target = new String(new char[difficulty]).replace('\0', '0');

        String hash = block.calculateHash();

        int iterations = 0;

        // CAMBIO 3: Comprobamos si otro nodo encontró el bloque primero
        while (!hash.startsWith(target) && isMining.get()) {

            block.getHeader().setNonce(block.getHeader().getNonce() + 1);

            // CAMBIO 8: Si el nonce alcanza Long.MAX_VALUE, reseteamos el nonce a 0 y
            // forzamos un nuevo timestamp
            if (block.getHeader().getNonce() == Long.MAX_VALUE) {
                System.out.println("⚠️ Nonce overflow! Reseteando y forzando nuevo timestamp...");
                block.getHeader().setNonce(0);
                block.getHeader().setTimestamp(System.currentTimeMillis());
            }

            hash = block.calculateHash();
            iterations++;

            // CAMBIO 7: Actualización periódica que refresca el timestamp del Header.
            if (iterations % 100_000 == 0) {
                block.getHeader().setTimestamp(System.currentTimeMillis());
            }
        }

        // Guardamos el hash final en el bloque (solo si encontramos uno válido)
        block.setHash(hash);
    }
}
