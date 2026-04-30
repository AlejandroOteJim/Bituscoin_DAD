package es.us.dad.vertx.network;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.*;
import io.vertx.core.parsetools.RecordParser;
import java.util.*;
import io.vertx.core.Promise;

public class P2PConnectionManager extends AbstractVerticle {

    private int listenPort;
    // Lista de sockets activos (nuestros "vecinos")
    private final List<NetSocket> activeSockets = new ArrayList<>();

    // --- GOSSIP SEEN CACHE ---
    // Usamos un Set respaldado por un Mapa LRU (Least Recently Used)
    // Guardará los últimos 1000 mensajes. Si llega el 1001, borra el más viejo.
    private final int CACHE_SIZE = 1000;
    private final Set<String> seenMessagesCache = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CACHE_SIZE;
                }
            }
    );

    @Override
    public void start(Promise<Void> startPromise) {
        // 1. Configuración: Leer puerto y semilla
        this.listenPort = config().getInteger("p2p.port", 6000);
        String seed = config().getString("p2p.seed.ip", ""); // Ej: "localhost:6000"

        // 2. Levantar Servidor (Escucha conexiones entrantes)
        startServer();

        // 3. Conectar a Semilla (Si existe en config)
        if (!seed.isEmpty()) {
            connectToPeer(seed);
        }

        // 4. CONSUMIDORES DEL EVENTBUS (Comunicación Interna -> Externa)

        // A. Peticiones genéricas de difusión (ej: Wallet manda TX ya formateada)
        vertx.eventBus().consumer(BusAddresses.BROADCAST_REQUEST, msg -> {
            broadcastMessage((JsonObject) msg.body());
        });

        // B. Bloque minado localmente (Minero -> Red)
        // El P2PManager actúa como adaptador: recibe el bloque crudo y lo empaqueta
        vertx.eventBus().consumer(BusAddresses.MINED_BLOCK, msg -> {
            JsonObject blockJson = (JsonObject) msg.body();

            // Construimos el mensaje de protocolo P2P
            JsonObject p2pMsg = new JsonObject()
                    .put("type", "BLOCK")
                    // Asumimos que el bloque tiene un hash calculado
                    .put("hash", blockJson.getString("hash"))
                    .put("data", blockJson);

            System.out.println("📢 Minero local encontró bloque " + p2pMsg.getString("hash").substring(0, 6) + "... Difundiendo.");

            // Lo marcamos como visto para no re-procesarlo si nos vuelve
            seenMessagesCache.add(p2pMsg.getString("hash"));

            broadcastMessage(p2pMsg);
        });

        System.out.println("📡 P2P Manager iniciado en puerto " + listenPort);
        startPromise.complete();
    }

    // --- LÓGICA DE SERVIDOR ---
    private void startServer() {
        NetServerOptions options = new NetServerOptions()
                .setTcpKeepAlive(true);
        NetServer server = vertx.createNetServer(options);

        server.connectHandler(socket -> {
            System.out.println("👋 Nueva conexión entrante desde: " + socket.remoteAddress());
            handleSocketConnection(socket);
        });

        server.listen(listenPort).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("✅ Servidor P2P escuchando en puerto " + listenPort);
            } else {
                System.err.println("❌ Error al iniciar servidor P2P: " + res.cause().getMessage());
            }
        });
    }

    // --- LÓGICA DE CLIENTE (BOOTSTRAPPING) ---
    private void connectToPeer(String address) {
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        NetClientOptions options = new NetClientOptions().setTcpKeepAlive(true);
        NetClient client = vertx.createNetClient(options);


        System.out.println("🔌 Intentando conectar a semilla: " + address);

        client.connect(port, host).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("✅ Conectado exitosamente a " + address);
                NetSocket socket = res.result();
                handleSocketConnection(socket);

                // Enviar Handshake inicial al conectar
                sendHandshake(socket);
            } else {
                System.err.println("❌ No se pudo conectar a " + address);
            }
        });
    }

    // --- GESTIÓN DE SOCKETS Y FRAMING ---
    private void handleSocketConnection(NetSocket socket) {
        // Añadir a lista de activos
        activeSockets.add(socket);

        // Gestionar desconexión
        socket.closeHandler(v -> {
            activeSockets.remove(socket);
            System.out.println("❌ Conexión cerrada con " + socket.remoteAddress());
            // Reconexión solo si somos el cliente (tenemos semilla configurada)
            String seed = config().getString("p2p.seed.ip", "");
            if (!seed.isEmpty()) {
                System.out.println("🔄 Reconectando en 3 segundos...");
                vertx.setTimer(3000, id -> connectToPeer(seed));
            }
        });

        socket.exceptionHandler(t -> {
            System.err.println("🔥 ERROR EN SOCKET " + socket.remoteAddress() + ": " + t.getMessage());
            t.printStackTrace();
        });

        // --- MÁQUINA DE ESTADOS PARA FRAMING (Length-Prefix) ---
        // Estado inicial: Esperar 4 bytes (Entero = Longitud)
        RecordParser parser = RecordParser.newFixed(4);

        // Usamos un array de 1 posición como "wrapper" mutable para la lambda
        // true = Estamos esperando CABECERA (longitud)
        // false = Estamos esperando CUERPO (json)
        boolean[] readingHeader = {true};

        parser.handler(buffer -> {
            if (readingHeader[0]) {
                // FASE A: LEER LONGITUD
                try {
                    int length = buffer.getInt(0);
                    // Cambiamos el modo del parser para leer 'length' bytes
                    parser.fixedSizeMode(length);
                    readingHeader[0] = false;
                } catch (Exception e) {
                    System.err.println("⚠️ Error leyendo cabecera: " + e.getMessage());
                    socket.close();
                }
            } else {
                // FASE B: LEER CUERPO (PAYLOAD)
                handleMessagePayload(buffer, socket);

                // Resetear para el siguiente mensaje
                parser.fixedSizeMode(4);
                readingHeader[0] = true;
            }
        });

        long timerId = vertx.setPeriodic(5000, id -> {
            if (!activeSockets.contains(socket)) {
                vertx.cancelTimer(id);
                return;
            }
            JsonObject ping = new JsonObject().put("type", "PING");
            Buffer payload = Buffer.buffer(ping.encode());
            Buffer frame = Buffer.buffer().appendInt(payload.length()).appendBuffer(payload);
            if (!socket.writeQueueFull()) {
                socket.write(frame).onFailure(err ->
                        System.err.println("🔥 Error enviando ping: " + err.getMessage())
                );
            }
        });

        socket.handler(parser);
    }

    // --- PROCESADO DE MENSAJES (GOSSIP LOGIC) ---
    private void handleMessagePayload(Buffer buffer, NetSocket originSocket) {
        try {
            JsonObject msg = new JsonObject(buffer.toString());
            String type = msg.getString("type");
            String msgId = msg.getString("hash");

            // 1. HANDSHAKE (Caso especial, no se difunde)
            if ("HANDSHAKE".equals(type)) {
                int remotePort = msg.getJsonObject("data").getInteger("listenPort");
                System.out.println("🤝 Handshake recibido de " + originSocket.remoteAddress().host() + ":" + remotePort);
                return;
            }

            // 2. GOSSIP CHECK: Evitar bucles
            if (msgId != null) {
                if (seenMessagesCache.contains(msgId)) {
                    // Ya lo he visto, lo ignoro
                    return;
                }
                seenMessagesCache.add(msgId);
            }

            System.out.println("📩 P2P Recibido: " + type);

            // 3. ENRUTAR AL INTERIOR (EventBus)
            if ("BLOCK".equals(type)) {
                vertx.eventBus().publish(BusAddresses.INCOMING_BLOCK, msg.getJsonObject("data"));
            } else if ("TRANSACTION".equals(type)) {
                vertx.eventBus().publish(BusAddresses.INCOMING_TRANSACTION, msg.getJsonObject("data"));
            }

            // 4. REENVIAR A VECINOS (Flood)
            // Reenviamos a todos menos al que me lo envió (mejora de eficiencia)
            broadcastMessageExcept(msg, originSocket);

        } catch (Exception e) {
            System.err.println("⚠️ Payload corrupto: " + e.getMessage());
        }
    }

    // --- DIFUSIÓN (WRITE WITH FRAMING) ---

    /**
     * Envía mensaje a TODOS los sockets (Broadcast puro).
     */
    private void broadcastMessage(JsonObject msg) {
        broadcastMessageExcept(msg, null);
    }

    /**
     * Envía mensaje a todos MENOS a uno (para evitar rebote inmediato).
     */
    private void broadcastMessageExcept(JsonObject msg, NetSocket excludeSocket) {
        // Aseguramos que el mensaje tiene hash para la caché
        String msgId = msg.getString("hash");
        if (msgId != null && !seenMessagesCache.contains(msgId)) {
            seenMessagesCache.add(msgId);
        }

        // PREPARAR BUFFER BINARIO
        Buffer payload = Buffer.buffer(msg.encode());
        Buffer frame = Buffer.buffer();
        frame.appendInt(payload.length()); // Cabecera
        frame.appendBuffer(payload);       // Cuerpo

        for (NetSocket socket : activeSockets) {
            if (socket.equals(excludeSocket)) continue;

            if (!socket.writeQueueFull()) {
                socket.write(frame).onFailure(err -> {
                    System.err.println("🔥 Write falló para " + socket.remoteAddress() + ": " + err.getMessage());
                    err.printStackTrace();
                });
            }
        }
    }

    // --- UTILS ---
    private void sendHandshake(NetSocket socket) {
        JsonObject handshake = new JsonObject()
                .put("type", "HANDSHAKE")
                .put("data", new JsonObject()
                        .put("listenPort", this.listenPort)
                        .put("version", 1));

        // Escribimos directamente usando framing manual (podríamos reutilizar método auxiliar)
        Buffer payload = Buffer.buffer(handshake.encode());
        Buffer frame = Buffer.buffer().appendInt(payload.length()).appendBuffer(payload);
        socket.write(frame);
    }
}