/**
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
package de.fhg.camel.ids.both;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.AvailablePortFinder;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.camel.util.jsse.ClientAuthentication;
import org.apache.camel.util.jsse.KeyManagersParameters;
import org.apache.camel.util.jsse.KeyStoreParameters;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.apache.camel.util.jsse.SSLContextServerParameters;
import org.apache.camel.util.jsse.TrustManagersParameters;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.fhg.camel.ids.client.TestServletFactory;
import de.fhg.camel.ids.client.WsComponent;
import de.fhg.camel.ids.server.WebsocketComponent;

/**
 *
 */
public class WsProducerConsumerTest extends CamelTestSupport {
    protected static final String TEST_MESSAGE = "Hello World!";
    protected static final int PORT = AvailablePortFinder.getNextAvailable();
    protected Server server;
    private static File socketFile;
    private static String DOCKER_CLI ="docker";
    private static String DOCKER_IMAGE = "registry.netsec.aisec.fraunhofer.de/ids/tpm2dsim:latest";
    private static String SOCKET = "control.sock";
    private static String SOCKET_PATH = "tpm2sim/socket/" + SOCKET;
    protected List<Object> messages;
	private static String PWD = "changeit";
	
	@BeforeClass
    public static void initSimServer() throws InterruptedException, IOException {
		WsProducerConsumerTest.kill("wspc");
		socketFile = new File(SOCKET_PATH);
		String folder = socketFile.getAbsolutePath().substring(0, socketFile.getAbsolutePath().length() - SOCKET.length());
		// pull the image
		new ProcessBuilder().redirectInput(Redirect.INHERIT).command(Arrays.asList(DOCKER_CLI, "pull", DOCKER_IMAGE)).start().waitFor(60, TimeUnit.SECONDS);
    	// then start the docker image
		new ProcessBuilder().redirectInput(Redirect.INHERIT).command(Arrays.asList(DOCKER_CLI, "run", "--name", "wspc", "-v", folder +":/data/cml/communication/tpm2d/", DOCKER_IMAGE, "/tpm2d/start.sh")).start().waitFor(5, TimeUnit.SECONDS);
    }
	
	@AfterClass
    public static void teardownSimServer() throws Exception {
		WsProducerConsumerTest.kill("wspc");
		socketFile.delete();
    }
	
	private static void kill(String id) throws InterruptedException, IOException {
		// pull the image
		new ProcessBuilder().redirectInput(Redirect.INHERIT).command(Arrays.asList(DOCKER_CLI, "stop", id)).start().waitFor(4, TimeUnit.SECONDS);
    	// pull the image
		new ProcessBuilder().redirectInput(Redirect.INHERIT).command(Arrays.asList(DOCKER_CLI, "rm", id)).start().waitFor(4, TimeUnit.SECONDS);
	}
	
    
    @Override
    public void setUp() throws Exception {
    	setupServer();
        super.setUp();
    }
    
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        stopTestServer();
    }
    
    public void stopTestServer() throws Exception {
        server.stop();
        server.destroy();
    }    
    
	protected void setupServer() throws Exception {
        // start a simple websocket echo service
        server = new Server(PORT);
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(TestServletFactory.class.getName(), "/*");

        server.setHandler(ctx);
        
        server.start();
        assertTrue(server.isStarted());      
    }
    
	@Test
    public void testTwoRoutes() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived(TEST_MESSAGE);

        template.sendBody("direct:input", TEST_MESSAGE);

        mock.assertIsSatisfied();
    }

    @Test
    public void testTwoRoutesRestartConsumer() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived(TEST_MESSAGE);

        template.sendBody("direct:input", TEST_MESSAGE);

        mock.assertIsSatisfied();

        resetMocks();

        log.info("Restarting bar route");
        context.stopRoute("bar");
        Thread.sleep(500);
        context.startRoute("bar");

        mock.expectedBodiesReceived(TEST_MESSAGE);

        template.sendBody("direct:input", TEST_MESSAGE);

        mock.assertIsSatisfied();
    }
    
    private static SSLContextParameters defineClientSSLContextClientParameters() {

        KeyStoreParameters ksp = new KeyStoreParameters();
        ksp.setResource("jsse/localhost.ks");
        ksp.setPassword(PWD);

        KeyManagersParameters kmp = new KeyManagersParameters();
        kmp.setKeyPassword(PWD);
        kmp.setKeyStore(ksp);

        TrustManagersParameters tmp = new TrustManagersParameters();
        tmp.setKeyStore(ksp);

        // NOTE: Needed since the client uses a loose trust configuration when no ssl context
        // is provided.  We turn on WANT client-auth to prefer using authentication
        SSLContextServerParameters scsp = new SSLContextServerParameters();
        scsp.setClientAuthentication(ClientAuthentication.NONE.name());	//TODO CHANGE TO REQUIRE

        SSLContextParameters sslContextParameters = new SSLContextParameters();
        sslContextParameters.setKeyManagers(kmp);
        sslContextParameters.setTrustManagers(tmp);
        sslContextParameters.setServerParameters(scsp);

        return sslContextParameters;
    }
    
    private static SSLContextParameters defineServerSSLContextParameters() {
    	   KeyStoreParameters ksp = new KeyStoreParameters();
           // ksp.setResource(this.getClass().getClassLoader().getResource("jsse/localhost.ks").toString());
           ksp.setResource("jsse/localhost.ks");
           ksp.setPassword(PWD);

           KeyManagersParameters kmp = new KeyManagersParameters();
           kmp.setKeyPassword(PWD);
           kmp.setKeyStore(ksp);

           TrustManagersParameters tmp = new TrustManagersParameters();
           tmp.setKeyStore(ksp);

           // NOTE: Needed since the client uses a loose trust configuration when no ssl context
           // is provided.  We turn on WANT client-auth to prefer using authentication
           SSLContextServerParameters scsp = new SSLContextServerParameters();
           scsp.setClientAuthentication(ClientAuthentication.NONE.name());

           SSLContextParameters sslContextParameters = new SSLContextParameters();
           sslContextParameters.setKeyManagers(kmp);
           sslContextParameters.setTrustManagers(tmp);
           sslContextParameters.setServerParameters(scsp);

           return sslContextParameters;
    }
    
    @Override
    protected RouteBuilder[] createRouteBuilders() throws Exception {
        RouteBuilder[] rbs = new RouteBuilder[2];
        
        // An IDS consumer
        rbs[0] = new RouteBuilder() {
            public void configure() {
        		
            	// Needed to configure TLS on the client side
		        WsComponent wsComponent = (WsComponent) context.getComponent("idsclientplain");
//		        wsComponent.setSslContextParameters(defineClientSSLContextClientParameters());

		        from("direct:input").routeId("foo")
                	.log(">>> Message from direct to WebSocket Client : ${body}")
                	.to("idsclientplain://localhost:9292/echo")
                    .log(">>> Message from WebSocket Client to server: ${body}");
                }
        };
        
        // An IDS provider
        rbs[1] = new RouteBuilder() {
            public void configure() {
            	
            		// Needed to configure TLS on the server side
            		WebsocketComponent websocketComponent = (WebsocketComponent) context.getComponent("idsserver");
//					websocketComponent.setSslContextParameters(defineServerSSLContextParameters());

					// This route is set to use TLS, referring to the parameters set above
                    from("idsserver:localhost:9292/echo")
                    .log(">>> Message from WebSocket Server to mock: ${body}")
                	.to("mock:result");
            }
        };
        return rbs;
    }
}
