package es.us.dad.vertx.network;

public class BusAddresses {
    // --- COMUNICACIÓN INTERNA (Lo que ocurre dentro del nodo) ---

    // El Minero publica aquí cuando encuentra un bloque
    public static final String MINED_BLOCK = "internal.mined.block";

    // La Wallet publica aquí cuando crea una transacción
    public static final String NEW_TRANSACTION = "internal.new.transaction";

    // --- COMUNICACIÓN ENTRANTE (Lo que viene de internet) ---

    // P2PConnectionManager publica aquí cuando recibe un BLOQUE de otro nodo
    public static final String INCOMING_BLOCK = "p2p.incoming.block";

    // P2PConnectionManager publica aquí cuando recibe una TX de otro nodo
    public static final String INCOMING_TRANSACTION = "p2p.incoming.transaction";

    // --- COMUNICACIÓN SALIENTE (Para pedirle al P2PManager que difunda) ---

    // Publicar aquí para que P2PConnectionManager haga broadcast a todos los vecinos
    public static final String BROADCAST_REQUEST = "p2p.action.broadcast";
}