/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.client;

import org.apache.http.util.TextUtils;

/**
 * Signals a non 2xx HTTP response.
 *
 * @since 4.0
 */
public class HttpResponseException extends ClientProtocolException {

    private static final long serialVersionUID = -7186627969477257933L;

    private final int statusCode;
    private final String reasonPhrase;

    public HttpResponseException(final int statusCode, final String reasonPhrase) {
        super(String.format("status code: %d" +
                (TextUtils.isBlank(reasonPhrase) ? "" : ", reason phrase: %s"), statusCode, reasonPhrase));
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    public int getStatusCode() {
        return this.statusCode;
    }

    public String getReasonPhrase() {
        return this.reasonPhrase;
    }

}