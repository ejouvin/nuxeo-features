/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.core.operations.notification;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.Session;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.collectors.DocumentModelCollector;
import org.nuxeo.ecm.automation.core.mail.Composer;
import org.nuxeo.ecm.automation.core.mail.Mailer;
import org.nuxeo.ecm.automation.core.mail.Mailer.Message;
import org.nuxeo.ecm.automation.core.mail.Mailer.Message.AS;
import org.nuxeo.ecm.automation.core.scripting.Scripting;
import org.nuxeo.ecm.automation.core.util.StringList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.PropertyException;
import org.nuxeo.ecm.core.api.model.impl.ListProperty;
import org.nuxeo.ecm.core.api.model.impl.MapProperty;
import org.nuxeo.ecm.core.api.model.impl.primitives.BlobProperty;
import org.nuxeo.ecm.platform.ec.notification.service.NotificationServiceHelper;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Save the session - TODO remove this?
 *
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
@Operation(id = SendMail.ID, category = Constants.CAT_NOTIFICATION, label = "Send E-Mail", description = "Send an email using the input document to the specified recipients. You can use the HTML parameter to specify whether you message is in HTML format or in plain text. Also you can attach any blob on the current document to the message by using the comma separated list of xpath expressions 'files'. If you xpath points to a blob list all blobs in the list will be attached. Return back the input document(s). If rollbackOnError is true, the whole chain will be rollbacked if an error occurs while trying to send the email (for instance if no SMTP server is configured), else a simple warning will be logged and the chain will continue.")
public class SendMail {

    protected static final Log log = LogFactory.getLog(SendMail.class);

    public static final Composer COMPOSER = new Composer();

    public static final String ID = "Notification.SendMail";

    @Context
    protected OperationContext ctx;

    @Context
    protected UserManager umgr;

    @Param(name = "from")
    protected String from;

    @Param(name = "to")
    protected StringList to;

    // Useful for tests.
    protected Session mailSession;

    /**
     * @since 5.9.1
     */
    @Param(name = "cc", required = false)
    protected StringList cc;

    /**
     * @since 5.9.1
     */
    @Param(name = "bcc", required = false)
    protected StringList bcc;

    /**
     * @since 5.9.1
     */
    @Param(name = "replyto", required = false)
    protected StringList replyto;

    @Param(name = "subject")
    protected String subject;

    @Param(name = "message", widget = Constants.W_MAIL_TEMPLATE)
    protected String message;

    @Param(name = "HTML", required = false, values = { "false" })
    protected boolean asHtml = false;

    @Param(name = "files", required = false)
    protected StringList blobXpath;

    @Param(name = "rollbackOnError", required = false, values = { "true" })
    protected boolean rollbackOnError = true;

    /**
     * @since 5.9.1
     */
    @Param(name = "Strict User Resolution", required = false)
    protected boolean isStrict = true;

    @Param(name = "viewId", required = false, values = { "view_documents" })
    protected String viewId = "view_documents";

    @OperationMethod(collector = DocumentModelCollector.class)
    public DocumentModel run(DocumentModel doc) throws Exception {
        send(doc);
        return doc;
    }

    protected String getContent() throws Exception {
        message = message.trim();
        if (message.startsWith("template:")) {
            String name = message.substring("template:".length()).trim();
            URL url = MailTemplateHelper.getTemplate(name);
            if (url == null) {
                throw new OperationException("No such mail template: " + name);
            }
            InputStream in = url.openStream();
            return FileUtils.read(in);
        } else {
            return StringEscapeUtils.unescapeHtml(message);
        }
    }

