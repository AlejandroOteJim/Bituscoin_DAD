package es.us.dad.vertx;

import io.vertx.core.Vertx;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Paths;

public class BituscoinLauncher {

    public static void main(String[] args) {
        // 1. Comprobar argumentos
        if (args.length < 1) {
            System.err.println("❌ Uso: java BituscoinLauncher <ruta_fichero_config.json>");
            System.err.println("   Ejemplo: java BituscoinLauncher conf/node1.json");
            System.exit(1);
        }

        String configFilePath = args[0];

        // 2. Crear instancia de Vert.x
        Vertx vertx = Vertx.vertx();

        try {
            // 3. Leer fichero de configuración
            String fileContent = new String(Files.readAllBytes(Paths.get(configFilePath)));
            JsonObject config = new JsonObject(fileContent);

            System.out.println("📂 Cargando configuración desde: " + configFilePath);

            // 4. Desplegar el MainVerticle con esa configuración
            DeploymentOptions options = new DeploymentOptions().setConfig(config);

            vertx.deployVerticle(new MainVerticle(), options).onComplete(res -> {
                if (res.succeeded()) {
                    System.out.println("✅ MainVerticle desplegado ID: " + res.result());
                } else {
                    System.err.println("❌ Fallo en despliegue: " + res.cause());
                    vertx.close(); // Cerrar si falla
                }
            });

        } catch (Exception e) {
            System.err.println("❌ Error leyendo el fichero de configuración: " + e.getMessage());
            e.printStackTrace();
        }
    }
}