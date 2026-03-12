package es.us.dad.vertx.wallet;

import es.us.dad.vertx.entities.Transaction;
import es.us.dad.vertx.network.BusAddresses;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import java.util.Random;
import java.util.UUID;

public class WalletVerticle extends AbstractVerticle {

    // TODO: Implementar la lógica de la Wallet aquí.
    // La Wallet debe ser capaz de:
    // 1. Generar su propia clave pública/privada (puede ser un String simulado por ahora).
    // 2. Crear nuevas transacciones (generar un Transaction con sender=su clave pública, receiver=otra clave pública, amount=lo que quiera).
    // 3. Enviar las transacciones al Bus de Vert.x para que sean procesadas por el nodo (NodeVerticle).
}
