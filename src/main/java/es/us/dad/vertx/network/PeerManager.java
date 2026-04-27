package es.us.dad.vertx.network;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager extends AbstractVerticle{

    private int listenPort;
    private final int SOFTWARE_VERSION = 1;
    private final Map<String, NetSocket> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartBeat = new ConcurrentHashMap<>();
    private final int Cache_size = 1000;
    private final Set<String> seenMessagesCache = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(Cache_size, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest){
                    return size() > Cache_size;
                }
            }
    );

    public void star(Promise<Void> startPromise) {
        this.listenPort = config().getInteger("p2p.port", 6000);
        String seed = config().getString("p2p.seed.ip", "");
        startServer();
        if (!seed.isEmpty()) {
            connectToPeer(seed);
        }
        vertx.setPeriodic(5000, id -> broadcastPing());
        vertx.setPeriodic(10000, id -> checkTimeouts());
        vertx.eventBus().consumer(BusAddresses.BROADCAST_REQUEST, msg -> {
            broadcastMessage((JsonObject) msg.body());
        });
        vertx.eventBus().consumer(BusAddresses.MINED_BLOCK, msg -> {
            JsonObject blockJson = (JsonObject) msg.body();
            JsonObject p2pMsg = new JsonObject()
                    .put("type", "BLOCK")
                    .put("hash", blockJson.getString("hash"))
                    .put("data", blockJson);
            System.out.println("📢 Minero local encontró bloque " + p2pMsg.getString("hash").substring(0, 6) + "... Difundiendo.");
            seenMessagesCache.add(p2pMsg.getString("hash"));
            broadcastMessage(p2pMsg);
        });
        System.out.println("📡 Peer Manager iniciado en puerto " + listenPort);
        startPromise.complete();
    }

    private void addPeer(String peerId, NetSocket socket) {
        if(!peers.containsKey(peerId)) {
            peers.put(peerId, socket);
            lastHeartBeat.put(peerId, System.currentTimeMillis());
            vertx.eventBus().publish("network.peer.connected", new JsonObject().put("peerId", peerId));
            sendMessage(socket, new JsonObject().put("type", "GET_PEERS"));
        }
    }

    private void removePeer(String peerId) {
        NetSocket socket = peers.remove(peerId);
        lastHeartBeat.remove(peerId);
        if(socket != null) {
            socket.close();
            vertx.eventBus().publish("network.peer.disconnected", new JsonObject().put("peerId", peerId));
            System.out.println("❌ Vecino desconectado/expulsado: " + peerId);
        }
    }

    private void startServer() {
        NetServer server = vertx.createNetServer();
        server.connectHandler(this::handleSocketConnection);
        server.listen(listenPort);
    }

    private void connectToPeer(String address) {
        if (address.equals("127.0.0.1" + this.listenPort) || address.equals("localhost:" + this.listenPort)) return;
        if (peers.containsKey(address)) return;
        String [] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        NetClient client = vertx.createNetClient();
        client.connect(port, host).onComplete(res -> {
            if (res.succeeded()) {
                NetSocket socket = res.result();
                handleSocketConnection(socket);
                JsonObject handshakeReq = new JsonObject()
                        .put("type", "HANDSHAKE_REQ")
                        .put("data", new JsonObject().put("listenPort", this.listenPort).put("version", SOFTWARE_VERSION));
                sendMessage(socket, handshakeReq);
            }
        });
    }

    private void handleSocketConnection(NetSocket socket) {
        socket.closeHandler(v -> {
            peers.entrySet().removeIf(entry -> {
                if(entry.getValue().equals(socket)) {
                    removePeer(entry.getKey());
                    return true;
                }
                return false;
            });
        });
        socket.exceptionHandler(t -> socket.close());
        RecordParser parser= RecordParser.newFixed(4);
        boolean [] readingHeader = {true};
        parser.handler(buffer -> {
            if (readingHeader[0]) {
                try {
                    int length = buffer.getInt(0);
                    parser.fixedSizeMode(length);
                    readingHeader[0] = false;
                } catch (Exception e) {
                    socket.close();
                }
            } else {
                handleMessagePayload(buffer, socket);
                parser.fixedSizeMode(4);
                readingHeader[0] = true;
            }
        });
        socket.handler(parser);
    }

    public void handleMessagePayload(Buffer buffer, NetSocket originSocket) {
        try {
            JsonObject msg = new JsonObject(buffer.toString());
            String type = msg.getString("type");
            String msgId = msg.getString("hash");
            String originIP = originSocket.remoteAddress().host();

            switch(type) {
                case "HANDSHAKE_REQ":
                    int reqVersion = msg.getJsonObject("data").getInteger("version");
                    int reqPort = msg.getJsonObject("data").getInteger("listenPort");
                    String reqPeerId = originIP + ":" + reqPort;
                    if (reqVersion == SOFTWARE_VERSION) {
                        addPeer(reqPeerId, originSocket);
                        JsonObject handshakeResp = new JsonObject()
                                .put("type", "HANDSHAKE_RESP")
                                .put("data", new JsonObject().put("listenPort", this.listenPort).put("version", SOFTWARE_VERSION));
                        sendMessage(originSocket, handshakeResp);
                    } else {
                        System.err.println("⚠️ Handshake rechazado de " + reqPeerId + " (Versión incorrecta)");
                        originSocket.close();
                    }
                    return;

                case "HANDSHAKE_RESP":
                    int resPort = msg.getJsonObject("data").getInteger("listenPort");
                    addPeer(originIP + ":" + resPort, originSocket);
                    return;

                case "PING":
                    sendMessage(originSocket, new JsonObject().put("type", "PONG").put("listenPort", this.listenPort));
                    return;

                case "PONG":
                    int pongPort = msg.getInteger("listenPort");
                    lastHeartBeat.put(originIP + ":" + pongPort, System.currentTimeMillis());
                    return;

                case "GET_PEERS":
                    JsonArray peerList = new JsonArray(new ArrayList<>(peers.keySet()));
                    sendMessage(originSocket, new JsonObject().put("type", "PEER_LIST").put("data", peerList));
                    return;

                case "PEER_LIST":
                    JsonArray recivedPeers = msg.getJsonArray("data");
                    for (int i = 0; i < recivedPeers.size(); i++) {
                        connectToPeer(recivedPeers.getString(i));
                    }
                    return;
            }
            if (msgId != null) {
                if (seenMessagesCache.contains(msgId)) return;
                seenMessagesCache.add(msgId);
            }
            if ("BLOCK".equals(type)) {
                vertx.eventBus().publish(BusAddresses.INCOMING_BLOCK, msg.getJsonObject("data"));
            } else if ("TRANSACTION".equals(type)){
                vertx.eventBus().publish(BusAddresses.INCOMING_TRANSACTION, msg.getJsonObject("data"));
            }
            broadcastMessageExcept(msg, originSocket);
        } catch (Exception e) {
            System.err.println("⚠️ Payload corrupto: " + e.getMessage());
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long MAX_IDLE_TIME = 15000;
        for (Map.Entry<String, Long> entry : lastHeartBeat.entrySet()) {
            if (now - entry.getValue() > MAX_IDLE_TIME) {
                System.err.println("⏱️ Timeout detectado para: " + entry.getKey());
                removePeer(entry.getKey());
            }
        }
    }

    private void broadcastPing() {
        JsonObject pingMsg = new JsonObject().put("type", "PING");
        broadcastMessageExcept(pingMsg, null);
    }

    private void broadcastMessage(JsonObject msg) {
        broadcastMessageExcept(msg, null);
    }

    private void broadcastMessageExcept(JsonObject msg, NetSocket excludeSocket) {
        String msgId = msg.getString("hash");
        if (msgId != null && !seenMessagesCache.contains(msgId)) {
            seenMessagesCache.add(msgId);
        }
        for (NetSocket socket : peers.values()) {
            if (!socket.equals(excludeSocket)) {
                sendMessage(socket, msg);
            }
        }
    }

    private void sendMessage(NetSocket socket, JsonObject msg) {
        if (socket != null && !socket.writeQueueFull()) {
            Buffer payload = Buffer.buffer(msg.encode());
            Buffer frame = Buffer.buffer().appendInt(payload.length()).appendBuffer(payload);
            socket.write(frame);
        }
    }

}
