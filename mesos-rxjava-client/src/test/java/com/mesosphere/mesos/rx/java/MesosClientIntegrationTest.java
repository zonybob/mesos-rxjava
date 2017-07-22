/*
 *    Copyright (C) 2016 Mesosphere, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mesosphere.mesos.rx.java;

import com.mesosphere.mesos.rx.java.test.Async;
import com.mesosphere.mesos.rx.java.test.StringMessageCodec;
import com.mesosphere.mesos.rx.java.util.UserAgentEntries;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.protocol.http.server.HttpServer;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import io.reactivex.netty.protocol.http.server.RequestHandler;
import org.jetbrains.annotations.NotNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.exceptions.MissingBackpressureException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class MesosClientIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MesosClientIntegrationTest.class);

    @Rule
    public Async async = new Async();

    @Rule
    public Timeout timeoutRule = new Timeout(10_000, TimeUnit.MILLISECONDS);

    @Test
    public void testStreamDoesNotRunWhenSubscribeFails_mesos4xxResponse() throws Throwable {
        final String errorMessage = "Error message that should come from the server";
        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
            final byte[] msgBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
            response.getHeaders().setHeader("Content-Length", msgBytes.length);
            response.getHeaders().setHeader("Content-Type", "text/plain;charset=utf-8");
            response.writeBytes(msgBytes);
            return response.close();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClient(uri);

        try {
            client.openStream().await();
            fail("Expect an exception to be propagated up because subscribe will 400");
        } catch (Mesos4xxException e) {
            // expected
            final MesosClientErrorContext ctx = e.getContext();
            assertThat(ctx.getStatusCode()).isEqualTo(400);
            assertThat(ctx.getMessage()).isEqualTo(errorMessage);
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testStreamDoesNotRunWhenSubscribeFails_nonTextResponseBodyNotRead() throws Throwable {
        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.BAD_REQUEST);
            response.getHeaders().setHeader("Content-Length", 1);
            response.getHeaders().setHeader("Content-Type", "application/octet-stream");
            response.writeBytes(new byte[]{0b1});
            return response.close();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClient(uri);

        try {
            client.openStream().await();
            fail("Expect an exception to be propagated up because subscribe will 400");
        } catch (Mesos4xxException e) {
            // expected
            final MesosClientErrorContext ctx = e.getContext();
            assertThat(ctx.getStatusCode()).isEqualTo(400);
            assertThat(ctx.getMessage()).isEqualTo("Not attempting to decode error response of type 'application/octet-stream' as string");
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testStreamDoesNotRunWhenSubscribeFails_mesos5xxResponse() throws Throwable {
        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return response.close();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClient(uri);

        try {
            client.openStream().await();
            fail("Expect an exception to be propagated up because subscribe will 500");
        } catch (Mesos5xxException e) {
            // expected
            final MesosClientErrorContext ctx = e.getContext();
            assertThat(ctx.getStatusCode()).isEqualTo(500);
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testStreamDoesNotRunWhenSubscribeFails_mismatchContentType() throws Throwable {
        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.OK);
            response.getHeaders().setHeader("Content-Type", "application/json");
            return response.close();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClient(uri);

        try {
            client.openStream().await();
            fail("Expect an exception to be propagated up because of content type mismatch");
        } catch (MesosException e) {
            // expected
            final MesosClientErrorContext ctx = e.getContext();
            assertThat(ctx.getStatusCode()).isEqualTo(200);
            assertThat(ctx.getMessage()).isEqualTo("Response had Content-Type \"application/json\" expected \"text/plain;charset=utf-8\"");
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testBurstyObservable_missingBackpressureException() throws Throwable {
        String subscribedMessage = "{\"type\": \"SUBSCRIBED\",\"subscribed\": {\"framework_id\": {\"value\":\"12220-3440-12532-2345\"},\"heartbeat_interval_seconds\":15.0}";

        String heartbeatMessage = "{\"type\":\"HEARTBEAT\"}";
        byte[] hmsg = heartbeatMessage.getBytes(StandardCharsets.UTF_8);
        byte[] hbytes = String.format("%d\n", heartbeatMessage.getBytes().length).getBytes(StandardCharsets.UTF_8);

        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.OK);
            response.getHeaders().setHeader("Content-Type", "text/plain;charset=utf-8");
            writeRecordIOMessage(response, subscribedMessage);
            for (int i = 0; i < 20000; i++) {
                response.writeBytes(hbytes);
                response.writeBytes(hmsg);
            }
            return response.flush();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClientForStreaming(uri).build();

        try {
            client.openStream().await();
            fail("Expect an exception to be propagated up due to backpressure");
        } catch (MissingBackpressureException e) {
            // expected
            e.printStackTrace();
            assertThat(e.getMessage()).isNullOrEmpty();
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testBurstyObservable_unboundedBufferSucceeds() throws Throwable {
        String subscribedMessage = "{\"type\": \"SUBSCRIBED\",\"subscribed\": {\"framework_id\": {\"value\":\"12220-3440-12532-2345\"},\"heartbeat_interval_seconds\":15.0}";
        String heartbeatMessage = "{\"type\":\"HEARTBEAT\"}";
        final RequestHandler<ByteBuf, ByteBuf> handler = (request, response) -> {
            response.setStatus(HttpResponseStatus.OK);
            response.getHeaders().setHeader("Content-Type", "text/plain;charset=utf-8");
            writeRecordIOMessage(response, subscribedMessage);
            for (int i = 0; i < 20000; i++) {
                writeRecordIOMessage(response, heartbeatMessage);
            }
            return response.close();
        };
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(0, handler);
        server.start();
        final URI uri = URI.create(String.format("http://localhost:%d/api/v1/scheduler", server.getServerPort()));
        final MesosClient<String, String> client = createClientForStreaming(uri)
                .onBackpressureBuffer()
                .build();

        try {
            client.openStream().await();
        } finally {
            server.shutdown();
        }
    }

    private void writeRecordIOMessage(HttpServerResponse<ByteBuf> response, String msg) {
        response.writeBytesAndFlush(String.format("%d\n", msg.getBytes().length).getBytes(StandardCharsets.UTF_8));
        response.writeBytesAndFlush(msg.getBytes(StandardCharsets.UTF_8));
    }

    @NotNull
    private static MesosClient<String, String> createClient(final URI uri) {
        return MesosClientBuilder.<String, String>newBuilder()
            .sendCodec(StringMessageCodec.UTF8_STRING)
            .receiveCodec(StringMessageCodec.UTF8_STRING)
            .mesosUri(uri)
            .applicationUserAgentEntry(UserAgentEntries.literal("test", "test"))
            .processStream(events ->
                events
                    .doOnNext(e -> fail("event stream should never start"))
                    .map(e -> Optional.empty()))
            .subscribe("subscribe")
            .build();
    }

    static int i = 0;
    private static MesosClientBuilder<String, String> createClientForStreaming(final URI uri) {

        return MesosClientBuilder.<String, String>newBuilder()
                .sendCodec(StringMessageCodec.UTF8_STRING)
                .receiveCodec(StringMessageCodec.UTF8_STRING)
                .mesosUri(uri)
                .applicationUserAgentEntry(UserAgentEntries.literal("test", "test"))
                .processStream(events ->
                        events
                                .doOnNext(e -> LOGGER.debug(""+i++))
                                .map(e -> Optional.empty()))
                .subscribe("subscribe");
    }

}
