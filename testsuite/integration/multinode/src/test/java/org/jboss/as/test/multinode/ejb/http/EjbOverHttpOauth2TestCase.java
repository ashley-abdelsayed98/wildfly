/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.multinode.ejb.http;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ALLOW_RESOURCE_SERVICE_RESTART;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.test.shared.integration.ejb.security.PermissionUtils.createFilePermission;
import static org.jboss.as.test.shared.integration.ejb.security.PermissionUtils.createPermissionsXmlAsset;

import java.io.IOException;
import java.util.Arrays;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.naming.InitialContext;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.test.integration.management.ManagementOperations;

import org.jboss.as.test.shared.ServerReload;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.extension.elytron.ElytronExtension;
import org.wildfly.test.security.common.elytron.EjbElytronHttpDomainSetup;
import org.wildfly.test.security.common.elytron.ElytronTokenDomainSetup;
import org.wildfly.test.security.common.elytron.ServletElytronDomainSetup;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.internal.Internal;
import okhttp3.internal.ws.RealWebSocket;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okio.BufferedSource;
import okio.Sink;
import okio.Source;

/**
 * This test che
 *
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */
@RunWith(Arquillian.class)
@ServerSetup({EjbOverHttpOauth2TestCase.EjbOverHttpTestCaseServerSetup.class})
public class EjbOverHttpOauth2TestCase {
    private static final Logger log = Logger.getLogger(EjbOverHttpTestCase.class);
    public static final String ARCHIVE_NAME_SERVER = "ejboverhttp-test-server";
    public static final String ARCHIVE_NAME_CLIENT = "ejboverhttp-test-client";
    public static final int NO_EJB_RETURN_CODE = -1;

    protected static final String DEFAULT_SECURITY_DOMAIN_NAME = "ejb3-tests";
    protected static final String DEFAULT_SECURITY_REALM_NAME = "ejb-token-realm";
    protected static final String DEFAULT_SECURITY_HTTP_AUTHENTICATION_NAME = "ejb-http-auth-factory";

    public static final MockWebServer server = new MockWebServer();

    @ArquillianResource
    private Deployer deployer;

    @BeforeClass
    public static void onBefore() throws Exception {
        server.setDispatcher(createTokenEndpoint());
        server.start(50831);
    }

    static class EjbOverHttpTestCaseServerSetup implements ServerSetupTask {

        @Override
        public void setup(final ManagementClient managementClient, final String containerId) throws Exception {
            final ModelNode address = new ModelNode();
            address.add("subsystem", "ejb3");
            address.add("remoting-profile", "test-profile");
            address.protect();

            final ModelNode op1 = new ModelNode();
            op1.get(OP).set("add");
            op1.get(OP_ADDR).add(SUBSYSTEM, "ejb3");
            op1.get(OP_ADDR).add("remoting-profile", "test-profile");
            op1.get(OP_ADDR).set(address);

            op1.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);

            ManagementOperations.executeOperation(managementClient.getControllerClient(), op1);

            ModelNode op2 = new ModelNode();
            op2.get(OP).set(ADD);
            op2.get(OP_ADDR).add(SUBSYSTEM, "ejb3");
            op2.get(OP_ADDR).add("remoting-profile", "test-profile");
            op2.get(OP_ADDR).add("remote-http-connection", "test-connection");

            op2.get("uri").set("http://localhost:8180/wildfly-services");

            op2.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);

            ManagementOperations.executeOperation(managementClient.getControllerClient(), op2);

