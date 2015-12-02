/*
 *    Copyright (C) 2015 Mesosphere, Inc
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

/**
 * This class represents a server error (HTTP 500 series) occurred while sending a request to Mesos.
 */
public final class MesosServerException extends RuntimeException {
    private final Object originalCall;
    private final MesosClientErrorContext context;

    public MesosServerException(final Object originalCall, final MesosClientErrorContext context) {
        this.originalCall = originalCall;
        this.context = context;
    }

    /**
     * The original object that was sent to Mesos.
     * @return The original object that was sent to Mesos.
     */
    public Object getOriginalCall() {
        return originalCall;
    }

    /**
     * The response context built from the Mesos response.
     * @return The response context built from the Mesos response.
     */
    public MesosClientErrorContext getContext() {
        return context;
    }
}