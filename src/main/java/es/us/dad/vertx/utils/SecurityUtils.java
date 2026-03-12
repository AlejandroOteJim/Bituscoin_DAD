package es.us.dad.vertx.utils;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class SecurityUtils {

    // 1. GENERACIÓN DE CLAVES (Curva Elíptica secp256r1)
    public static KeyPair generateECKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            keyGen.initialize(256, random);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Error inicializando la criptografía", e);
        }
    }

    // 2. CODIFICACIÓN A TEXTO (Base64)
    public static String encodeKey(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String keyStr) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Error decodificando Clave Pública", e);
        }
    }

    // 3. FIRMA DIGITAL (ECDSA)
    public static byte[] applyECDSASig(PrivateKey privateKey, String input) {
        try {
            Signature dsa = Signature.getInstance("SHA256withECDSA");
            dsa.initSign(privateKey);
            dsa.update(input.getBytes());
            return dsa.sign();
        } catch (Exception e) {
            throw new RuntimeException("Error firmando la transacción", e);
        }
    }

    // 4. VERIFICACIÓN DE FIRMA
    public static boolean verifyECDSASig(PublicKey publicKey, String data, byte[] signature) {
        try {
            Signature ecdsaVerify = Signature.getInstance("SHA256withECDSA");
            ecdsaVerify.initVerify(publicKey);
            ecdsaVerify.update(data.getBytes());
            return ecdsaVerify.verify(signature);
        } catch (Exception e) {
            throw new RuntimeException("Error verificando la firma", e);
        }
    }
}