/*
 *    Copyright (C) 2017 Mesosphere, Inc
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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.http.client.HttpResponseHeaders;
import org.jetbrains.annotations.NotNull;
import rx.Observable;

import java.nio.charset.StandardCharsets;

final class ResponseUtils {

    private ResponseUtils() {}

    @NotNull
    static Observable<String> attemptToReadErrorResponse(@NotNull final HttpClientResponse<ByteBuf> resp) {
        return attemptToReadErrorResponse(resp, true);
    }

    @NotNull
    static Observable<String> attemptToReadErrorResponse(@NotNull final HttpClientResponse<ByteBuf> resp, final boolean ignoreContentWhenUnreadable) {
        final HttpResponseHeaders headers = resp.getHeaders();
        final String contentType = resp.getHeaders().get(HttpHeaderNames.CONTENT_TYPE);
        if (headers.isContentLengthSet() && headers.getContentLength() > 0 ) {
            if (contentType != null && contentType.startsWith("text/plain")) {
                return resp.getContent()
                    .map(r -> r.toString(StandardCharsets.UTF_8));
            } else {
                if (ignoreContentWhenUnreadable) {
                    resp.ignoreContent();
                }
                final String errMsg = getErrMsg(contentType);
                return Observable.just(errMsg);
            }
        } else {
            return Observable.just("");
        }
    }

    private static String getErrMsg(final String contentType) {
        if (contentType == null) {
            return "Not attempting to decode error response with unspecified Content-Type";
        }
        return String.format("Not attempting to decode error response of type '%s' as string", contentType);
    }
}