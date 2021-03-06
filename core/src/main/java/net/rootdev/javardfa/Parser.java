/*
 * (c) Copyright 2009 University of Bristol
 * All rights reserved.
 * [See end of file]
 */
package net.rootdev.javardfa;

import net.rootdev.javardfa.uri.URIExtractor10;
import net.rootdev.javardfa.uri.URIExtractor;
import net.rootdev.javardfa.uri.IRIResolver;
import net.rootdev.javardfa.literal.LiteralCollector;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * @author Damian Steer <pldms@mac.com>
 */
public class Parser implements ContentHandler {

    private final XMLEventFactory eventFactory;
    private final StatementSink sink;
    private final Set<Setting> settings;
    private final LiteralCollector literalCollector;
    private final URIExtractor extractor;
    private final ProfileCollector profileCollector;

    public Parser(StatementSink sink) {
        this(   sink,
                XMLOutputFactory.newInstance(),
                XMLEventFactory.newInstance(),
                new URIExtractor10(new IRIResolver()),
                ProfileCollector.EMPTY_COLLECTOR);
    }

    public Parser(StatementSink sink,
            XMLOutputFactory outputFactory,
            XMLEventFactory eventFactory,
            URIExtractor extractor,
            ProfileCollector profileCollector) {
        this.sink = sink;
        this.eventFactory = eventFactory;
        this.settings = EnumSet.noneOf(Setting.class);
        this.extractor = extractor;
        this.literalCollector = new LiteralCollector(this, eventFactory, outputFactory);
        this.profileCollector = profileCollector;

        extractor.setSettings(settings);

        // Important, although I guess the caller doesn't get total control
        outputFactory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true);
    }

    public void enable(Setting setting) {
        settings.add(setting);
    }

    public void disable(Setting setting) {
        settings.remove(setting);
    }

    public void setBase(String base) {
        this.context = new EvalContext(base);
        sink.setBase(context.getBase());
    }

    EvalContext parse(EvalContext context, StartElement element)
            throws XMLStreamException {
        boolean skipElement = false;
        String newSubject = null;
        String currentObject = null;
        List<String> forwardProperties = new LinkedList();
        List<String> backwardProperties = new LinkedList();
        String currentLanguage = context.language;

        if (settings.contains(Setting.OnePointOne)) {

            if (element.getAttributeByName(Constants.vocab) != null) {
                context.vocab =
                    element.getAttributeByName(Constants.vocab).getValue().trim();
            }

            if (element.getAttributeByName(Constants.prefix) != null) {
                parsePrefixes(element.getAttributeByName(Constants.prefix).getValue(), context);
            }

            if (element.getAttributeByName(Constants.profile) != null) {
                String profileURI = extractor.resolveURI(
                        element.getAttributeByName(Constants.profile).getValue(),
                        context);
                profileCollector.getProfile(
                        profileURI,
                        context);
            }
        }

        // The xml / html namespace matching is a bit ropey. I wonder if the html 5
        // parser has a setting for this?
        if (settings.contains(Setting.ManualNamespaces)) {
            if (element.getAttributeByName(Constants.xmllang) != null) {
                currentLanguage = element.getAttributeByName(Constants.xmllang).getValue();
                if (currentLanguage.length() == 0) currentLanguage = null;
            } else if (element.getAttributeByName(Constants.lang) != null) {
                currentLanguage = element.getAttributeByName(Constants.lang).getValue();
                if (currentLanguage.length() == 0) currentLanguage = null;
            }
        } else if (element.getAttributeByName(Constants.xmllangNS) != null) {
            currentLanguage = element.getAttributeByName(Constants.xmllangNS).getValue();
            if (currentLanguage.length() == 0) currentLanguage = null;
        }

        if (Constants.base.equals(element.getName()) &&
                element.getAttributeByName(Constants.href) != null) {
            context.setBase(element.getAttributeByName(Constants.href).getValue());
            sink.setBase(context.getBase());
        }

        if (element.getAttributeByName(Constants.rev) == null &&
                element.getAttributeByName(Constants.rel) == null) {
            Attribute nSubj = findAttribute(element, Constants.about, Constants.src,
                    Constants.resource, Constants.href);
            if (nSubj != null) {
                newSubject = extractor.getURI(element, nSubj, context);
            }
            if (newSubject == null) {
                if (Constants.body.equals(element.getName()) ||
                            Constants.head.equals(element.getName())) {
                    newSubject = context.base;
                }
                else if (element.getAttributeByName(Constants.typeof) != null) {
                    newSubject = createBNode();
                } else {
                    if (context.parentObject != null) {
                        newSubject = context.parentObject;
                    }
                    if (element.getAttributeByName(Constants.property) == null) {
                        skipElement = true;
                    }
                }
            }
        } else {
            Attribute nSubj = findAttribute(element, Constants.about, Constants.src);
            if (nSubj != null) {
                newSubject = extractor.getURI(element, nSubj, context);
            }
            if (newSubject == null) {
                // if element is head or body assume about=""
                if (Constants.head.equals(element.getName()) ||
                        Constants.body.equals(element.getName())) {
                    newSubject = context.base;
                } else if (element.getAttributeByName(Constants.typeof) != null) {
                    newSubject = createBNode();
                } else if (context.parentObject != null) {
                    newSubject = context.parentObject;
                }
            }
            Attribute cObj = findAttribute(element, Constants.resource, Constants.href);
            if (cObj != null) {
                currentObject = extractor.getURI(element, cObj, context);
            }
        }

        if (newSubject != null && element.getAttributeByName(Constants.typeof) != null) {
            List<String> types = extractor.getURIs(element,
                    element.getAttributeByName(Constants.typeof), context);
            for (String type : types) {
                emitTriples(newSubject,
                        Constants.rdfType,
                        type);
            }
        }

        // Dodgy extension
        if (settings.contains(Setting.FormMode)) {
            if (Constants.form.equals(element.getName())) {
                emitTriples(newSubject, Constants.rdfType, "http://www.w3.org/1999/xhtml/vocab/#form"); // Signal entering form
            }
            if (Constants.input.equals(element.getName()) &&
                    element.getAttributeByName(Constants.name) != null) {
                currentObject = "?" + element.getAttributeByName(Constants.name).getValue();
            }

        }

        if (currentObject != null) {
            if (element.getAttributeByName(Constants.rel) != null) {
                emitTriples(newSubject,
                        extractor.getURIs(element,
                            element.getAttributeByName(Constants.rel), context),
                        currentObject);
            }
            if (element.getAttributeByName(Constants.rev) != null) {
                emitTriples(currentObject,
                        extractor.getURIs(element, element.getAttributeByName(Constants.rev), context),
                        newSubject);
            }
        } else {
            if (element.getAttributeByName(Constants.rel) != null) {
                forwardProperties.addAll(extractor.getURIs(element,
                        element.getAttributeByName(Constants.rel), context));
            }
            if (element.getAttributeByName(Constants.rev) != null) {
                backwardProperties.addAll(extractor.getURIs(element,
                        element.getAttributeByName(Constants.rev), context));
            }
            if (!forwardProperties.isEmpty() || !backwardProperties.isEmpty()) {
                // if predicate present
                currentObject = createBNode();
            }
        }

        // Getting literal values. Complicated!
        if (element.getAttributeByName(Constants.property) != null) {
            List<String> props = extractor.getURIs(element,
                    element.getAttributeByName(Constants.property), context);
            String dt = getDatatype(element);
            if (element.getAttributeByName(Constants.content) != null) { // The easy bit
                String lex = element.getAttributeByName(Constants.content).getValue();
                if (dt == null || dt.length() == 0) {
                    emitTriplesPlainLiteral(newSubject, props, lex, currentLanguage);
                } else {
                    emitTriplesDatatypeLiteral(newSubject, props, lex, dt);
                }
            } else {
                literalCollector.collect(newSubject, props, dt, currentLanguage);
            }
        }

        if (!skipElement && newSubject != null) {
            emitTriples(context.parentSubject,
                    context.forwardProperties,
                    newSubject);

            emitTriples(newSubject,
                    context.backwardProperties,
                    context.parentSubject);
        }

        EvalContext ec = new EvalContext(context);
        if (skipElement) {
            ec.language = currentLanguage;
        } else {
            if (newSubject != null) {
                ec.parentSubject = newSubject;
            } else {
                ec.parentSubject = context.parentSubject;
            }

            if (currentObject != null) {
                ec.parentObject = currentObject;
            } else if (newSubject != null) {
                ec.parentObject = newSubject;
            } else {
                ec.parentObject = context.parentSubject;
            }

            ec.language = currentLanguage;
            ec.forwardProperties = forwardProperties;
            ec.backwardProperties = backwardProperties;
        }
        return ec;
    }

    private Attribute findAttribute(StartElement element, QName... names) {
        for (QName aName : names) {
            Attribute a = element.getAttributeByName(aName);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    public void emitTriples(String subj, Collection<String> props, String obj) {
        for (String prop : props) {
            sink.addObject(subj, prop, obj);
        }
    }

    public void emitTriplesPlainLiteral(String subj, Collection<String> props, String lex, String language) {
        for (String prop : props) {
            sink.addLiteral(subj, prop, lex, language, null);
        }
    }

    public void emitTriplesDatatypeLiteral(String subj, Collection<String> props, String lex, String datatype) {
        for (String prop : props) {
            sink.addLiteral(subj, prop, lex, null, datatype);
        }
    }

    int bnodeId = 0;
    
    private String createBNode() // TODO probably broken? Can you write bnodes in rdfa directly?
    {
        return "_:node" + (bnodeId++);
    }

    private String getDatatype(StartElement element) {
        Attribute de = element.getAttributeByName(Constants.datatype);
        if (de == null) {
            return null;
        }
        String dt = de.getValue();
        if (dt.length() == 0) {
            return dt;
        }
        return extractor.expandCURIE(element, dt, context);
    }

    private void getNamespaces(Attributes attrs) {
        for (int i = 0; i < attrs.getLength(); i++) {
            String qname = attrs.getQName(i);
            String prefix = getPrefix(qname);
            if ("xmlns".equals(prefix)) {
                String pre = getLocal(prefix, qname);
                String uri = attrs.getValue(i);
                if (!settings.contains(Setting.ManualNamespaces) && pre.contains("_"))
                    continue; // not permitted
                context.setNamespaceURI(pre, uri);
                sink.addPrefix(pre, uri);
            }
        }
    }

    private String getPrefix(String qname) {
        if (!qname.contains(":")) {
            return "";
        }
        return qname.substring(0, qname.indexOf(":"));
    }

    private String getLocal(String prefix, String qname) {
        if (prefix.length() == 0) {
            return qname;
        }
        return qname.substring(prefix.length() + 1);
    }
    /**
     * SAX methods
     */
    private Locator locator;
    private EvalContext context;

    public void setDocumentLocator(Locator arg0) {
        this.locator = arg0;
        if (locator.getSystemId() != null)
            this.setBase(arg0.getSystemId());
    }

    public void startDocument() throws SAXException {
        sink.start();
    }

    public void endDocument() throws SAXException {
        sink.end();
    }

    public void startPrefixMapping(String arg0, String arg1)
            throws SAXException {
        context.setNamespaceURI(arg0, arg1);
        sink.addPrefix(arg0, arg1);
    }

    public void endPrefixMapping(String arg0) throws SAXException {
    }

    public void startElement(String arg0, String localname, String qname, Attributes arg3) throws SAXException {
        try {
            //System.err.println("Start element: " + arg0 + " " + arg1 + " " + arg2);

            // This is set very late in some html5 cases (not even ready by document start)
            if (context == null) {
                this.setBase(locator.getSystemId());
            }

            // Dammit, not quite the same as XMLEventFactory
            String prefix = /*(localname.equals(qname))*/
                    (qname.indexOf(':') == -1 ) ? ""
                    : qname.substring(0, qname.indexOf(':'));
            if (settings.contains(Setting.ManualNamespaces)) {
                getNamespaces(arg3);
                if (prefix.length() != 0) {
                    arg0 = context.getNamespaceURI(prefix);
                    localname = localname.substring(prefix.length() + 1);
                }
            }
            StartElement e = eventFactory.createStartElement(
                    prefix, arg0, localname,
                    fromAttributes(arg3), null, context);

            if (literalCollector.isCollecting()) literalCollector.handleEvent(e);

            // If we are gathering XML we stop parsing
            if (!literalCollector.isCollectingXML()) context = parse(context, e);
        } catch (XMLStreamException ex) {
            throw new RuntimeException("Streaming issue", ex);
        }

    }

    public void endElement(String arg0, String localname, String qname) throws SAXException {
        //System.err.println("End element: " + arg0 + " " + arg1 + " " + arg2);
        if (literalCollector.isCollecting()) {
            String prefix = (localname.equals(qname)) ? ""
                    : qname.substring(0, qname.indexOf(':'));
            XMLEvent e = eventFactory.createEndElement(prefix, arg0, localname);
            literalCollector.handleEvent(e);
        }
        // If we aren't collecting an XML literal keep parsing
        if (!literalCollector.isCollectingXML()) context = context.parent;
    }

    public void characters(char[] arg0, int arg1, int arg2) throws SAXException {
        if (literalCollector.isCollecting()) {
            XMLEvent e = eventFactory.createCharacters(String.valueOf(arg0, arg1, arg2));
            literalCollector.handleEvent(e);
        }
    }

    public void ignorableWhitespace(char[] arg0, int arg1, int arg2) throws SAXException {
        //System.err.println("Whitespace...");
        if (literalCollector.isCollecting()) {
            XMLEvent e = eventFactory.createIgnorableSpace(String.valueOf(arg0, arg1, arg2));
            literalCollector.handleEvent(e);
        }
    }

    public void processingInstruction(String arg0, String arg1) throws SAXException {
    }

    public void skippedEntity(String arg0) throws SAXException {
    }

    private Iterator fromAttributes(Attributes attributes) {
        List toReturn = new LinkedList();
        
        for (int i = 0; i < attributes.getLength(); i++) {
            String qname = attributes.getQName(i);
            String prefix = qname.contains(":") ? qname.substring(0, qname.indexOf(":")) : "";
            Attribute attr = eventFactory.createAttribute(
                    prefix, attributes.getURI(i),
                    attributes.getLocalName(i), attributes.getValue(i));

            if (!qname.equals("xmlns") && !qname.startsWith("xmlns:"))
                toReturn.add(attr);
        }
        
        return toReturn.iterator();
    }

    // 1.1 method

    private void parsePrefixes(String value, EvalContext context) {
        String[] parts = value.split("\\s+");
        for (int i = 0; i < parts.length; i += 2) {
            String prefix = parts[i];
            if (i + 1 < parts.length && prefix.endsWith(":")) {
                String prefixFix = prefix.substring(0, prefix.length() - 1);
                context.setPrefix(prefixFix, parts[i+1]);
                sink.addPrefix(prefixFix, parts[i+1]);
            }
        }
    }
}

/*
 * (c) Copyright 2009 University of Bristol
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
