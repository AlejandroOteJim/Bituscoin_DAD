package es.us.dad.vertx;

import es.us.dad.vertx.entities.BlockChain;
import es.us.dad.vertx.miner.MinerVerticle;
import es.us.dad.vertx.network.BlockValidator;
import es.us.dad.vertx.network.P2PConnectionManager;
import es.us.dad.vertx.wallet.WalletVerticle;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> startPromise) {
        DeploymentOptions options = new DeploymentOptions().setConfig(config());
        BlockChain sharedBlockchain = new BlockChain(); //Para que el validador y el minero compartan el blockchain
        Future<String> p2pDeploy = vertx.deployVerticle(new P2PConnectionManager(), options);
        Future<String> minerDeploy = vertx.deployVerticle(new MinerVerticle(sharedBlockchain), options);
        Future<String> walletDeploy = vertx.deployVerticle(new WalletVerticle(), options);
        Future<String> validatorDeploy = vertx.deployVerticle(new BlockValidator(sharedBlockchain));

        Future.all(validatorDeploy,p2pDeploy, minerDeploy, walletDeploy).onComplete(res -> {
            if (res.succeeded()) {
                System.out.println("\n🚀 =======================================");
                System.out.println("   NODO BITUSCOIN INICIADO CORRECTAMENTE");
                // ... resto de logs ...
                System.out.println("========================================\n");
                startPromise.complete();
            } else {
                System.err.println("❌ Error fatal iniciando el nodo: " + res.cause().getMessage());
                startPromise.fail(res.cause());
            }
        });
    }
}