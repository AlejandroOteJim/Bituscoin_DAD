package es.us.dad.vertx.network;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class P2PConnectionManager extends AbstractVerticle {

    // --- GOSSIP SEEN CACHE ---
    // (Mantenemos la caché aquí, ya que el control de inundación es lógica de negocio)
    private static final int SEEN_CACHE_SIZE = 1000;
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

        // 1. CONSUMIDOR: Peticiones genéricas de difusión (Ej: Wallet manda TX)
        vertx.eventBus().consumer(BusAddresses.BROADCAST_REQUEST, msg -> {
            broadcastToTopology((JsonObject) msg.body(), null);
        });

        // 2. CONSUMIDOR: Bloque minado localmente
        vertx.eventBus().consumer(BusAddresses.MINED_BLOCK, msg -> {
            JsonObject blockJson = (JsonObject) msg.body();
            JsonObject p2pMsg = new JsonObject()
                    .put("type", "BLOCK")
                    .put("hash", blockJson.getString("hash"))
                    .put("data", blockJson);

            System.out.println("📢 [Protocolo P2P] Minero local encontró bloque... Difundiendo.");
            
            seenMessagesCache.add(p2pMsg.getString("hash"));
            broadcastToTopology(p2pMsg, null);
        });

        // 3. CONSUMIDOR: Mensajes entrantes recibidos por el PeerManager
        vertx.eventBus().consumer("network.internal.gossip.receive", msg -> {
            JsonObject wrapper = (JsonObject) msg.body();
            JsonObject p2pMsg = wrapper.getJsonObject("message");
            String originPeerId = wrapper.getString("originPeerId");
            
            String type = p2pMsg.getString("type");
            String msgId = p2pMsg.getString("hash");

            // Comprobación Gossip para evitar bucles infinitos
            if (msgId != null) {
                if (seenMessagesCache.contains(msgId)) return;
                seenMessagesCache.add(msgId);
            }

            // Enrutamiento interno hacia el Blockchain/Pool
            if ("BLOCK".equals(type)) {
                vertx.eventBus().publish(BusAddresses.INCOMING_BLOCK, p2pMsg.getJsonObject("data"));
            } else if ("TRANSACTION".equals(type)) {
                vertx.eventBus().publish(BusAddresses.INCOMING_TRANSACTION, p2pMsg.getJsonObject("data"));
            }

            // Reenviar al resto de la red (inundación), excluyendo al que nos lo envió
            broadcastToTopology(p2pMsg, originPeerId);
        });

        System.out.println("📡 P2PConnectionManager (Capa Gossip) iniciado correctamente.");
        startPromise.complete();
    }

    /**
     * Delega el envío físico de mensajes al PeerManager a través del EventBus
     */
    private void broadcastToTopology(JsonObject msg, String excludePeerId) {
        JsonObject cmd = new JsonObject()
                .put("message", msg);
        
        if (excludePeerId != null) {
            cmd.put("excludePeerId", excludePeerId);
        }

        // Le decimos al PeerManager: "Envía esto a la topología"
        vertx.eventBus().publish("network.internal.topology.broadcast", cmd);
    }
}
