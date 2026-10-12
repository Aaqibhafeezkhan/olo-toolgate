// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.file.Files;
import java.util.logging.Logger;

/** Starts the helper. Refuses to start without a token, a signing key or the toolgate CLI. */
public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("seed-manifest")) {
            SeedManifest.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Config config;
        Signer signer;
        try {
            config = Config.fromEnvironment(System.getenv());
            if (!Files.isExecutable(config.toolgateCli())) {
                throw new IllegalStateException("toolgate CLI not found at " + config.toolgateCli());
            }
            signer = Signer.load(config.signingKeyFile(), config.signingKeyId(), config.retiredKeys());
        } catch (IllegalStateException e) {
            System.err.println("marketplace-helper: " + e.getMessage());
            System.exit(1);
            return;
        }
        HelperServer server = new HelperServer(config, new FileStore(config),
                new Inspector(config, new CliValidator(config), signer), signer);
        int port = server.start("0.0.0.0", config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        LOG.info("marketplace helper listening on " + port + "; marketplace " + config.marketplaceId() + "; key "
                + signer.keyId() + " " + Signer.fingerprint(signer.rawPublicKey()) + "; toolgate revision "
                + config.toolgateRevision());
    }
}
