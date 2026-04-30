package es.us.dad.vertx.entities;

import io.vertx.core.json.JsonObject;

public class Header {

    private long index;
    private long timestamp;
    private String previousHash;
    private String merkleRoot;
    private long nonce;
    private int difficulty;
    private int version;

    public Header() {}

    // Constructor completo
    public Header(int version, long index, String previousHash, long timestamp, long nonce, int difficulty) {
        this.version = version;
        this.index = index;
        this.previousHash = previousHash;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.difficulty = difficulty;
    }

    // Constructor desde JsonObject (Para Vert.x)
    public Header(JsonObject json) {
        this.version = json.getInteger("version", 1);
        this.index = json.getLong("index");
        this.timestamp = json.getLong("timestamp");
        this.previousHash = json.getString("previousHash");
        this.merkleRoot = json.getString("merkleRoot");
        this.nonce = json.getLong("nonce");
        this.difficulty = json.getInteger("difficulty");
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("version", version)
                .put("index", index)
                .put("timestamp", timestamp)
                .put("previousHash", previousHash)
                .put("merkleRoot", merkleRoot)
                .put("nonce", nonce)
                .put("difficulty", difficulty);
    }

    // --- Getters y Setters ---
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public long getIndex() { return index; }
    public void setIndex(long index) { this.index = index; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public String getMerkleRoot() { return merkleRoot; }
    public void setMerkleRoot(String merkleRoot) { this.merkleRoot = merkleRoot; }
    public long getNonce() { return nonce; }
    public void setNonce(long nonce) { this.nonce = nonce; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }

    @Override
    public String toString() {
        return "Header [version=" +  version + ", Idx=" + index + ", Prev=" + previousHash + ", Root=" + merkleRoot + " + Nonce=" + nonce + ", Diff=" + difficulty + "]";
    }
}