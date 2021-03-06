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

package org.eclipse.jetty.websocket.server.misbehaving;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Testing badly behaving Socket class implementations to get the best
 * error messages and state out of the websocket implementation.
 */
public class MisbehavingClassTest
{
    private static SimpleServletServer server;
    private static BadSocketsServlet badSocketsServlet;
    private static BlockheadClient client;

    @BeforeClass
    public static void startServer() throws Exception
    {
        badSocketsServlet = new BadSocketsServlet();
        server = new SimpleServletServer(badSocketsServlet);
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @BeforeClass
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterClass
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testListenerRuntimeOnConnect() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "listener-runtime-connect");
        request.idleTimeout(1, TimeUnit.SECONDS);

        ListenerRuntimeOnConnectSocket socket = badSocketsServlet.listenerRuntimeConnect;
        socket.reset();

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging ignore = new StacklessLogging(ListenerRuntimeOnConnectSocket.class, WebSocketSession.class))
        {
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));

            clientConn.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Close Latch",socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("closeStatusCode",socket.closeStatusCode,is(StatusCode.SERVER_ERROR));

            // Validate errors
            assertThat("socket.onErrors",socket.errors.size(),is(1));
            Throwable cause = socket.errors.pop();
            assertThat("Error type",cause,instanceOf(RuntimeException.class));
        }
    }
    
    @Test
    public void testAnnotatedRuntimeOnConnect() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "annotated-runtime-connect");
        request.idleTimeout(1, TimeUnit.SECONDS);

        AnnotatedRuntimeOnConnectSocket socket = badSocketsServlet.annotatedRuntimeConnect;
        socket.reset();

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging scope = new StacklessLogging(AnnotatedRuntimeOnConnectSocket.class, WebSocketSession.class))
        {
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));

            clientConn.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Close Latch",socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("closeStatusCode",socket.closeStatusCode,is(StatusCode.SERVER_ERROR));

            // Validate errors
            assertThat("socket.onErrors",socket.errors.size(),is(1));
            Throwable cause = socket.errors.pop();
            assertThat("Error type",cause,instanceOf(RuntimeException.class));
        }
    }
}
