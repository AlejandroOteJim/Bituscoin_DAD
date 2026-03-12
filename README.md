# 🔐 Laboratorio 5: Wallets, Claves y Firmas Digitales (Bituscoin)

## 🎯 Objetivos

En el laboratorio anterior conseguimos que los nodos de nuestra red P2P se comunicaran e intercambiaran transacciones y bloques. Sin embargo, nuestro sistema actual es **completamente inseguro**: cualquiera puede falsificar la identidad de otro nodo escribiendo su nombre en el campo `sender` de un JSON.

En esta sesión vamos a implementar **Criptografía de Curva Elíptica (ECDSA)** para garantizar que solo el dueño legítimo de los fondos pueda gastarlos.

### Tareas a realizar:

1. Crear el motor criptográfico (`SecurityUtils`).
2. Implementar una verdadera clase `Wallet` con claves pública y privada.
3. Modificar `Transaction` para soportar firmas digitales.
4. Entender el papel especial de la `CoinbaseTransaction`.
5. Actualizar `MinerVerticle` para que rechaze a los impostores.

---

## 🛠️ Paso 1: El Motor Criptográfico (`SecurityUtils.java`)

Java proporciona excelentes herramientas en el paquete `java.security`. Para no mezclar la matemática con la lógica de negocio, crearemos una clase estática de utilidad.

Crea la clase `SecurityUtils` en un nuevo paquete llamado `utils` y copia este código. Analiza bien qué hace cada método.

```java
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

    // 2. CODIFICACIÓN A TEXTO (Para poder enviar las claves en JSON por la red P2P)
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

```

---

## 💼 Paso 2: La verdadera Wallet

Nuestra wallet ya no puede ser solo un nombre aleatorio. Ahora debe custodiar el **Llavero** del usuario.

Crea la clase `Wallet.java` en el paquete `wallet` (¡Ojo, es un objeto de dominio (tipo), no un Verticle!).

```java
package es.us.dad.vertx.wallet;

import es.us.dad.vertx.utils.SecurityUtils;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public class Wallet {
    private PrivateKey privateKey;
    private PublicKey publicKey;

    public Wallet() {
        // Al nacer, la wallet genera su par de claves
        KeyPair pair = SecurityUtils.generateECKeyPair();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
    }

    // La "Dirección" pública es la Clave Pública codificada en Base64
    public String getAddress() {
        return SecurityUtils.encodeKey(publicKey);
    }

    /**
     * todo: Implementa este método. Debe crear una nueva Transaction, 
     * calcular su hash, firmarlo con tu privateKey y asignar la firma a la TX.
     */
    public Transaction sendFunds(String receiver, long amount) {
        // 1. Instanciamos la TX. El constructor ya le asigna Timestamp y Hash inicial.
        Transaction newTx = new Transaction(this.getAddress(), receiver, amount);

        // 2. Firmamos el Hash (ID) con nuestra llave privada
        byte[] signature = SecurityUtils.applyECDSASig(this.privateKey, newTx.getTransactionId());

        // 3. Convertimos los bytes de la firma a String Base64 para poder enviarla en JSON
        newTx.setSignature(Base64.getEncoder().encodeToString(signature));

        return newTx;
    }
}

```

---

## 📝 Paso 3: Modificando la Transacción y el Misterio de la Coinbase

Ve a tu clase `Transaction.java`. Necesitamos hacer dos cosas vitales:

1. **Asegurar que el Hash incluya todos los datos**: En tu `calculateHash()`, asegúrate de que usas `timestamp`.
2. **Añadir el método de verificación**: El minero usará este método para comprobar si la transacción es legítima.

Añade este método a `Transaction.java`:

```java
    public boolean verifySignature() {
        // Excepción de sistema: Las CoinbaseTransactions no se verifican por ECDSA
        if (this.sender.equals("COINBASE_SYSTEM")) {
            return true;
        }

        if (this.signature == null || this.signature.isEmpty()) {
            return false;
        }

        try {
            PublicKey pubKey = SecurityUtils.decodePublicKey(this.sender);
            byte[] sigBytes = java.util.Base64.getDecoder().decode(this.signature);
            // Comprobamos si el Hash de la TX fue firmado por la clave pública del sender
            return SecurityUtils.verifyECDSASig(pubKey, this.transactionId, sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

```

### 🛑 ATENCIÓN TEÓRICA: La CoinbaseTransaction

Fíjate en el `if` especial del método anterior. ¿Por qué ignoramos la firma si el sender es `"COINBASE_SYSTEM"`?