    protected void send(DocumentModel doc) throws Exception {
        // TODO should sent one by one to each recipient? and have the template
        // rendered for each recipient? Use: "mailto" var name?
        try {
            Map<String, Object> map = Scripting.initBindings(ctx);
            // do not use document wrapper which is working only in mvel.
            map.put("Document", doc);
            map.put("docUrl", MailTemplateHelper.getDocumentUrl(doc, viewId));
            map.put("subject", subject);
            map.put("to", to);
            map.put("toResolved", MailBox.fetchPersonsFromList(to, isStrict));
            map.put("from", from);
            map.put("fromResolved",
                    MailBox.fetchPersonsFromString(from, isStrict));
            map.put("cc", cc);
            map.put("ccResolved", MailBox.fetchPersonsFromList(cc, isStrict));
            map.put("bcc", bcc);
            map.put("bccResolved", MailBox.fetchPersonsFromList(bcc, isStrict));
            map.put("replyto", replyto);
            map.put("replytoResolved",
                    MailBox.fetchPersonsFromList(replyto, isStrict));
            map.put("viewId", viewId);
            map.put("baseUrl",
                    NotificationServiceHelper.getNotificationService().getServerUrlPrefix());
            map.put("Runtime", Framework.getRuntime());
            Mailer.Message msg = createMessage(doc, getContent(), map);
            msg.setSubject(subject, "UTF-8");

            addMailBoxInfo(msg, map);

            msg.send();
        } catch (Exception e) {
            if (rollbackOnError) {
                throw e;
            } else {
                log.warn(
                        String.format(
                                "An error occured while trying to execute the %s operation, see complete stack trace below. Continuing chain since 'rollbackOnError' was set to false.",
                                ID), e);
            }
        }
    }

    /**
     * @since 5.9.1
     */
    private void addMailBoxInfo(Mailer.Message msg, Map<String, Object> map) throws MessagingException,
            ClientException {
        addMailBoxInfoInMessageHeader(msg, AS.FROM, map.get("fromResolved"));

        addMailBoxInfoInMessageHeader(msg, AS.TO, map.get("toResolved"));

        addMailBoxInfoInMessageHeader(msg, AS.CC, map.get("ccResolved"));

        addMailBoxInfoInMessageHeader(msg, AS.BCC, map.get("bccResolved"));

        if (replyto != null && !replyto.isEmpty()) {
            msg.setReplyTo(null);
            addMailBoxInfoInMessageHeader(msg, AS.REPLYTO, map.get("replytoResolved"));
        }
    }

    /**
     * @since 5.9.1
     */
    private void addMailBoxInfoInMessageHeader(Message msg, AS as,
            List<MailBox> persons) throws MessagingException {
        for (MailBox person : persons) {
            msg.addInfoInMessageHeader(person.toString(), as);
        }
    }

    protected Mailer.Message createMessage(DocumentModel doc, String message,
            Map<String, Object> map) throws Exception {
        if (blobXpath == null) {
            if (asHtml) {
                return COMPOSER.newHtmlMessage(message, map);
            } else {
                return COMPOSER.newTextMessage(message, map);
            }
        } else {
            ArrayList<Blob> blobs = new ArrayList<Blob>();
            for (String xpath : blobXpath) {
                try {
                    Property p = doc.getProperty(xpath);
                    if (p instanceof BlobProperty) {
                        getBlob(p.getValue(), blobs);
                    } else if (p instanceof ListProperty) {
                        for (Property pp : p) {
                            getBlob(pp.getValue(), blobs);
                        }
                    } else if (p instanceof MapProperty) {
                        for (Property sp : ((MapProperty) p).values()) {
                            getBlob(sp.getValue(), blobs);
                        }
                    } else {
                        Object o = p.getValue();
                        if (o instanceof Blob) {
                            blobs.add((Blob) o);
                        }
                    }
                } catch (PropertyException pe) {
                    log.error("Error while fetching blobs: " + pe.getMessage());
                    log.debug(pe, pe);
                    continue;
                }
            }
            return COMPOSER.newMixedMessage(message, map, asHtml ? "html"
                    : "plain", blobs);
        }
    }

    /**
     *
     * @since 5.7
     * @param o: the object to introspect to find a blob
     * @param blobs: the Blob list where the blobs are put during property
     *            introspection
     */
    @SuppressWarnings("unchecked")
    private void getBlob(Object o, List<Blob> blobs) {
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                getBlob(item, blobs);
            }
        } else if (o instanceof Map) {
            for (Object item : ((Map<String, Object>) o).values()) {
                getBlob(item, blobs);
            }
        } else if (o instanceof Blob) {
            blobs.add((Blob) o);
        }

    }
}
