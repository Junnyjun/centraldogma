/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.it.mirror.git;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitMirrorAuthTest {

    private static final Logger logger = LoggerFactory.getLogger(GitMirrorAuthTest.class);

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
        }
    };

    // To make this test cover all supported authentication schemes, the properties defined below need to be
    // set via System properties.
    //
    // Assuming that:
    // - you have read access to https://github.com/line/centraldogma-authtest
    // - your system username is identical to your GitHub.com username
    // - you have an SSH key pair
    //
    // The only properties you need to set to run this test are:
    // - git.password: your GitHub.com password or personal token if you are using 2-factor authentication
    // - git.privateKey: the path to your SSH private key
    // - git.publicKey: the path to your SSH public key
    // - git.passphrase: the passphrase of your encrypted SSH private key
    //                   (not needed if your private key is unencrypted)

    /**
     * The hostname of the git server.
     */
    private static final String GIT_HOST = System.getProperty("git.host", "github.com");

    /**
     * The port number of the git-over-HTTPS server.
     */
    private static final String GIT_PORT_HTTPS = System.getProperty("git.httpsPort", "443");

    /**
     * The port number of the git-over-SSH server.
     */
    private static final String GIT_PORT_SSH = System.getProperty("git.sshPort", "22");

    /**
     * The path of the remote git repository. e.g. /foo/bar.git
     */
    private static final String GIT_PATH = System.getProperty("git.path", "/line/centraldogma-authtest.git");

    /**
     * The username of the git-over-HTTPS server.
     */
    private static final String GIT_USERNAME_HTTPS = System.getProperty(
            "git.httpsUsername", System.getProperty("user.name"));

    /**
     * The username of the git-over-SSH server.
     */
    private static final String GIT_USERNAME_SSH = System.getProperty("git.sshUsername", "git");

    /**
     * The password. Required for password authentication.
     * If you are using 2-factor authentication in GitHub.com, you'll have to generate a personal token
     * and specify it here. See: https://github.com/settings/tokens
     */
    @Nullable
    private static final String GIT_PASSWORD = System.getProperty("git.password");

    /**
     * The path to the private key file. Required for public key authentication.
     */
    @Nullable
    private static final String GIT_PRIVATE_KEY = System.getProperty("git.privateKey");

    /**
     * The path to the public key file. Required for public key authentication.
     */
    @Nullable
    private static final String GIT_PUBLIC_KEY = System.getProperty("git.publicKey");

    /**
     * The passphrase of the private key file.
     */
    private static final String GIT_PASSPHRASE = System.getProperty("git.passphrase");

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void setUp() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arguments")
    void auth(String projName, String gitUri, JsonNode credential) {
        client.createProject(projName).join();
        client.createRepository(projName, "main").join();

        // Add /credentials.json and /mirrors.json
        final ArrayNode credentials = JsonNodeFactory.instance.arrayNode().add(credential);
        client.forRepo(projName, Project.REPO_META)
              .commit("Add a mirror",
                      Change.ofJsonUpsert("/credentials.json", credentials),
                      Change.ofJsonUpsert("/mirrors.json",
                                          "[{" +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"main\"," +
                                          "  \"localPath\": \"/\"," +
                                          "  \"remoteUri\": \"" + gitUri + '"' +
                                          "}]"))
              .push().join();

        // Try to perform mirroring to see if authentication works as expected.
        mirroringService.mirror().join();

        client.removeProject(projName).join();
    }

    private static Collection<Arguments> arguments() throws Exception {
        final ImmutableSet.Builder<Arguments> builder = ImmutableSet.builder();
        if (GIT_PASSWORD != null) {
            builder.add(Arguments.of(
                    "https",
                    "git+https://" + GIT_HOST + ':' + GIT_PORT_HTTPS + GIT_PATH,
                    Jackson.readTree(
                            '{' +
                            "  \"type\": \"password\"," +
                            "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                            "  \"username\": \"" + GIT_USERNAME_HTTPS + "\"," +
                            "  \"password\": \"" + Jackson.escapeText(GIT_PASSWORD) + '"' +
                            '}')));
        }

        // Test Git-over-SSH only when the public key and private key files are specified and readable.
        if (GIT_PRIVATE_KEY != null && new File(GIT_PRIVATE_KEY).canRead()) {
            final String gitPublicKey = GIT_PUBLIC_KEY != null ? GIT_PUBLIC_KEY : (GIT_PRIVATE_KEY + ".pub");
            if (new File(gitPublicKey).canRead()) {
                final byte[] privateKeyBytes = Files.readAllBytes(Paths.get(GIT_PRIVATE_KEY));
                final byte[] publicKeyBytes = Files.readAllBytes(Paths.get(gitPublicKey));
                final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8);
                final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8);

                // Test Git-over-SSH only when:
                // - the private key passphrase is specified or
                // - the private key is unencrypted.
                if (GIT_PASSPHRASE != null || !isEncrypted(privateKeyBytes, publicKeyBytes)) {
                    final String passphraseProperty;
                    if (GIT_PASSPHRASE != null) {
                        passphraseProperty = "\"passphrase\": \"" + Jackson.escapeText(GIT_PASSPHRASE) + '"';
                    } else {
                        passphraseProperty = "\"passphrase\": null";
                    }

                    builder.add(Arguments.of(
                            "ssh",
                            "git+ssh://" + GIT_HOST + ':' + GIT_PORT_SSH + GIT_PATH,
                            Jackson.readTree(
                                    '{' +
                                    "  \"type\": \"public_key\"," +
                                    "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                                    "  \"username\": \"" + GIT_USERNAME_SSH + "\"," +
                                    "  \"publicKey\": \"" + Jackson.escapeText(publicKey) + "\"," +
                                    "  \"privateKey\": \"" + Jackson.escapeText(privateKey) + "\"," +
                                    passphraseProperty +
                                    '}')));
                }
            }
        }

        final Collection<Arguments> arguments = builder.build();
        assumeThat(arguments).isNotEmpty();
        return arguments;
    }

    private static boolean isEncrypted(byte[] privateKeyBytes, byte[] publicKeyBytes) {
        try {
            return KeyPair.load(new JSch(), privateKeyBytes, publicKeyBytes).isEncrypted();
        } catch (JSchException e) {
            logger.warn("Failed to load the SSH key: {}", GIT_PRIVATE_KEY, e);
            return true;
        }
    }
}