            ModelNode addRealm = new ModelNode();
            addRealm.get(OP).set("add");
            addRealm.get(OP_ADDR).add(SUBSYSTEM, ElytronExtension.SUBSYSTEM_NAME);
            addRealm.get(OP_ADDR).add("token-realm", getSecurityRealmName());
            addRealm.get("principal-claim").set("preferred_username");
            addRealm.get("oauth2-introspection").get("client-id").set("elytron-client");
            addRealm.get("oauth2-introspection").get("client-secret").set("dont_tell_me");
            addRealm.get("oauth2-introspection").get("introspection-url").set("http://localhost:50831/token");
            addRealm.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), addRealm);

            ModelNode addDomain = new ModelNode();
            addDomain.get(OP).set("add");
            addDomain.get(OP_ADDR).add(SUBSYSTEM, ElytronExtension.SUBSYSTEM_NAME);
            addDomain.get(OP_ADDR).add("security-domain", getSecurityDomainName());
            addDomain.get("default-realm").set(getSecurityRealmName());
            addDomain.get("realms").get(0).get("realm").set(getSecurityRealmName());
            addDomain.get("realms").get(0).get("role-decoder").set("groups-to-roles"); // use attribute "groups" as roles (defined in standalone-elytron.xml)
            addDomain.get("realms").get(1).get("realm").set("local");
            addDomain.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), addDomain);

            ModelNode addHttpAuthentication = new ModelNode();
            addHttpAuthentication.get(OP).set("add");
            addHttpAuthentication.get(OP_ADDR).add(SUBSYSTEM, ElytronExtension.SUBSYSTEM_NAME);
            addHttpAuthentication.get(OP_ADDR).add("http-authentication-factory", getHttpAuthenticationName());
            addHttpAuthentication.get("http-server-mechanism-factory").set("global");
            addHttpAuthentication.get("security-domain").set(getSecurityDomainName());
            addHttpAuthentication.get("mechanism-configurations").get(0).get("mechanism-name").set("BEARER");
            addHttpAuthentication.get("mechanism-configurations").get(0).get("mechanism-realm-configurations").get(0).get("realm-name").set(getSecurityRealmName());
            addHttpAuthentication.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), addHttpAuthentication);

            ModelNode removeUndertowHttpAuthentication = new ModelNode();
            removeUndertowHttpAuthentication.get(OP).set("undefine-attribute");
            removeUndertowHttpAuthentication.get(OP_ADDR).add(SUBSYSTEM, "undertow");
            removeUndertowHttpAuthentication.get(OP_ADDR).add("server", "default-server");
            removeUndertowHttpAuthentication.get(OP_ADDR).add("host", "default-host");
            removeUndertowHttpAuthentication.get(OP_ADDR).add("setting", "http-invoker");
            removeUndertowHttpAuthentication.get("name").set("security-realm");
            removeUndertowHttpAuthentication.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), removeUndertowHttpAuthentication);

            ModelNode addUndertowHttpAuthentication = new ModelNode();
            addUndertowHttpAuthentication.get(OP).set("write-attribute");
            addUndertowHttpAuthentication.get(OP_ADDR).add(SUBSYSTEM, "undertow");
            addUndertowHttpAuthentication.get(OP_ADDR).add("server", "default-server");
            addUndertowHttpAuthentication.get(OP_ADDR).add("host", "default-host");
            addUndertowHttpAuthentication.get(OP_ADDR).add("setting", "http-invoker");
            addUndertowHttpAuthentication.get("name").set("http-authentication-factory");
            addUndertowHttpAuthentication.get("value").set(getHttpAuthenticationName());
            addUndertowHttpAuthentication.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), addUndertowHttpAuthentication);

            ModelNode modifyUndertowHttpAuthentication = new ModelNode();
            modifyUndertowHttpAuthentication.get(OP).set("write-attribute");
            modifyUndertowHttpAuthentication.get(OP_ADDR).add(SUBSYSTEM, "undertow");
            modifyUndertowHttpAuthentication.get(OP_ADDR).add("server", "default-server");
            modifyUndertowHttpAuthentication.get(OP_ADDR).add("host", "default-host");
            modifyUndertowHttpAuthentication.get(OP_ADDR).add("setting", "http-invoker");
            modifyUndertowHttpAuthentication.get("name").set("path");
            modifyUndertowHttpAuthentication.get("value").set("wildfly-services");
            modifyUndertowHttpAuthentication.get(OPERATION_HEADERS).get(ALLOW_RESOURCE_SERVICE_RESTART).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), modifyUndertowHttpAuthentication);
            ServerReload.reloadIfRequired(managementClient);

        }

        @Override
        public void tearDown(final ManagementClient managementClient, final String containerId) throws Exception {
            ModelNode op = new ModelNode();
            op.get(OP).set(REMOVE);
            op.get(OP_ADDR).add(SUBSYSTEM, "ejb3");
            op.get(OP_ADDR).add("remoting-profile", "test-profile");
            ManagementOperations.executeOperation(managementClient.getControllerClient(), op);
            try {
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        protected String getSecurityDomainName() {
            return DEFAULT_SECURITY_DOMAIN_NAME;
        }

        protected String getSecurityRealmName() {
            return DEFAULT_SECURITY_REALM_NAME;
        }

        protected String getHttpAuthenticationName() {
            return DEFAULT_SECURITY_HTTP_AUTHENTICATION_NAME;
        }
    }


    private static Dispatcher createTokenEndpoint() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest recordedRequest) throws InterruptedException {
                String body = recordedRequest.getBody().readUtf8();
                boolean resourceOwnerCredentials = body.contains("grant_type=password");
                boolean clientCredentials = body.contains("grant_type=client_credentials");

                if (resourceOwnerCredentials
                        && (body.contains("client_id=elytron-client") && body.contains("client_secret=dont_tell_me"))
                        && (body.contains("username=alice") || body.contains("username=jdoe"))
                        && body.contains("password=dont_tell_me")) {
                    JsonObjectBuilder tokenBuilder = Json.createObjectBuilder();

                    tokenBuilder.add("access_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiaXNzIjoiYXV0aC5zZXJ2ZXIiLCJhdWQiOiJmb3JfbWUiLCJleHAiOjE3NjA5OTE2MzUsInByZWZlcnJlZF91c2VybmFtZSI6Impkb2UifQ.SoPW41_mOFnKXdkwVG63agWQ2k09dEnEtTBztnxHN64");

                    return new MockResponse().setBody(tokenBuilder.build().toString());
                } else if (clientCredentials
                        && (body.contains("client_id=elytron-client") && body.contains("client_secret=dont_tell_me"))
                        && !body.contains("username=")) {
                    JsonObjectBuilder tokenBuilder = Json.createObjectBuilder();

                    tokenBuilder.add("access_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiaXNzIjoiYXV0aC5zZXJ2ZXIiLCJhdWQiOiJmb3JfbWUiLCJleHAiOjE3NjA5OTE2MzUsInByZWZlcnJlZF91c2VybmFtZSI6Impkb2UifQ.SoPW41_mOFnKXdkwVG63agWQ2k09dEnEtTBztnxHN64");

                    return new MockResponse().setBody(tokenBuilder.build().toString());
                }

                return new MockResponse().setResponseCode(400);
            }
        };
    }


    @BeforeClass
    public static void printSysProps() {
        log.trace("System properties:\n" + System.getProperties());
    }

    @Deployment(name = "server", managed = false)
    @TargetsContainer("multinode-server")
    public static Archive<?> deployment0() {
        JavaArchive jar = createJar(ARCHIVE_NAME_SERVER);
        return jar;
    }

    @Deployment(name = "client")
    @TargetsContainer("multinode-client")
    public static Archive<?> deployment1() {
        JavaArchive clientJar = createClientJar()
                .addClass(Dispatcher.class)
                .addClass(ElytronTokenDomainSetup.class)
                .addClass(EjbElytronHttpDomainSetup.class)
                .addClass(ServletElytronDomainSetup.class)
                .addClass(MockWebServer.class)
                .addClass(QueueDispatcher.class)
                .addClass(Sink.class)
                .addClass(WebSocket.class)
                .addClass(RealWebSocket.class)
                .addClass(BufferedSource.class)
                .addClass(Internal.class)
                .addClass(Source.class)
                .addClass(OkHttpClient.class)
                .addClass(Call.class)
                .addPackage("okio")
                .addPackage("okhttp3")
                .addPackage("okhttp3.mockwebserver")
                .addPackage("okhttp3.internal")
                .addPackage("okhttp3.internal.platform")
                .addPackage("okhttp3.internal.connection")
                .addPackage("okhttp3.internal.tls");

        return clientJar;
    }

    private static JavaArchive createJar(String archiveName) {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class, archiveName + ".jar");
        jar.addClasses(StatelessBean.class, StatelessLocal.class, StatelessRemote.class);
        return jar;
    }

    private static JavaArchive createClientJar() {
        JavaArchive jar = createJar(EjbOverHttpTestCase.ARCHIVE_NAME_CLIENT);
        jar.addClasses(EjbOverHttpTestCase.class);
        jar.addAsManifestResource("META-INF/jboss-ejb-client-profile.xml", "jboss-ejb-client.xml")
                .addAsManifestResource("ejb-http-oauth2-wildfly-config.xml", "wildfly-config.xml")
                .addAsManifestResource(createPermissionsXmlAsset(createFilePermission("read,write",
                        "jbossas.multinode.client", Arrays.asList("standalone", "data", "ejb-xa-recovery")),
                        createFilePermission("read,write",
                                "jbossas.multinode.client", Arrays.asList("standalone", "data", "ejb-xa-recovery", "-"))),
                        "permissions.xml");
        return jar;
    }

    @Test
    @OperateOnDeployment("client")
    public void testBasicInvocation(@ArquillianResource InitialContext ctx) throws Exception {
        deployer.deploy("server");

        StatelessRemote bean = (StatelessRemote) ctx.lookup("java:module/" + StatelessBean.class.getSimpleName() + "!"
                + StatelessRemote.class.getName());
        Assert.assertNotNull(bean);

        // initial discovery
        int methodCount = bean.remoteCall();
        Assert.assertEquals(1, methodCount);

        deployer.undeploy("server");

        //  failed discovery after undeploying server deployment
        int returnValue = bean.remoteCall();
        Assert.assertEquals(NO_EJB_RETURN_CODE, returnValue);

        deployer.deploy("server");

        // rediscovery after redeployment
        methodCount = bean.remoteCall();
        Assert.assertEquals(1, methodCount);
    }
}