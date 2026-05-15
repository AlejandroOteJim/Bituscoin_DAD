package es.us.dad.vertx.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.vertx.core.Vertx;

public class BlockChain {

    // CAMBIO 1: La cadena almacena BLOQUES completos (Header + Body), no solo cabeceras.
    private List<Block> chain;

    // Dificultad actual de la red (puede ser dinámica)
    private int currentDifficulty = 4;

    // Versión actual del software Bituscoin
    public static final int VERSION = 1;

    // APARTADO 2
    // Dirección donde guardaremos el estado en formato json
    private static final String STORAGE_PATH = "data/blockchain.json";

    // APARTADO 2
    // Motor de Vert.x necesario para acceder al FileSystem de forma asíncrona.
    // Se usa 'transient' para evitar que el serializador intente convertir el motor entero de Vert.x a JSON, lo cual causaría un error fatal.
    private transient Vertx vertx;

    private static HashMap<String, Block> hashes = new HashMap<>();

    public BlockChain() { // nos llama al constructor el miner
        // APARTADO 2
        // Ajustamos el constructor para tener en cuenta el Vertx, la API que nos da acceso al fileSystem
        this.vertx = Vertx.vertx(); // mirar si aquí estás creando una instancia nueva o mirando la que ya tenemos
        this.chain = new ArrayList<>();

        loadChainFromDisk();
    }

    private void loadChainFromDisk() {
        if (!this.vertx.fileSystem().existsBlocking("data")) { // te bloquea el worker verticle del miner
            this.vertx.fileSystem().mkdirsBlocking("data"); // mira si existe y si no la crea
        }

        if (this.vertx.fileSystem().existsBlocking(STORAGE_PATH)) {
            try {
                // leemos el archivo entero de forma síncrona
                io.vertx.core.buffer.Buffer buffer = this.vertx.fileSystem().readFileBlocking(STORAGE_PATH);

                if (buffer.length() > 0) {
                    io.vertx.core.json.JsonArray jsonArray = new io.vertx.core.json.JsonArray(buffer);

                    for (int i = 0; i < jsonArray.size(); i++) { // recorremos la blockchain y lo vamos metiendo en el array
                        io.vertx.core.json.JsonObject blockJson = jsonArray.getJsonObject(i);
                        Block block = new Block(blockJson);
                        this.chain.add(block);
                        this.hashes.put(block.getHash(), block);

                        if(!block.calculateHash().equals(block.getHash())) {
                            System.err.println("ERROR!!!!! HASH CORRUPTO. Detenemos el arranque del nodo.");
                            vertx.close(); // cerramos nuestra instancia de vertx
                            System.exit(1); // detenemos la ejecución
                        }
                    }
                    System.out.println("📦 Blockchain cargada desde disco con éxito: " + this.chain.size() + " bloques.");
                    return;
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error leyendo el archivo, empezaremos desde el génesis: " + e.getMessage());
            }
        }

        // el archivo no existe, está vacío o hubo un error al leer
        System.out.println("❗❗ No se encontró blockchain previa en disco. Generamos bloque génesis");
        this.chain.add(createGenesisBlock());
        Block genesis = this.chain.get(this.chain.size() - 1);
        this.hashes.put(genesis.getHash(), genesis);

        saveChainToDisk();
    }

    // 1. OBTENER ÚLTIMO BLOQUE
    public Block getLatestBlock() {
        if (chain.isEmpty()) return null;
        return chain.get(chain.size() - 1);
    }

    // 2. AÑADIR NUEVO BLOQUE
    public void addBlock(Block newBlock) {
        Block previousBlock = getLatestBlock();

        System.out.println("✅ Bloque #" + newBlock.getHeader().getIndex() + " añadido a la cadena.");
        this.chain.add(newBlock);

        saveChainToDisk(); // Funcion que hace el append

        // System.out.println(getBlockByHash("aakshdfqiuewfsbdjfvbuiaefnd")); prueba búsqueda por índice
        // System.out.println(getBlockByIndex(3)); prueba función búsqueda por índice
        System.out.println(getBlocksFromIndex(1));
    }

    private void saveChainToDisk() {
        io.vertx.core.json.JsonArray jsonArray = new io.vertx.core.json.JsonArray(); // JsonArray nativo de Vert.x

        for (Block block : this.chain) {
            jsonArray.add(block.toJson()); // vamos metiendo cada bloque como json en el array
        }

        // convertimos a buffer para poder meterlo en la función siguiente
        io.vertx.core.buffer.Buffer buffer = io.vertx.core.buffer.Buffer.buffer(jsonArray.encodePrettily());

        this.vertx.fileSystem().writeFile(STORAGE_PATH, buffer)
                .onSuccess(v -> {
                    System.out.println("----> Blockchain serializada y guardada en disco correctamente");
                })
                .onFailure(error -> {
                    System.err.println("!!!!!! Error al guardar la blockchain en disco: " + error.getMessage());
                });
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
        // Para poder validar que el Bloque 1 es real, necesitas el Hash del Bloque 0.
        // Si el nodo te da un Bloque 0 falso, podrá darte un Bloque 1 falso, un Bloque 2 falso...
        // El génesis tiene que estar predefinido (hardcoded)

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
    // ver que el hash del genesis empiece por el número de 0 que diga su dificultad
    private static void mineGenesis(Block block, int difficulty) {
        String target = new String(new char[difficulty]).replace('\0', '0');
        while (!block.calculateHash().startsWith(target)) {
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
            // esto lo dejamos porque sí que tenemos que validar la blockchain como tal,
            // lo que no tenemos que hacer es la validación al añadir el bloque
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

    public Block getBlockByHash(String hash) {
        return hashes.get(hash);
    }

    public Block getBlockByIndex(long index) {
        if (index < 0 || index >= chain.size()) { // si el índice que nos están pidiendo no es válido
            System.out.println("⚠️ Solicitud de bloque fuera de rango: Índice " + index);
            return null;
        }
        return chain.get((int) index);
    }

    public List<Block> getBlocksFromIndex(long startIndex) {
        List<Block> bloques = new ArrayList<>();
        if (startIndex < 0 || startIndex >= chain.size()) {
            System.err.println("Índice inválido");
        } else {
            if(startIndex == this.chain.size() - 1){
                // si nuestro parámetro es el último elemento de la blockchain
                bloques.add(this.chain.get((int)startIndex));
            } else {
                bloques = this.chain.subList((int) startIndex, this.chain.size());
            }
        }
        return bloques;
    }
}