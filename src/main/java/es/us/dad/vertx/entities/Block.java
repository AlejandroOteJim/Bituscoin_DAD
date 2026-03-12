package es.us.dad.vertx.entities;

import io.vertx.core.json.JsonObject;

public class Block {

    private Header header;
    private Body body;
    private String hash;

    public Block() {
    }

    public Block(Header header, Body body) {
        this.header = header;
        this.body = body;
        // IMPORTANTE: Asegurar que el Header tiene el Merkle Root correcto del Body
        if (body != null) {
            this.header.setMerkleRoot(body.calculateMerkleRoot());
        }
        this.hash = calculateHash();
    }

    // Constructor para reconstruir desde JSON (Recepción)
    public Block(JsonObject json) {
        this.hash = json.getString("hash");
        this.header = new Header(json.getJsonObject("header"));
        this.body = new Body(json.getJsonObject("body"));
    }

    // El hash del bloque depende SOLO del Header
    public String calculateHash() {
        String dataToHash =
                Integer.toString(this.header.getVersion()) +
                        Long.toString(this.header.getTimestamp())
                        + Long.toString(this.header.getIndex())
                        + this.header.getPreviousHash()
                        + this.header.getMerkleRoot() // <--- Usamos el resumen, no las TX completas
                        + Long.toString(this.header.getNonce());

        return Transaction.applySha256(dataToHash);
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("hash", hash)
                .put("header", header.toJson())
                .put("body", body.toJson());
    }

    // Getters y Setters estándar...
    public Header getHeader() {
        return header;
    }

    public void setHeader(Header header) {
        this.header = header;
    }

    public Body getBody() {
        return body;
    }

    public void setBody(Body body) {
        this.body = body;
    }

    public String getHash() {
        return hash;
    }
    public void setHash(String  hash) {
        this.hash = hash;
    }

    @Override
    public String toString() {
        return toJson().encodePrettily();
    }
}