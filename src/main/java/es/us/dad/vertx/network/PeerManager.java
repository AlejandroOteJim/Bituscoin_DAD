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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager extends AbstractVerticle {

    private static final int SOFTWARE_VERSION = 1;
    private static final int SEEN_CACHE_SIZE = 1000;
    private static final long DEFAULT_PING_INTERVAL_MS = 5000;
    private static final long DEFAULT_PEER_TIMEOUT_MS = 15000;

    private int listenPort;
    private long pingIntervalMs;
    private long peerTimeoutMs;

    private final ConcurrentHashMap<String, NetSocket> peers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NetSocket, String> peerIdsBySocket = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPongAt = new ConcurrentHashMap<>();
    private final Set<String> pendingConnections = ConcurrentHashMap.newKeySet();

    private final Set<String> seenMessagesCache = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(SEEN_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CACHE_SIZE;
                }
            }
    );

    @Override
    public void start(Promise<Void> startPromise) {
        this.listenPort = config().getInteger("p2p.port", 6000);
        this.pingIntervalMs = config().getLong("p2p.ping.interval.ms", DEFAULT_PING_INTERVAL_MS);
        this.peerTimeoutMs = config().getLong("p2p.peer.timeout.ms", DEFAULT_PEER_TIMEOUT_MS);

        registerEventBusConsumers();
        startServer(startPromise);
    }

    private void startServer(Promise<Void> startPromise) {
        NetServer server = vertx.createNetServer();

        server.connectHandler(socket -> {
            System.out.println("[P2P] Incoming TCP connection from " + socket.remoteAddress());
            handleSocketConnection(socket);
        });

        server.listen(listenPort).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("[P2P] PeerManager listening on port " + listenPort);
                startHealthChecks();
                connectToConfiguredSeed();
                startPromise.complete();
            } else {
                System.err.println("[P2P] Could not start server: " + res.cause().getMessage());
                startPromise.fail(res.cause());
            }
        });
    }

    private void connectToConfiguredSeed() {
        String seed = config().getString("p2p.seed.ip", "");
        if (!seed.isBlank()) {
            connectToPeer(seed);
        }
    }

    private void registerEventBusConsumers() {
        vertx.eventBus().consumer(BusAddresses.BROADCAST_REQUEST, msg -> {
            broadcastMessage((JsonObject) msg.body());
        });

        vertx.eventBus().consumer(BusAddresses.MINED_BLOCK, msg -> {
            JsonObject blockJson = (JsonObject) msg.body();
            JsonObject p2pMsg = new JsonObject()
                    .put("type", "BLOCK")
                    .put("hash", blockJson.getString("hash"))
                    .put("data", blockJson);

            String hash = p2pMsg.getString("hash");
            if (hash != null) {
                seenMessagesCache.add(hash);
            }

            System.out.println("[P2P] Broadcasting local mined block " + abbreviate(hash));
            broadcastMessage(p2pMsg);
        });
    }

    private void startHealthChecks() {
        vertx.setPeriodic(pingIntervalMs, id -> broadcastPing());
        vertx.setPeriodic(Math.max(1000, pingIntervalMs), id -> removeTimedOutPeers());
    }

    private void connectToPeer(String peerId) {
        if (!isValidPeerId(peerId) || isSelf(peerId) || peers.containsKey(peerId) || !pendingConnections.add(peerId)) {
            return;
        }

        String[] parts = peerId.split(":", 2);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        NetClient client = vertx.createNetClient();

        System.out.println("[P2P] Connecting to peer " + peerId);
        client.connect(port, host).onComplete(res -> {
            pendingConnections.remove(peerId);

            if (res.succeeded()) {
                NetSocket socket = res.result();
                handleSocketConnection(socket);
                sendHandshakeRequest(socket);
            } else {
                System.err.println("[P2P] Could not connect to " + peerId + ": " + res.cause().getMessage());
            }
        });
    }

    private void handleSocketConnection(NetSocket socket) {
        socket.closeHandler(v -> {
            String peerId = peerIdsBySocket.get(socket);
            if (peerId != null) {
                removePeer(peerId, "socket closed");
            }
        });

        socket.exceptionHandler(t -> {
            System.err.println("[P2P] Socket error from " + socket.remoteAddress() + ": " + t.getMessage());
            socket.close();
        });

        RecordParser parser = RecordParser.newFixed(4);
        boolean[] readingHeader = {true};

        parser.handler(buffer -> {
            if (readingHeader[0]) {
                readFrameHeader(buffer, parser, socket, readingHeader);
            } else {
                handleMessagePayload(buffer, socket);
                parser.fixedSizeMode(4);
                readingHeader[0] = true;
            }
        });

        socket.handler(parser);
    }

    private void readFrameHeader(Buffer buffer, RecordParser parser, NetSocket socket, boolean[] readingHeader) {
        int length = buffer.getInt(0);
        if (length <= 0) {
            socket.close();
            return;
        }

        parser.fixedSizeMode(length);
        readingHeader[0] = false;
    }

    private void handleMessagePayload(Buffer buffer, NetSocket originSocket) {
        try {
            JsonObject msg = new JsonObject(buffer.toString());
            String type = msg.getString("type");

            if (type == null) {
                return;
            }

            switch (type) {
                case "HANDSHAKE_REQUEST":
                    handleHandshakeRequest(msg, originSocket);
                    return;
                case "HANDSHAKE_RESPONSE":
                    handleHandshakeResponse(msg, originSocket);
                    return;
                case "PING":
                    sendMessage(originSocket, new JsonObject().put("type", "PONG"));
                    return;
                case "PONG":
                    updateHeartbeat(originSocket);
                    return;
                case "GET_PEERS":
                    sendKnownPeers(originSocket);
                    return;
                case "PEER_LIST":
                    connectToDiscoveredPeers(msg);
                    return;
                default:
                    processGossipMessage(msg, originSocket);
            }
        } catch (Exception e) {
            System.err.println("[P2P] Corrupt payload from " + originSocket.remoteAddress() + ": " + e.getMessage());
            originSocket.close();
        }
    }

    private void handleHandshakeRequest(JsonObject msg, NetSocket originSocket) {
        JsonObject data = msg.getJsonObject("data", new JsonObject());
        String peerId = buildPeerId(originSocket, data);

        if (!isHandshakeVersionAccepted(data, peerId)) {
            originSocket.close();
            return;
        }

        addPeer(peerId, originSocket);
        sendHandshakeResponse(originSocket);
    }

    private void handleHandshakeResponse(JsonObject msg, NetSocket originSocket) {
        JsonObject data = msg.getJsonObject("data", new JsonObject());
        String peerId = buildPeerId(originSocket, data);

        if (!isHandshakeVersionAccepted(data, peerId)) {
            originSocket.close();
            return;
        }

        addPeer(peerId, originSocket);
    }

    private boolean isHandshakeVersionAccepted(JsonObject data, String peerId) {
        Integer remoteVersion = data.getInteger("version");
        if (remoteVersion == null || remoteVersion != SOFTWARE_VERSION) {
            System.err.println("[P2P] Rejected peer " + peerId + " because software version is " + remoteVersion);
            return false;
        }
        return true;
    }

    private String buildPeerId(NetSocket socket, JsonObject data) {
        Integer remoteListenPort = data.getInteger("listenPort");
        String host = socket.remoteAddress().host();
        int port = remoteListenPort != null ? remoteListenPort : socket.remoteAddress().port();
        return host + ":" + port;
    }

    private void addPeer(String peerId, NetSocket socket) {
        if (isSelf(peerId)) {
            socket.close();
            return;
        }

        NetSocket previousSocket = peers.put(peerId, socket);
        if (previousSocket != null && previousSocket != socket) {
            peerIdsBySocket.remove(previousSocket);
            previousSocket.close();
        }

        peerIdsBySocket.put(socket, peerId);
        lastPongAt.put(peerId, System.currentTimeMillis());

        if (previousSocket == null) {
            System.out.println("[P2P] Peer connected: " + peerId);
            vertx.eventBus().publish(BusAddresses.NETWORK_PEER_CONNECTED, new JsonObject().put("peerId", peerId));
            requestPeerList(socket);
        }
    }

    private void removePeer(String peerId, String reason) {
        NetSocket socket = peers.remove(peerId);
        lastPongAt.remove(peerId);

        if (socket != null) {
            peerIdsBySocket.remove(socket);
            socket.close();
            System.out.println("[P2P] Peer disconnected: " + peerId + " (" + reason + ")");
            vertx.eventBus().publish(BusAddresses.NETWORK_PEER_DISCONNECTED,
                    new JsonObject().put("peerId", peerId).put("reason", reason));
        }
    }

    private void updateHeartbeat(NetSocket originSocket) {
        String peerId = peerIdsBySocket.get(originSocket);
        if (peerId != null) {
            lastPongAt.put(peerId, System.currentTimeMillis());
        }
    }

    private void removeTimedOutPeers() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : lastPongAt.entrySet()) {
            if (now - entry.getValue() > peerTimeoutMs) {
                removePeer(entry.getKey(), "pong timeout");
            }
        }
    }

    private void sendHandshakeRequest(NetSocket socket) {
        sendMessage(socket, new JsonObject()
                .put("type", "HANDSHAKE_REQUEST")
                .put("data", handshakeData()));
    }

    private void sendHandshakeResponse(NetSocket socket) {
        sendMessage(socket, new JsonObject()
                .put("type", "HANDSHAKE_RESPONSE")
                .put("data", handshakeData()));
    }

    private JsonObject handshakeData() {
        return new JsonObject()
                .put("listenPort", listenPort)
                .put("version", SOFTWARE_VERSION);
    }

    private void requestPeerList(NetSocket socket) {
        sendMessage(socket, new JsonObject().put("type", "GET_PEERS"));
    }

    private void sendKnownPeers(NetSocket originSocket) {
        String originPeerId = peerIdsBySocket.get(originSocket);
        ArrayList<String> knownPeers = new ArrayList<>(peers.keySet());
        knownPeers.remove(originPeerId);

        JsonArray peerList = new JsonArray(knownPeers);
        sendMessage(originSocket, new JsonObject()
                .put("type", "PEER_LIST")
                .put("data", peerList));
    }

    private void connectToDiscoveredPeers(JsonObject msg) {
        JsonArray peerList = msg.getJsonArray("data", new JsonArray());
        for (int i = 0; i < peerList.size(); i++) {
            connectToPeer(peerList.getString(i));
        }
    }

    private void processGossipMessage(JsonObject msg, NetSocket originSocket) {
        String msgId = msg.getString("hash");
        if (msgId != null) {
            if (seenMessagesCache.contains(msgId)) {
                return;
            }
            seenMessagesCache.add(msgId);
        }

        String type = msg.getString("type");
        if ("BLOCK".equals(type)) {
            vertx.eventBus().publish(BusAddresses.INCOMING_BLOCK, msg.getJsonObject("data"));
        } else if ("TRANSACTION".equals(type)) {
            vertx.eventBus().publish(BusAddresses.INCOMING_TRANSACTION, msg.getJsonObject("data"));
        }

        String originPeerId = peerIdsBySocket.get(originSocket);
        broadcastMessageExcept(msg, originPeerId);
    }

    private void broadcastPing() {
        broadcastMessage(new JsonObject().put("type", "PING"));
    }

    private void broadcastMessage(JsonObject msg) {
        broadcastMessageExcept(msg, null);
    }

    private void broadcastMessageExcept(JsonObject msg, String excludedPeerId) {
        String msgId = msg.getString("hash");
        if (msgId != null) {
            seenMessagesCache.add(msgId);
        }

        for (Map.Entry<String, NetSocket> entry : peers.entrySet()) {
            if (entry.getKey().equals(excludedPeerId)) {
                continue;
            }
            sendMessage(entry.getValue(), msg);
        }
    }

    private void sendMessage(NetSocket socket, JsonObject msg) {
        if (socket == null || socket.writeQueueFull()) {
            return;
        }

        Buffer payload = Buffer.buffer(msg.encode());
        Buffer frame = Buffer.buffer()
                .appendInt(payload.length())
                .appendBuffer(payload);
        socket.write(frame);
    }

    private boolean isValidPeerId(String peerId) {
        String[] parts = peerId.split(":", 2);
        if (parts.length != 2) {
            return false;
        }

        try {
            Integer.parseInt(parts[1]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isSelf(String peerId) {
        String[] parts = peerId.split(":", 2);
        if (parts.length != 2) {
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }

        String host = parts[0];
        return port == listenPort && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "0.0.0.0".equals(host));
    }

    private String abbreviate(String hash) {
        if (hash == null || hash.length() <= 8) {
            return String.valueOf(hash);
        }
        return hash.substring(0, 8) + "...";
    }
}
