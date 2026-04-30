package es.us.dad.vertx.wallet;

import es.us.dad.vertx.utils.SecurityUtils;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public class KeyManager {

    private static final String WALLET_FILE = "wallet.dat";

    // Las claves viven aquí dentro y la privada NUNCA sale de esta clase
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private String password;

    // PUNTO 2: El constructor recibe la contraseña y arranca el ciclo de vida
    public KeyManager(String password) {
        this.password = password;
        loadOrGenerateKeys();
    }

    private void loadOrGenerateKeys() {
        if (Files.exists(Paths.get(WALLET_FILE))) {
            System.out.println("🔐 [KeyManager] Archivo " + WALLET_FILE + " encontrado. Intentando descifrar...");
            loadKeysFromDisk(); // PUNTO 4: Cargar desde disco
        } else {
            System.out.println("🔐 [KeyManager] No hay Wallet previa. Generando nuevas claves ECDSA...");
            KeyPair pair = SecurityUtils.generateECKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            saveKeysToDisk(); // PUNTO 3: Guardar en disco
        }
    }

    private void saveKeysToDisk() {
        try {
            // 1. Convertimos los objetos clave a una secuencia de bytes (Serialización)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(privateKey);
            oos.writeObject(publicKey);
            oos.flush();

            // 2. PUNTO 5: Ciframos esos bytes usando AES y la contraseña
            byte[] encryptedData = SecurityUtils.encryptAES(baos.toByteArray(), this.password);

            // 3. Escribimos los bytes cifrados en el archivo físico
            FileOutputStream fos = new FileOutputStream(WALLET_FILE);
            fos.write(encryptedData);
            fos.close();

            System.out.println("💾 [KeyManager] Claves guardadas y protegidas con AES en disco.");
        } catch (Exception e) {
            System.err.println("❌ Error guardando claves: " + e.getMessage());
        }
    }

    private void loadKeysFromDisk() {
        try {
            // 1. Leemos los bytes cifrados del archivo
            byte[] encryptedData = Files.readAllBytes(Paths.get(WALLET_FILE));

            // 2. PUNTO 5: Desciframos los bytes usando la contraseña
            byte[] decryptedData = SecurityUtils.decryptAES(encryptedData, this.password);

            // 3. Reconstruimos los objetos clave a partir de los bytes en claro (Deserialización)
            ByteArrayInputStream bais = new ByteArrayInputStream(decryptedData);
            ObjectInputStream ois = new ObjectInputStream(bais);
            this.privateKey = (PrivateKey) ois.readObject();
            this.publicKey = (PublicKey) ois.readObject();

            System.out.println("🔓 [KeyManager] Claves descifradas y cargadas en memoria correctamente.");
        } catch (Exception e) {
            System.err.println("❌ Error descifrando claves. ¿Contraseña incorrecta?: " + e.getMessage());
            // Detenemos la ejecución si la contraseña es mala o el archivo está corrupto
            throw new RuntimeException("Fallo al desbloquear la Wallet. Revisa la contraseña.");
        }
    }

    // Devolvemos la clave pública (esta sí se puede compartir)
    public PublicKey getPublicKey() {
        return publicKey;
    }

    // PUNTO 7: AISLAMIENTO (Caja Negra)
    // Recibe los bytes del hash, los firma con la clave privada y devuelve la firma.
    // La clave privada jamás sale del KeyManager.
    //
    // CORRECCIÓN: Se usa applyECDSASigBytes() en lugar de applyECDSASig(String).
    // La conversión byte[] -> new String() corrompe datos binarios porque los bytes
    // de un hash SHA-256 no son texto UTF-8 válido. Aquí pasamos los bytes directamente
    // al objeto Signature de Java, garantizando integridad total del hash a firmar.
    public byte[] sign(byte[] dataHash) {
        return SecurityUtils.applyECDSASigBytes(this.privateKey, dataHash);
    }
}