/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     dmetzler
 */
package org.nuxeo.ecm.automation.test;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Before;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.TransactionalCoreSessionWrapper;
import org.nuxeo.ecm.core.api.local.LocalSession;

import com.google.inject.Inject;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

/**
 *
 *
 * @since TODO
 */
public class BaseTest {

    static enum RequestType {
        GET, POST, DELETE, PUT, POSTREQUEST
    }

    protected ObjectMapper mapper;

    protected WebResource service;

    @Before
    public void doBefore() {
        ClientConfig config = new DefaultClientConfig();
        Client client = Client.create(config);
        client.addFilter(new HTTPBasicAuthFilter("Administrator",
                "Administrator"));
        service = client.resource("http://localhost:18090/api/");

        mapper = new ObjectMapper();

    }

    @Inject
    CoreSession session;

    protected ClientResponse getResponse(RequestType requestType, String path) {
        return getResponse(requestType, path, null);
    }

    protected ClientResponse getResponse(RequestType requestType, String path,
            String data) {
        Builder builder = service.path(path) //
        .accept(MediaType.APPLICATION_JSON) //
        .header("X-NXDocumentProperties", "dublincore");

        if(requestType == RequestType.POSTREQUEST) {
            builder.header("Content-type", "application/json+nxrequest");
        } else {
            builder.header("Content-type", "application/json+nxentity");
        }

        switch (requestType) {
        case GET:
            return builder.get(ClientResponse.class);
        case POST:
        case POSTREQUEST:
            return builder.post(ClientResponse.class, data);
        case PUT:
            return builder.put(ClientResponse.class, data);
        case DELETE:
            return builder.delete(ClientResponse.class);
        default:
            throw new RuntimeException();
        }
    }

    protected void dispose(CoreSession session) throws Exception {
        if (Proxy.isProxyClass(session.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(session);
            if (handler instanceof TransactionalCoreSessionWrapper) {
                Field field = TransactionalCoreSessionWrapper.class.getDeclaredField("session");
                field.setAccessible(true);
                session = (CoreSession) field.get(handler);
            }
        }
        if (!(session instanceof LocalSession)) {
            throw new UnsupportedOperationException(
                    "Cannot dispose session of class " + session.getClass());
        }
        ((LocalSession) session).getSession().dispose();
    }

    protected void assertNodeEqualsDoc(JsonNode node, DocumentModel note)
            throws Exception {
        assertEquals("document", node.get("entity-type").getValueAsText());
        assertEquals(note.getPathAsString(), node.get("path").getValueAsText());
        assertEquals(note.getId(), node.get("uid").getValueAsText());
        assertEquals(note.getTitle(), node.get("title").getValueAsText());
    }

    protected void assertEntityEqualsDoc(InputStream in, DocumentModel doc)
            throws Exception {

        JsonNode node = mapper.readTree(in);
        assertNodeEqualsDoc(node, doc);

    }
}