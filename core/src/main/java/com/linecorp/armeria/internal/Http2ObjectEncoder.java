/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.stream.ClosedPublisherException;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.ReferenceCountUtil;

public final class Http2ObjectEncoder extends HttpObjectEncoder {

    private final Http2ConnectionEncoder encoder;

    public Http2ObjectEncoder(Http2ConnectionEncoder encoder) {
        this.encoder = requireNonNull(encoder, "encoder");
    }

    @Override
    protected ChannelFuture doWriteHeaders(
            ChannelHandlerContext ctx, int id, int streamId, HttpHeaders headers, boolean endStream) {

        final ChannelFuture future = validateStream(ctx, streamId);
        if (future != null) {
            return future;
        }

        return encoder.writeHeaders(
                ctx, streamId, ArmeriaHttpUtil.toNettyHttp2(headers), 0, endStream, ctx.newPromise());
    }

    @Override
    protected ChannelFuture doWriteData(
            ChannelHandlerContext ctx, int id, int streamId, HttpData data, boolean endStream) {

        final ChannelFuture future = validateStream(ctx, streamId);
        if (future != null) {
            ReferenceCountUtil.safeRelease(data);
            return future;
        }

        if (!encoder.connection().streamMayHaveExisted(streamId)) {
            // Cannot start a new stream with a DATA frame. It must start with a HEADERS frame.
            ReferenceCountUtil.safeRelease(data);
            return ctx.newFailedFuture(new IllegalStateException(
                    "cannot start a new stream " + streamId + " with a DATA frame"));
        }

        return encoder.writeData(ctx, streamId, toByteBuf(ctx, data), 0, endStream, ctx.newPromise());
    }

    @Override
    protected ChannelFuture doWriteReset(ChannelHandlerContext ctx, int id, int streamId, Http2Error error) {
        final ChannelFuture future = validateStream(ctx, streamId);
        if (future != null) {
            return future;
        }

        if (encoder.connection().streamMayHaveExisted(streamId)) {
            return encoder.writeRstStream(ctx, streamId, error.code(), ctx.newPromise());
        } else {
            // Tried to send a RST frame for a non-existent stream. This can happen when a client-side
            // subscriber terminated its response stream even before the first frame of the stream is sent.
            // In this case, we don't need to send a RST stream.
            return ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }
    }

    @Nullable
    private ChannelFuture validateStream(ChannelHandlerContext ctx, int streamId) {
        final Http2Stream stream = encoder.connection().stream(streamId);
        if (stream != null) {
            switch (stream.state()) {
                case RESERVED_LOCAL:
                case OPEN:
                case HALF_CLOSED_REMOTE:
                    break;
                default:
                    // The response has been sent already.
                    return ctx.newFailedFuture(ClosedPublisherException.get());
            }
        } else if (encoder.connection().streamMayHaveExisted(streamId)) {
            // Stream has been removed because it has been closed completely.
            return ctx.newFailedFuture(ClosedPublisherException.get());
        }

        return null;
    }

    @Override
    protected void doClose() {}
}
