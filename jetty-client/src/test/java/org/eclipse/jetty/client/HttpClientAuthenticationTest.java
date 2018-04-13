//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    private String realm = "TestRealm";

    public void startBasic(final Scenario scenario, Handler handler) throws Exception
    {
        start(scenario, new BasicAuthenticator(), handler);
    }

    public void startDigest(final Scenario scenario, Handler handler) throws Exception
    {
        start(scenario, new DigestAuthenticator(), handler);
    }

    private void start(final Scenario scenario, Authenticator authenticator, Handler handler) throws Exception
    {
        server = new Server();
        File realmFile = MavenTestingUtils.getTestResourceFile("realm.properties");
        LoginService loginService = new HashLoginService(realm, realmFile.getAbsolutePath());
        server.addBean(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();

        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"**"}); //allow any authenticated user
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure");
        mapping.setConstraint(constraint);

        securityHandler.addConstraintMapping(mapping);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        securityHandler.setHandler(handler);
        start(scenario, securityHandler);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        test_Authentication(scenario, new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicEmptyRealm(Scenario scenario) throws Exception
    {
        realm = "";
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        test_Authentication(scenario, new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicAnyRealm(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        test_Authentication(scenario, new BasicAuthentication(uri, Authentication.ANY_REALM, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_DigestAuthentication(Scenario scenario) throws Exception
    {
        startDigest(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        test_Authentication(scenario, new DigestAuthentication(uri, realm, "digest", "digest"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_DigestAnyRealm(Scenario scenario) throws Exception
    {
        startDigest(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        test_Authentication(scenario, new DigestAuthentication(uri, Authentication.ANY_REALM, "digest", "digest"));
    }

    private void test_Authentication(final Scenario scenario, Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(1));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request without Authentication causes a 401
        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        authenticationStore.addAuthentication(authentication);

        requests.set(new CountDownLatch(2));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        requests.set(new CountDownLatch(1));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Further requests do not trigger 401 because there is a previous successful authentication
        // Remove existing header to be sure it's added by the implementation
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicAuthentication_ThenRedirect(Scenario scenario) throws Exception
    {
        startBasic(scenario, new AbstractHandler()
        {
            private final AtomicInteger requests = new AtomicInteger();

            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (requests.incrementAndGet() == 1)
                    response.sendRedirect(URIUtil.newURI(scenario.getScheme(), request.getServerName(), request.getServerPort(), request.getRequestURI(), null));
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_Redirect_ThenBasicAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().endsWith("/redirect"))
                    response.sendRedirect(URIUtil.newURI(scenario.getScheme(), request.getServerName(), request.getServerPort(), "/secure", null));
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/redirect")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicAuthentication_WithAuthenticationRemoved(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(2));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        authenticationStore.removeAuthentication(authentication);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        Authentication.Result result = authenticationStore.findAuthenticationResult(request.getURI());
        assertNotNull(result);
        authenticationStore.removeAuthenticationResult(result);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_BasicAuthentication_WithWrongPassword(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "wrong");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_Authentication_ThrowsException(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        // Request without Authentication would cause a 401,
        // but the client will throw an exception trying to
        // send the credentials to the server.
        final String cause = "thrown_explicitly_by_test";
        client.getAuthenticationStore().addAuthentication(new Authentication()
        {
            @Override
            public boolean matches(String type, URI uri, String realm)
            {
                return true;
            }

            @Override
            public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
            {
                throw new RuntimeException(cause);
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        assertTrue(result.isFailed());
                        assertEquals(cause, result.getFailure().getMessage());
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_PreemptedAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        authenticationStore.addAuthenticationResult(new BasicAuthentication.BasicResult(uri, "basic", "basic"));

        AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertEquals(200, response.getStatus());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_NonReproducibleContent(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        CountDownLatch resultLatch = new CountDownLatch(1);
        byte[] data = new byte[]{'h', 'e', 'l', 'l', 'o'};
        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(data))
        {
            @Override
            public boolean isReproducible()
            {
                return false;
            }
        };
        Request request = client.newRequest(uri)
                .path("/secure")
                .content(content);
        request.send(result ->
        {
            if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.UNAUTHORIZED_401)
                resultLatch.countDown();
        });

        content.close();

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_RequestFailsAfterResponse(Scenario scenario) throws Exception
    {        
        startBasic(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException, ServletException
            {
                IO.readBytes(jettyRequest.getInputStream());              
            }
        });
        
        CountDownLatch authLatch = new CountDownLatch(1);
        client.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
        client.getProtocolHandlers().put(new WWWAuthenticationProtocolHandler(client)
        {
            @Override
            public Listener getResponseListener()
            {
                Response.Listener listener = super.getResponseListener();
                return new Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        authLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        listener.onComplete(result);
                    }
                };
            }
        });
        
        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        AtomicBoolean fail = new AtomicBoolean(true);
        GeneratingContentProvider content = new GeneratingContentProvider(index ->
        {
            switch (index)
            {
                case 0:
                    return ByteBuffer.wrap(new byte[]{'h', 'e', 'l', 'l', 'o'});
                case 1:
                    return ByteBuffer.wrap(new byte[]{'w', 'o', 'r', 'l', 'd'});
                case 2:
                    if (fail.compareAndSet(true, false))
                    {
                        // Wait for the 401 response to arrive
                        try
                        {
                            authLatch.await();
                        }
                        catch(InterruptedException e)
                        {}
                        
                        // Trigger request failure.
                        throw new RuntimeException();
                    }
                    else
                    {
                        return null;
                    }
                    
                default:
                    throw new IllegalStateException();
            }
        });
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/secure")
                .content(content)
                .onResponseSuccess(r->authLatch.countDown())
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                        resultLatch.countDown();
                });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    private static class GeneratingContentProvider implements ContentProvider
    {
        private static final ByteBuffer DONE = ByteBuffer.allocate(0);

        private final IntFunction<ByteBuffer> generator;

        private GeneratingContentProvider(IntFunction<ByteBuffer> generator)
        {
            this.generator = generator;
        }

        @Override
        public long getLength()
        {
            return -1;
        }

        @Override
        public boolean isReproducible()
        {
            return true;
        }

        @Override
        public Iterator<ByteBuffer> iterator()
        {
            return new Iterator<ByteBuffer>()
            {
                private int index;
                public ByteBuffer current;

                @Override
                @SuppressWarnings("ReferenceEquality")
                public boolean hasNext()
                {
                    if (current == null)
                    {
                        current = generator.apply(index++);
                        if (current == null)
                            current = DONE;
                    }
                    return current != DONE;
                }

                @Override
                public ByteBuffer next()
                {
                    ByteBuffer result = current;
                    current = null;
                    if (result == null)
                        throw new NoSuchElementException();
                    return result;
                }
            };
        }
    }
}
