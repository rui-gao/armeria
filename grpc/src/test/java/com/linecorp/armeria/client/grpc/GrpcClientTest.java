/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static com.linecorp.armeria.grpc.testing.Messages.PayloadType.COMPRESSABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.logging.LoggingClientBuilder;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.ResponseParameters;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;
import com.linecorp.armeria.grpc.testing.UnimplementedServiceGrpc;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.StreamRecorder;
import com.linecorp.armeria.internal.grpc.TestServiceImpl;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.testing.server.ServerRule;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;

public class GrpcClientTest {

    private static final Logger logger = LoggerFactory.getLogger(GrpcClientTest.class);

    /**
     * Must be at least {@link #unaryPayloadLength()}, plus some to account for encoding overhead.
     */
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final Empty EMPTY = Empty.getDefaultInstance();

    private static final AtomicReference<HttpHeaders> CLIENT_HEADERS_CAPTURE = new AtomicReference<>();
    private static final AtomicReference<HttpHeaders> SERVER_TRAILERS_CAPTURE = new AtomicReference<>();

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.defaultMaxRequestLength(MAX_MESSAGE_SIZE);
            sb.idleTimeoutMillis(0);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                    .setMaxInboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .setMaxOutboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .build()
                    .decorate(TestServiceImpl.EchoRequestHeadersInTrailers::new)
                    .decorate((client, ctx, req) -> {
                        CLIENT_HEADERS_CAPTURE.set(req.headers());
                        final HttpResponse res = client.serve(ctx, req);
                        return new FilteredHttpResponse(res) {

                            private boolean headersReceived;

                            @Override
                            protected HttpObject filter(HttpObject obj) {
                                if (obj instanceof HttpHeaders) {
                                    if (!headersReceived) {
                                        headersReceived = true;
                                    } else {
                                        SERVER_TRAILERS_CAPTURE.set((HttpHeaders) obj);
                                    }
                                }
                                return obj;
                            }
                        };
                    }));
        }
    };

    private TestServiceBlockingStub blockingStub;
    private TestServiceStub asyncStub;

    @Before
    public void setUp() {
        blockingStub = new ClientBuilder("gproto+" + server.httpUri("/"))
                .defaultMaxResponseLength(MAX_MESSAGE_SIZE)
                .decorator(HttpRequest.class, HttpResponse.class, new LoggingClientBuilder().newDecorator())
                .build(TestServiceBlockingStub.class);
        asyncStub = new ClientBuilder("gproto+" + server.httpUri("/"))
                .decorator(HttpRequest.class, HttpResponse.class, new LoggingClientBuilder().newDecorator())
                .build(TestServiceStub.class);
    }

    @After
    public void tearDown() {
        CLIENT_HEADERS_CAPTURE.set(null);
        SERVER_TRAILERS_CAPTURE.set(null);
    }

    @Test(timeout = 10000)
    public void emptyUnary() {
        assertThat(blockingStub.emptyCall(EMPTY)).isEqualTo(EMPTY);
    }

    @Test(timeout = 10000)
    public void largeUnary() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();

        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 10000)
    public void largeUnary_unsafe() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();

        TestServiceStub stub = new ClientBuilder("gproto+" + server.httpUri("/"))
                .option(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS.newValue(true))
                .decorator(HttpRequest.class, HttpResponse.class, new LoggingClientBuilder().newDecorator())
                .build(TestServiceStub.class);
        stub.unaryCall(request, new StreamObserver<SimpleResponse>() {
            @Override
            public void onNext(SimpleResponse value) {
                RequestContext ctx = RequestContext.current();
                assertThat(value).isEqualTo(goldenResponse);
                ByteBuf buf = ctx.attr(GrpcUnsafeBufferUtil.BUFFERS).get().get(value);
                assertThat(buf.refCnt()).isNotZero();
                GrpcUnsafeBufferUtil.releaseBuffer(value, ctx);
                assertThat(buf.refCnt()).isZero();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    @Test(timeout = 10000)
    public void serverStreaming() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setSize(31415))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        asyncStub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).containsExactlyElementsOf(goldenResponses);
    }

    @Test(timeout = 10000)
    public void clientStreaming() throws Exception {
        final List<StreamingInputCallRequest> requests = Arrays.asList(
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[27182])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[8])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[1828])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[45904])))
                                         .build());
        final StreamingInputCallResponse goldenResponse =
                StreamingInputCallResponse.newBuilder()
                                          .setAggregatedPayloadSize(74922)
                                          .build();

        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        for (StreamingInputCallRequest request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse);
        responseObserver.awaitCompletion();
    }

    @Test(timeout = 10000)
    public void pingPong() throws Exception {
        final List<StreamingOutputCallRequest> requests = Arrays.asList(
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[8])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[1828])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[45904])))
                                          .build());
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(5);
        final StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
            @Override
            public void onNext(StreamingOutputCallResponse response) {
                queue.add(response);
            }

            @Override
            public void onError(Throwable t) {
                queue.add(t);
            }

            @Override
            public void onCompleted() {
                queue.add("Completed");
            }
        });
        for (int i = 0; i < requests.size(); i++) {
            assertThat(queue.peek()).isNull();
            requestObserver.onNext(requests.get(i));
            assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(
                    goldenResponses.get(i));
        }
        requestObserver.onCompleted();
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo("Completed");
    }

    @Test(timeout = 10000)
    public void emptyStream() throws Exception {
        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onCompleted();
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    @Test(timeout = 10000)
    public void cancelAfterBegin() throws Exception {
        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion();
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(Status.fromThrowable(responseObserver.getError()).getCode()).isEqualTo(Code.CANCELLED);
    }

    @Test(timeout = 10000)
    public void cancelAfterFirstResponse() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build();
        final StreamingOutputCallResponse goldenResponse =
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(
                                                                      COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build();

        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(request);
        await().untilAsserted(() -> assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse));
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).hasSize(1);
        assertThat(Status.fromThrowable(responseObserver.getError()).getCode()).isEqualTo(Code.CANCELLED);
    }

    @Test(timeout = 10000)
    public void fullDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream =
                asyncStub.fullDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests =
                new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);
        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            final StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            final int length = response.getPayload().getBody().size();
            final int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).withFailMessage("comparison failed at index " + ix)
                              .isEqualTo(expectedSize);
        }
    }

    @Test(timeout = 10000)
    public void halfDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream = asyncStub.halfDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();

        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);

        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            final StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            final int length = response.getPayload().getBody().size();
            final int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).withFailMessage("comparison failed at index " + ix)
                              .isEqualTo(expectedSize);
        }
    }

    @Test(timeout = 10000)
    public void serverStreamingShouldBeFlowControlled() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100000))
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100001))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100000])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100001])))
                                           .build());

        final long start = System.nanoTime();

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(10);
        final ClientCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
                asyncStub.getChannel().newCall(TestServiceGrpc.getStreamingOutputCallMethod(),
                                               CallOptions.DEFAULT);
        call.start(new ClientCall.Listener<StreamingOutputCallResponse>() {
            @Override
            public void onHeaders(Metadata headers) {}

            @Override
            public void onMessage(final StreamingOutputCallResponse message) {
                queue.add(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                queue.add(status);
            }
        }, new Metadata());
        call.sendMessage(request);
        call.halfClose();

        // Time how long it takes to get the first response.
        call.request(1);

        final Object actualResponse1 = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(actualResponse1).withFailMessage("Unexpected response: %s", actualResponse1)
                                   .isEqualTo(goldenResponses.get(0));
        final long firstCallDuration = System.nanoTime() - start;

        // Without giving additional flow control, make sure that we don't get another response. We wait
        // until we are comfortable the next message isn't coming. We may have very low nanoTime
        // resolution (like on Windows) or be using a testing, in-process transport where message
        // handling is instantaneous. In both cases, firstCallDuration may be 0, so round up sleep time
        // to at least 1ms.
        assertThat(queue.poll(Math.max(firstCallDuration * 4, 1_000_000), TimeUnit.NANOSECONDS)).isNull();

        // Make sure that everything still completes.
        call.request(1);
        final Object actualResponse2 = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(actualResponse2).withFailMessage("Unexpected response: %s", actualResponse2)
                                   .isEqualTo(goldenResponses.get(1));
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Status.OK);
        call.cancel("Cancelled after all of the requests are done", null);
    }

    @Test(timeout = 30000)
    public void veryLargeRequest() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setType(COMPRESSABLE)
                                                .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                             .setResponseSize(10)
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[10])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 30000)
    public void veryLargeResponse() {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(unaryPayloadLength())
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 10000)
    public void exchangeHeadersUnaryCall() {
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.HTTP_HEADERS.newValue(
                                HttpHeaders.of()
                                           .set(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(CLIENT_HEADERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
    }

    @Test(timeout = 10000)
    public void exchangeHeadersStreamingCall() throws Exception {
        final TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOption.HTTP_HEADERS.newValue(
                                HttpHeaders.of()
                                           .set(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request = streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream =
                stub.fullDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);

        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(CLIENT_HEADERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
    }

    @Test(timeout = 10000)
    public void sendsTimeoutHeader() {
        final long configuredTimeoutMinutes = 100;
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.MINUTES.toMillis(configuredTimeoutMinutes)));
        stub.emptyCall(EMPTY);
        final long transferredTimeoutMinutes = TimeUnit.NANOSECONDS.toMinutes(
                TimeoutHeaderUtil.fromHeaderValue(
                        CLIENT_HEADERS_CAPTURE.get().get(GrpcHeaderNames.GRPC_TIMEOUT)));
        assertThat(transferredTimeoutMinutes).isEqualTo(configuredTimeoutMinutes);
    }

    @Test
    public void deadlineNotExceeded() {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.SECONDS.toMillis(10)));
        stub
                .streamingOutputCall(
                        StreamingOutputCallRequest.newBuilder()
                                                  .addResponseParameters(
                                                          ResponseParameters.newBuilder()
                                                                            .setIntervalUs(0))
                                                  .build())
                .next();
    }

    @Test(timeout = 10000)
    public void deadlineExceeded() {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(10L));
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setIntervalUs(20000))
                                          .build();
        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    @Test(timeout = 10000)
    public void deadlineExceededServerStreaming() throws Exception {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        final ResponseParameters.Builder responseParameters = ResponseParameters.newBuilder()
                                                                                .setSize(1)
                                                                                .setIntervalUs(20000);
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .build();
        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(30L));
        stub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();

        assertThat(recorder.getError()).isNotNull();
        assertThat(Status.fromThrowable(recorder.getError()).getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    // NB: It's unclear when anyone would set a negative timeout, and trying to set the negative timeout
    // into a header correctly raises an exception. The test has been copied over from upstream to make it
    // easier to understand the compatibility test coverage - not sure why the gRPC test doesn't fail but it
    // doesn't seem worth investigating too hard on this one.
    @Ignore
    @Test(timeout = 10000)
    public void deadlineInPast() {
        // Test once with idle channel and once with active channel
        final TestServiceGrpc.TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,

                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(TimeUnit.SECONDS.toMillis(-10)));
        stub.emptyCall(EMPTY);
        Throwable t = catchThrowable(() -> stub.emptyCall(EMPTY));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode());

        // warm up the channel
        blockingStub.emptyCall(Empty.getDefaultInstance());
        t = catchThrowable(() -> stub.emptyCall(EMPTY));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode());
    }

    @Test(timeout = 10000)
    public void maxInboundSize_exact() {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size));
        stub.streamingOutputCall(request).next();
    }

    @Test(timeout = 10000)
    public void maxInboundSize_tooBig() {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size - 1));
        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        assertThat(Throwables.getStackTraceAsString(t)).contains("exceeds maximum");
    }

    @Test(timeout = 10000)
    public void maxOutboundSize_exact() {
        // set at least one field to ensure the size is non-zero.
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize()));
        stub.streamingOutputCall(request).next();
    }

    @Test(timeout = 10000)
    public void maxOutboundSize_tooBig() {
        // set at least one field to ensure the size is non-zero.
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize() - 1));
        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.CANCELLED);
        assertThat(Throwables.getStackTraceAsString(t)).contains("message too large");
    }

    @Test(timeout = 10000)
    public void statusCodeAndMessage() {
        final int errorCode = 2;
        final String errorMessage = "test status message";
        final EchoStatus responseStatus = EchoStatus.newBuilder()
                                                    .setCode(errorCode)
                                                    .setMessage(errorMessage)
                                                    .build();
        final SimpleRequest simpleRequest = SimpleRequest.newBuilder()
                                                         .setResponseStatus(responseStatus)
                                                         .build();
        final StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
                                                                                      .setResponseStatus(
                                                                                        responseStatus)
                                                                                      .build();

        // Test UnaryCall
        final Throwable t = catchThrowable(() -> blockingStub.unaryCall(simpleRequest));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        final StatusRuntimeException e = (StatusRuntimeException) t;
        assertThat(e.getStatus().getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(e.getStatus().getDescription()).isEqualTo(errorMessage);

        // Test FullDuplexCall
        @SuppressWarnings("unchecked")
        final StreamObserver<StreamingOutputCallResponse> responseObserver =
                mock(StreamObserver.class);
        final StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(streamingRequest);
        requestObserver.onCompleted();

        final ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver, timeout(operationTimeoutMillis())).onError(captor.capture());
        assertThat(Status.fromThrowable(captor.getValue()).getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(Status.fromThrowable(captor.getValue()).getDescription()).isEqualTo(errorMessage);
        verifyNoMoreInteractions(responseObserver);
    }

    /** Sends an rpc to an unimplemented method within TestService. */
    @Test(timeout = 10000)
    public void unimplementedMethod() {
        final Throwable t = catchThrowable(() -> blockingStub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());
    }

    @Test(timeout = 10000)
    public void unimplementedService() {
        final UnimplementedServiceGrpc.UnimplementedServiceBlockingStub stub =
                UnimplementedServiceGrpc.newBlockingStub(asyncStub.getChannel());
        final Throwable t = catchThrowable(() -> stub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());
    }

    /** Start a fullDuplexCall which the server will not respond, and verify the deadline expires. */
    @Test(timeout = 10000)
    public void timeoutOnSleepingServer() throws Exception {
        final TestServiceStub stub = Clients.newDerivedClient(
                asyncStub,
                ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(1L));

        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver
                = stub.fullDuplexCall(responseObserver);

        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest
                        .newBuilder()
                        .setPayload(Payload.newBuilder()
                                           .setBody(ByteString.copyFrom(new byte[27182])))
                        .addResponseParameters(
                                ResponseParameters.newBuilder()
                                                  .setIntervalUs((int) TimeUnit.SECONDS.toMicros(10)))
                        .build();
        try {
            requestObserver.onNext(request);
            // TODO(anuraag): Upstream test does not need to call onCompleted - response timeout is set at the
            // start of the stream, which seems to make sense. Figure out how to fix this timeout handling in
            // armeria.
            requestObserver.onCompleted();
        } catch (IllegalStateException expected) {
            // This can happen if the stream has already been terminated due to deadline exceeded.
        }

        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(responseObserver.getError()).isNotNull();
        // TODO(anuraag): As gRPC supports handling timeouts in the server or client due to the grpc-timeout
        // header, it's not guaranteed which is the source of this error.
        // Until https://github.com/line/armeria/issues/521 a server side timeout will not have the correct
        // status so we don't verify it for now.
        //assertThat(Status.fromThrowable(responseObserver.getError()).getCode())
        //.isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    private static void assertSuccess(StreamRecorder<?> recorder) {
        if (recorder.getError() != null) {
            throw new AssertionError(recorder.getError());
        }
    }

    private static int unaryPayloadLength() {
        // 10MiB.
        return 10485760;
    }

    private static int operationTimeoutMillis() {
        return 5000;
    }
}
