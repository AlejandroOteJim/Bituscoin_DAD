package es.us.dad.vertx.network;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.*;
import io.vertx.core.parsetools.RecordParser;
import java.util.*;
import io.vertx.core.Promise;

public class P2PConnectionManager extends AbstractVerticle {

    private static final String BROADCAST_REQUEST_ADDRESS = "p2p.action.broadcast";
    private static final String MINED_BLOCK_ADDRESS = "internal.mined.block";
    private static final String INCOMING_BLOCK_ADDRESS = "p2p.incoming.block";
    private static final String INCOMING_TRANSACTION_ADDRESS = "p2p.incoming.transaction";

    private int listenPort;

    private final List<NetSocket> activeSockets = new ArrayList<>();

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

        this.listenPort = config().getInteger("p2p.port", 6000);
        String seed = config().getString("p2p.seed.ip", "");

        startServer();

        if (!seed.isEmpty()) {
            connectToPeer(seed);
        }

        vertx.eventBus().consumer(BROADCAST_REQUEST_ADDRESS, msg -> {
            broadcastMessage((JsonObject) msg.body());
        });

        vertx.eventBus().consumer(MINED_BLOCK_ADDRESS, msg -> {
            JsonObject blockJson = (JsonObject) msg.body();

            JsonObject p2pMsg = new JsonObject()
                    .put("type", "BLOCK")
                    .put("hash", blockJson.getString("hash"))
                    .put("data", blockJson);

            System.out.println("📢 Minero local encontró bloque " + p2pMsg.getString("hash").substring(0, 6) + "... Difundiendo.");

            seenMessagesCache.add(p2pMsg.getString("hash"));

            broadcastMessage(p2pMsg);
        });

        System.out.println("📡 P2P Manager iniciado en puerto " + listenPort);
        startPromise.complete();
    }

    private void startServer() {
        NetServerOptions options = new NetServerOptions()
                .setTcpKeepAlive(true);
        NetServer server = vertx.createNetServer(options);

        server.connectHandler(socket -> {
            System.out.println("Nueva conexión entrante desde: " + socket.remoteAddress());
            handleSocketConnection(socket);
        });

        server.listen(listenPort).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("Servidor P2P escuchando en puerto " + listenPort);
            } else {
                System.err.println("Error al iniciar servidor P2P: " + res.cause().getMessage());
            }
        });
    }

    private void connectToPeer(String address) {
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        NetClientOptions options = new NetClientOptions().setTcpKeepAlive(true);
        NetClient client = vertx.createNetClient(options);


        System.out.println("🔌 Intentando conectar a semilla: " + address);

        client.connect(port, host).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("Conectado exitosamente a " + address);
                NetSocket socket = res.result();
                handleSocketConnection(socket);
                sendHandshake(socket);
            } else {
                System.err.println("No se pudo conectar a " + address);
            }
        });
    }

    private void handleSocketConnection(NetSocket socket) {
        activeSockets.add(socket);

        socket.closeHandler(v -> {
            activeSockets.remove(socket);
            System.out.println("Conexión cerrada con " + socket.remoteAddress());
            String seed = config().getString("p2p.seed.ip", "");
            if (!seed.isEmpty()) {
                System.out.println("Reconectando en 3 segundos...");
                vertx.setTimer(3000, id -> connectToPeer(seed));
            }
        });

        socket.exceptionHandler(t -> {
            System.err.println("ERROR EN SOCKET " + socket.remoteAddress() + ": " + t.getMessage());
            t.printStackTrace();
        });

        RecordParser parser = RecordParser.newFixed(4);

        boolean[] readingHeader = {true};

        parser.handler(buffer -> {
            if (readingHeader[0]) {
                try {
                    int length = buffer.getInt(0);
                    parser.fixedSizeMode(length);
                    readingHeader[0] = false;
                } catch (Exception e) {
                    System.err.println("Error leyendo cabecera: " + e.getMessage());
                    socket.close();
                }
            } else {
                handleMessagePayload(buffer, socket);
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
                        System.err.println("Error enviando ping: " + err.getMessage())
                );
            }
        });

        socket.handler(parser);
    }

    private void handleMessagePayload(Buffer buffer, NetSocket originSocket) {
        try {
            JsonObject msg = new JsonObject(buffer.toString());
            String type = msg.getString("type");
            String msgId = msg.getString("hash");

            if ("HANDSHAKE".equals(type)) {
                int remotePort = msg.getJsonObject("data").getInteger("listenPort");
                System.out.println("🤝 Handshake recibido de " + originSocket.remoteAddress().host() + ":" + remotePort);
                return;
            }

            if (msgId != null) {
                if (seenMessagesCache.contains(msgId)) {
                    return;
                }
                seenMessagesCache.add(msgId);
            }

            System.out.println("P2P Recibido: " + type);

            if ("BLOCK".equals(type)) {
                vertx.eventBus().publish(INCOMING_BLOCK_ADDRESS, msg.getJsonObject("data"));
            } else if ("TRANSACTION".equals(type)) {
                vertx.eventBus().publish(INCOMING_TRANSACTION_ADDRESS, msg.getJsonObject("data"));
            }

            broadcastMessageExcept(msg, originSocket);

        } catch (Exception e) {
            System.err.println("Payload corrupto: " + e.getMessage());
        }
    }

    private void broadcastMessage(JsonObject msg) {
        broadcastMessageExcept(msg, null);
    }

    private void broadcastMessageExcept(JsonObject msg, NetSocket excludeSocket) {
        String msgId = msg.getString("hash");
        if (msgId != null && !seenMessagesCache.contains(msgId)) {
            seenMessagesCache.add(msgId);
        }

        Buffer payload = Buffer.buffer(msg.encode());
        Buffer frame = Buffer.buffer();
        frame.appendInt(payload.length());
        frame.appendBuffer(payload);

        for (NetSocket socket : activeSockets) {
            if (socket.equals(excludeSocket)) continue;

            if (!socket.writeQueueFull()) {
                socket.write(frame).onFailure(err -> {
                    System.err.println("Write falló para " + socket.remoteAddress() + ": " + err.getMessage());
                    err.printStackTrace();
                });
            }
        }
    }

    private void sendHandshake(NetSocket socket) {
        JsonObject handshake = new JsonObject()
                .put("type", "HANDSHAKE")
                .put("data", new JsonObject()
                        .put("listenPort", this.listenPort)
                        .put("version", 1));

        Buffer payload = Buffer.buffer(handshake.encode());
        Buffer frame = Buffer.buffer().appendInt(payload.length()).appendBuffer(payload);
        socket.write(frame);
    }
}