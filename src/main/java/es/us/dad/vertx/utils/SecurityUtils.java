package es.us.dad.vertx.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public class SecurityUtils {

    // ==========================================
    // 1. GENERACIÓN DE CLAVES (Curva Elíptica secp256r1)
    // ==========================================
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

    // ==========================================
    // 2. DIRECCIONES HASH160 (Punto 6 Módulo 8)
    // Nota: Esta es una simplificación para el laboratorio. Bitcoin real usa
    // RIPEMD160(SHA256(pubkey)). Aquí aplicamos SHA-256 y truncamos a 20 bytes,
    // que es funcionalmente equivalente para los propósitos de este proyecto.
    // ==========================================
    public static String generateHash160Address(PublicKey publicKey) {
        try {
            // Aplicamos SHA-256 a la clave pública
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey.getEncoded());

            // Truncamos a 20 bytes (simulando RIPEMD-160)
            byte[] hash160 = Arrays.copyOfRange(hash, 0, 20);

            // Convertimos a Hexadecimal
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash160) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generando la dirección Hash160", e);
        }
    }

    // ==========================================
    // 3. SEGURIDAD EN REPOSO (AES) (Punto 5 Módulo 8)
    // Nota: Se usa AES/ECB por simplicidad en el laboratorio.
    // En producción debería usarse AES/CBC o AES/GCM con un IV aleatorio.
    // ==========================================
    private static SecretKeySpec generateAESKey(String password) throws Exception {
        // Usamos SHA-256 para convertir la contraseña en una clave válida de 256 bits
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(password.getBytes("UTF-8"));
        return new SecretKeySpec(key, "AES");
    }

    public static byte[] encryptAES(byte[] data, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, generateAESKey(password));
        return cipher.doFinal(data);
    }

    public static byte[] decryptAES(byte[] encryptedData, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, generateAESKey(password));
        return cipher.doFinal(encryptedData);
    }

    // ==========================================
    // 4. MÉTODOS HEREDADOS (Codificación y Firmas)
    // ==========================================
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

    // Versión legada — firma datos que llegan como String.
    // Usada internamente por Transaction.verifySignature() y similares.
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

    // ==========================================
    // 5. CORRECCIÓN MÓDULO 8 — Firma con bytes crudos (Punto 7)
    // ==========================================
    // NUEVO: Variante que acepta directamente el byte[] del hash SHA-256.
    // La conversión byte[] -> new String() corrompe datos binarios porque los bytes
    // de un hash SHA-256 no son texto UTF-8 válido y Java los altera al codificar.
    // Este método pasa los bytes directamente a Signature.update(), garantizando
    // que lo que se firma es exactamente lo mismo que luego se verifica.
    public static byte[] applyECDSASigBytes(PrivateKey privateKey, byte[] dataBytes) {
        try {
            Signature dsa = Signature.getInstance("SHA256withECDSA");
            dsa.initSign(privateKey);
            dsa.update(dataBytes);
            return dsa.sign();
        } catch (Exception e) {
            throw new RuntimeException("Error firmando los bytes de la transacción", e);
        }
    }

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
