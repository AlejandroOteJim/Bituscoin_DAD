package es.us.dad.vertx.wallet;

import es.us.dad.vertx.utils.SecurityUtils;
import java.security.PublicKey;

public class Wallet {

    // 1. Delegamos la persistencia y la seguridad al KeyManager
    private KeyManager keyManager;

    public Wallet(String password) {
        // Exigimos contraseña en la creación para el cifrado AES
        this.keyManager = new KeyManager(password);
    }

    // 2. Usamos Hash160 (más corto y seguro) en lugar de Base64
    public String getAddress() {
        return SecurityUtils.generateHash160Address(keyManager.getPublicKey());
    }

    public PublicKey getPublicKey() {
        return keyManager.getPublicKey();
    }

    // 3. AISLAMIENTO: La Wallet ya no construye la Transacción.
    // Solo expone un método para que el Módulo 9 le pase un Hash y le devuelva la firma.
    public byte[] signTransaction(byte[] hashToSign) {
        return keyManager.sign(hashToSign);
    }
}