La `CoinbaseTransaction` (que ya tienes programada del laboratorio anterior) es la transacción más importante de la red. **Es la única forma de crear dinero nuevo.**

* **¿Quién la crea?** El Minero, cuando logra resolver el Proof of Work.
* **¿Quién envía el dinero?** Nadie. El dinero se "mina" (se crea de la nada). Por eso su `sender` es `"COINBASE_SYSTEM"`.
* **¿Cómo se firma?** Como no hay un humano detrás enviando el dinero, **no hay Clave Privada**. El campo `signature` de una Coinbase no contiene una firma ECDSA, sino un mensaje de texto libre (ej: *"Mined by Node 1"*) que además sirve como un Nonce extra para alterar el hash del bloque.

Por tanto, al validar firmas en el P2P, debemos dejar pasar la Coinbase como una excepción válida por diseño.

---

## 🚀 Paso 4: Actualizando el `WalletVerticle`

Ahora que tenemos una entidad `Wallet` funcional, vamos a modificar nuestro generador de tráfico para que envíe transacciones criptográficamente válidas.

Sustituye la lógica de tu `WalletVerticle` actual:

```java
public class WalletVerticle extends AbstractVerticle {

    private Wallet myWallet;

    @Override
    public void start() {
        this.identity = "Wallet-" + UUID.randomUUID().toString().substring(0, 4);

        // Instanciamos la Wallet (generará sus claves automáticamente)
        this.myWallet = new Wallet();

        System.out.println("💰 " + this.identity + " iniciada.");
        System.out.println("🔑 Mi dirección pública: " + myWallet.getAddress().substring(0, 20) + "...");

        vertx.setPeriodic(5000, id -> generateAndBroadcastTransaction());
    }

    private void generateAndBroadcastTransaction() {
        // 1. SOLUCIÓN AL TODO: Crear TX firmada desde la Wallet
        Transaction tx = myWallet.sendFunds("Bob", 10);

        System.out.println("💸 " + this.identity + " generando TX firmada: " + tx.getTransactionId().substring(0,8) + "...");

        // 2. Convertir a JSON
        JsonObject transactionData = tx.toJson();

        // 3. Enviar localmente al minero (EventBus interno)
        vertx.eventBus().publish(BusAddresses.NEW_TRANSACTION, transactionData);

        // 4. Preparar el envoltorio Gossip para el P2PManager y enviarlo a la red
        JsonObject p2pMessage = new JsonObject()
                .put("type", "TRANSACTION")
                .put("hash", tx.getTransactionId())
                .put("data", transactionData);

        vertx.eventBus().publish(BusAddresses.BROADCAST_REQUEST, p2pMessage);
    }
}

```

---

## 🛡️ Paso 5: El Minero como Guardián (`MinerVerticle`)

Nuestra red P2P (`P2PConnectionManager`) reenvía todo lo que recibe, pero el `MinerVerticle` es quien decide qué entra en la **Mempool** (la sala de espera de transacciones).

Ve a tu `MinerVerticle` y busca el método `addTransactionToPool(JsonObject txJson)`. Modifícalo para que ejecute nuestra nueva validación:

```java
    private void addTransactionToPool(JsonObject txJson) {
        Transaction tx = new Transaction(txJson);

        // 🛡️ BARRERA CRIPTOGRÁFICA
        if (!tx.verifySignature()) {
            System.err.println("🚨 HACKER DETECTADO: Firma inválida en la TX " + tx.getTransactionId());
            return; // Descartamos la transacción inmediatamente
        }

        // Evitar duplicados simples
        if (transactionPool.stream().anyMatch(t -> t.getTransactionId().equals(tx.getTransactionId()))) {
            return;
        }

        transactionPool.add(tx);
        System.out.println("📥 TX válida añadida a Mempool. Total: " + transactionPool.size() + "/" + BLOCK_SIZE);

        if (transactionPool.size() >= BLOCK_SIZE) {
            mineBlock();
        }
    }

```

---

## 🧪 Pruebas de Éxito

Para comprobar que habéis implementado todo correctamente:

1. Arranca el `Node 1` y el `Node 2`.
2. Las Wallets empezarán a generar transacciones con firmas kilométricas en Base64.
3. El Minero del otro nodo debe imprimir `📥 TX válida añadida a Mempool`.
4. **La Prueba del Algodón (Para nota):** Modifica el `WalletVerticle` para que, justo antes de enviar la transacción, cambies la cantidad (`json.put("amount", 1000)`). Observa cómo el Minero receptor grita `🚨 HACKER DETECTADO`, demostrando que tu red P2P ahora es criptográficamente segura.