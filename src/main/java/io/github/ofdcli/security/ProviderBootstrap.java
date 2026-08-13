/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ofdcli.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/**
 * Registers the BouncyCastle JCA provider at image startup so the ofd-cli
 * {@code sign}, {@code verify} and {@code validate} subcommands work in
 * GraalVM native-image as well as on a stock JVM.
 *
 * <h2>Why a static initializer</h2>
 *
 * GraalVM native-image replaces {@link Security} with
 * {@code Target_java_security_Security} whose {@code providers} field is
 * populated at image startup, not at build time. Calling
 * {@code Security.addProvider(...)} from a {@code Feature.beforeAnalysis}
 * callback mutates the build-time Security class state and the change
 * does <strong>not</strong> survive into the image heap. Embedding the
 * call in a runtime class's {@code <clinit>} — which native-image runs at
 * image startup — does work, and is the same pattern used by
 * <a href="https://github.com/quarkusio/quarkus/pull/23527">Quarkus
 * security deployment</a>.
 *
 * <h2>Why this class lives in ofd-cli and not ofdrw</h2>
 *
 * ofdrw's policy is to keep its library code JVM-only; the GraalVM
 * integration is the application's responsibility. This class therefore
 * stays in the ofd-cli source tree and is only loaded for the
 * {@code sign}/{@code verify}/{@code validate} subcommands.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // at the very top of Main.main(), before any other class loads:
 * Class.forName("io.github.ofdcli.security.ProviderBootstrap");
 * }</pre>
 *
 * Calling {@link #bootstrap()} explicitly is also safe and is a no-op
 * if the provider is already registered.
 */
public final class ProviderBootstrap {

    /** Standard name of the BouncyCastle JCA provider. */
    public static final String BC = "BC";

    static {
        // Idempotent: Security.addProvider returns -1 if already present,
        // and we also short-circuit via the lookup below.
        if (Security.getProvider(BC) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private ProviderBootstrap() {
        // utility
    }

    /**
     * Force the provider registration by triggering this class's
     * {@code <clinit>}. Safe to call multiple times.
     */
    public static void bootstrap() {
        // Touching the class object is enough; the static initializer
        // runs on first reference. We don't need to do anything here.
    }
}
