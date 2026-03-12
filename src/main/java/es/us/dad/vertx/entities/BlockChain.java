package es.us.dad.vertx.entities;

import java.util.ArrayList;
import java.util.List;

public class BlockChain {

    // CAMBIO 1: La cadena almacena BLOQUES completos (Header + Body), no solo cabeceras.
    private List<Block> chain;

    // Dificultad actual de la red (puede ser dinámica)
    private int currentDifficulty = 4;

    // Versión actual del software Bituscoin
    public static final int VERSION = 1;


    public BlockChain() {
        this.chain = new ArrayList<>();
        // Creamos el Génesis
        chain.add(createGenesisBlock());
    }

    // 1. OBTENER ÚLTIMO BLOQUE
    public Block getLatestBlock() {
        if (chain.isEmpty()) return null;
        return chain.get(chain.size() - 1);
    }

    // 2. AÑADIR NUEVO BLOQUE
    public void addBlock(Block newBlock) {
        Block previousBlock = getLatestBlock();

        // CASO ESPECIAL: Si es el primer bloque tras el Génesis
        if (previousBlock != null) {

            // 1. VALIDAR ENLACE (Continuidad)
            // El bloque nuevo YA debe traer puesto el hash del anterior.
            if (newBlock.getHeader().getIndex() > 0 && !newBlock.getHeader().getPreviousHash().equals(previousBlock.getHash())) {
                throw new RuntimeException("❌ Rechazado: El bloque no apunta al último bloque de la cadena");
            }

            // 2. VALIDAR ÍNDICE (Orden)
            if (newBlock.getHeader().getIndex() != previousBlock.getHeader().getIndex() + 1) {
                throw new RuntimeException("❌ Rechazado: El índice del bloque es incorrecto");
            }
        }

        // 3. VALIDAR INTEGRIDAD (¿El hash coincide con el contenido?)
        // Calculamos el hash de lo que nos llega y comparamos con lo que dice tener.
        String calculatedHash = newBlock.calculateHash();
        if (!calculatedHash.equals(newBlock.getHash())) {
            throw new RuntimeException("❌ Rechazado: El hash del bloque no es consistente (datos modificados)");
        }

        // 4. VALIDAR DIFICULTAD (Proof of Work)
        // Generamos el string de ceros (ej: "0000")
        String target = new String(new char[this.currentDifficulty]).replace('\0', '0');

        // a) ¿El bloque dice tener la dificultad correcta?
        if (newBlock.getHeader().getDifficulty() < this.currentDifficulty) {
            throw new RuntimeException("❌ Rechazado: Dificultad inferior a la requerida");
        }

        // b) ¿El hash realmente empieza por esos ceros?
        if (!newBlock.getHash().startsWith(target)) {
            throw new RuntimeException("❌ Rechazado: El hash no cumple la prueba de trabajo (No minado)");
        }

        // SI TODO ESTÁ BIEN, LO AÑADIMOS
        System.out.println("✅ Bloque #" + newBlock.getHeader().getIndex() + " añadido a la cadena.");
        this.chain.add(newBlock);
    }

    public Block createNextBlock(Body body) {
        Block previousBlock = getLatestBlock();

        // 1. Preparamos la cabecera con los datos que YA sabemos
        Header nextHeader = new Header();
        nextHeader.setIndex(previousBlock.getHeader().getIndex() + 1);
        nextHeader.setPreviousHash(previousBlock.getHash());
        nextHeader.setTimestamp(System.currentTimeMillis());
        nextHeader.setVersion(VERSION);
        nextHeader.setDifficulty(this.currentDifficulty);
        nextHeader.setNonce(0); // El minero empezará a probar desde aquí

        // 2. Calculamos el Merkle Root de las transacciones
        nextHeader.setMerkleRoot(body.calculateMerkleRoot());

        // 3. Devolvemos el bloque "a medio hacer" para que el Minero lo complete
        return new Block(nextHeader, body);
    }

    // 3. GENERAR GÉNESIS (Adaptado a las nuevas clases)
    public Block createGenesisBlock() {
        // ❌ MAL: Esto crea un hash distinto cada vez que arrancas el programa
        // long timestamp = System.currentTimeMillis();
        // String data = "Genesis " + UUID.randomUUID();

        // ✅ BIEN: Valores FIJOS (Hardcoded)
        // Usamos una fecha congelada en el tiempo
        long index = 0L;
        String previousHash = "0"; // El origen de los tiempos no tiene padre
        long fixedTimestamp = 1700000000000L; // Una fecha congelada (ej: 14/11/2023)
        long nonce = 0L;
        int difficulty = 1; // Dificultad mínima para el génesis

        CoinbaseTransaction coinbaseTransaction = new CoinbaseTransaction("admin", "");
        coinbaseTransaction.setSignature("GENESIS_FIXED_SIGNATURE");
        coinbaseTransaction.setTimestamp(fixedTimestamp);
        coinbaseTransaction.setTransactionId(coinbaseTransaction.calculateHash());

        System.out.println("⛏️ Coinbase values: " + coinbaseTransaction.toString());

        // 2. CREAR EL CUERPO (Body)
        List<Transaction> txs = new ArrayList<>();
        txs.add(coinbaseTransaction);
        Body body = new Body(txs);


        // IMPORTANTE: El nonce y dificultad deben ser fijos también si validas el hash del génesis
        // (Aunque normalmente el génesis se acepta "porque sí" sin validar PoW)
        Header header = new Header(VERSION, index, previousHash, fixedTimestamp, nonce, difficulty);

        // 3. BLOQUE: Construirlo con las partes fijas
        Block genesis = new Block(header, body);

        System.out.println("⛏️ Genesis generado con hash: " + genesis.getHash() + " y merkle root: " + genesis.getHeader().getMerkleRoot());
        System.out.println("⛏️ Coinbase TX ID: " + coinbaseTransaction.getTransactionId());
        return genesis;
    }

    // Pequeño helper para que el Génesis nazca válido
    private static void mineGenesis(Block block, int difficulty) {
        String target = new String(new char[difficulty]).replace('\0', '0');
        while(!block.calculateHash().startsWith(target)) {
            block.getHeader().setNonce(block.getHeader().getNonce() + 1);
        }
    }

    // 4. VALIDACIÓN DE INTEGRIDAD
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current = chain.get(i);
            Block previous = chain.get(i - 1);

            // 1. INTEGRIDAD DE DATOS (¿El hash coincide con el contenido?)
            if (!current.getHash().equals(current.calculateHash())) {
                System.out.println("❌ El bloque " + i + " ha sido modificado.");
                return false;
            }

            // 2. CONTINUIDAD DE LA CADENA (¿Apunta al anterior?)
            if (!current.getHeader().getPreviousHash().equals(previous.getHash())) {
                System.out.println("❌ El bloque " + i + " no apunta al bloque anterior.");
                return false;
            }

            // 3. (NUEVO) VERIFICACIÓN DE LA PRUEBA DE TRABAJO (PoW)
            // Obtenemos la dificultad que DICE tener el bloque
            int difficulty = current.getHeader().getDifficulty();

            // Creamos el string de ceros (ej: "0000")
            String target = new String(new char[difficulty]).replace('\0', '0');

            // Comprobamos si el hash realmente cumple esa dificultad
            if (!current.getHash().startsWith(target)) {
                System.out.println("❌ El bloque " + i + " no ha sido minado correctamente.");
                System.out.println("   Requerido: " + target);
                System.out.println("   Obtenido:  " + current.getHash());
                return false;
            }
        }
        return true;
    }

    public List<Block> getChain() {
        return chain;
    }
}