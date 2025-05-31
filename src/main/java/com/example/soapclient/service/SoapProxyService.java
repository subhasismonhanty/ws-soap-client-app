package com.example.soapclient.service;

import com.example.soapclient.config.CustomHttpComponentsMessageSender;
import com.example.soapclient.config.SoapServiceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpClient;
import org.apache.http.HttpRequestInterceptor; // Import HttpRequestInterceptor
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.SoapHeader;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.transport.WebServiceMessageSender;
import org.springframework.ws.transport.http.HttpComponentsMessageSender;
import org.springframework.xml.transform.StringSource;
import javax.xml.transform.dom.DOMSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import java.io.*;

import org.w3c.dom.Document;
import javax.annotation.PostConstruct;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.Base64;

import org.apache.xml.security.c14n.Canonicalizer;

import java.nio.charset.StandardCharsets;

@Service
public class SoapProxyService {
    private static final Logger logger = LoggerFactory.getLogger(SoapProxyService.class);

    @Value("${soap.services}")
    private String servicesJson;

    private Map<String, SoapServiceConfig> serviceConfigs;

    @Value("${soap.service.username:test}")
    private String username;

    @Value("${soap.service.password:test123}")
    private String password;

    @Autowired
    private WebServiceTemplate webServiceTemplate;

    @Value("${keystore.path}")
    private String keystorePath;

    @Value("${keystore.password}")
    private String keystorePassword;

    @Value("${keystore.alias}")
    private String keystoreAlias;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            serviceConfigs = mapper.readValue(servicesJson, new TypeReference<Map<String, SoapServiceConfig>>() {});

            // Initialize XML Security
            org.apache.xml.security.Init.init();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize", e);
        }
    }

    public String processSoapRequest(String serviceName, String xmlPayload, String soapAction, Map<String, String> headers) {
        String response = null;
        // Get the service configuration
        SoapServiceConfig serviceConfig = serviceConfigs.get(serviceName);
        if (serviceConfig == null) {
            throw new IllegalArgumentException("Unknown service: " + serviceName);
        }

        // Use provided soapAction or fall back to configured one
        String effectiveSoapAction = soapAction != null ? soapAction : serviceConfig.getSoapAction();

        logger.info("Service: {}, headerRequired: {}, soapAction: {}",
                serviceName, serviceConfig.isHeaderRequired(), effectiveSoapAction);
        logger.info("Original payload: {}", xmlPayload);

        // Extract the content using configured tags
        String startTag = serviceConfig.getStartTag() != null ? serviceConfig.getStartTag() : "<Add>";
        String endTag = serviceConfig.getEndTag() != null ? serviceConfig.getEndTag() : "</Add>";

        String actualRequest;
        int startIndex = xmlPayload.indexOf(startTag);
        int endIndex = xmlPayload.indexOf(endTag) + endTag.length();

        if (startIndex != -1 && endIndex != -1) {
            actualRequest = xmlPayload.substring(startIndex, endIndex);
            logger.info("Extracted content using tags {} and {}: {}", startTag, endTag, actualRequest);
        } else {
            throw new IllegalArgumentException(
                    String.format("Could not find content between tags %s and %s in payload", startTag, endTag));
        }

        // Extract just the content between tags
        String tagContent = actualRequest.substring(
                actualRequest.indexOf(">") + 1,
                actualRequest.lastIndexOf("<")
        ).trim();

        // Get namespace configuration with defaults
        String requestNamespace = serviceConfig.getRequestNamespace() != null ?
                serviceConfig.getRequestNamespace() : "http://tempuri.org/";

        // Create the request with configured namespace
        String tagName = startTag.substring(1, startTag.length() - 1); // Remove < and > from startTag
        String formattedRequest = String.format(
                "<%s xmlns=\"%s\">%s</%s>",
                tagName,
                requestNamespace,
                tagContent,
                tagName
        );

        logger.info("Formatted request: {}", formattedRequest);

        // Create source from the formatted request
        Source requestSource = new StringSource(formattedRequest);

        // Prepare response writer
        StringWriter responseWriter = new StringWriter();
        StreamResult result = new StreamResult(responseWriter);

        // Store the original message sender to restore later
        WebServiceMessageSender[] originalMessageSenders = webServiceTemplate.getMessageSenders();

        try {
            // Remove all interceptors to prevent WSS4J from adding its headers
            webServiceTemplate.setInterceptors(new ClientInterceptor[]{});

            // --- MODIFIED: configure HTTP proxy and use CustomHttpComponentsMessageSender ---
            if (serviceConfig.isProxyEnabled()
                    && serviceConfig.getProxyHost() != null
                    && serviceConfig.getProxyPort() > 0) {

                logger.info("Using proxy {}:{} for SOAP request", serviceConfig.getProxyHost(), serviceConfig.getProxyPort());
                HttpClientBuilder builder = HttpClientBuilder.create();
                HttpHost proxy = new HttpHost(serviceConfig.getProxyHost(), serviceConfig.getProxyPort());
                builder.setProxy(proxy);

                // Build the HttpClient with proxy configuration
                HttpClient client = builder.build();

                // Use our custom message sender which will handle headers properly
                CustomHttpComponentsMessageSender messageSender = new CustomHttpComponentsMessageSender(client);
                webServiceTemplate.setMessageSender(messageSender);

            } else {
                // If no proxy is enabled, use the default HttpComponentsMessageSender
                webServiceTemplate.setMessageSender(new HttpComponentsMessageSender());
            }
            // --- end modified configuration ---


            // Create message callback with configurable envelope settings
            WebServiceMessageCallback messageCallback = message -> {
                SoapMessage soapMessage = (SoapMessage) message;

                // Always set SOAPAction if available
                if (effectiveSoapAction != null && !effectiveSoapAction.isEmpty()) {
                    logger.info("Setting SOAPAction: {}", effectiveSoapAction);
                    soapMessage.setSoapAction(effectiveSoapAction);
                }

                // Configure envelope namespace if provided
                if (serviceConfig.getEnvelopeNamespace() != null) {
                    soapMessage.getEnvelope().addNamespaceDeclaration(
                            serviceConfig.getEnvelopePrefix() != null ? serviceConfig.getEnvelopePrefix() : "soap",
                            serviceConfig.getEnvelopeNamespace()
                    );
                }

                // Configure body namespace if provided
                if (serviceConfig.getBodyNamespace() != null) {
                    soapMessage.getEnvelope().getBody().addNamespaceDeclaration(
                            serviceConfig.getBodyPrefix() != null ? serviceConfig.getBodyPrefix() : "soap",
                            serviceConfig.getBodyNamespace()
                    );
                }

                // Add our custom security header if required
                if (serviceConfig.isHeaderRequired()) {
                    addSecurityHeader(soapMessage, serviceName);
                }
            };

            // Send request to SOAP service
            webServiceTemplate.sendSourceAndReceiveToResult(
                    serviceConfig.getUrl(),
                    requestSource,
                    messageCallback,
                    result
            );

            response = responseWriter.toString();
            logger.debug("Received SOAP response: {}", response);

        } catch (Exception e) {
            logger.error("Error processing SOAP request", e);
            e.printStackTrace(); // Consider more robust error handling
            throw new RuntimeException("Error processing SOAP request", e); // Re-throw to indicate failure
        } finally {
            // Reset message sender to the original one
            webServiceTemplate.setMessageSenders(originalMessageSenders);
            // Reset interceptors to default state (which is likely empty in this case)
            webServiceTemplate.setInterceptors(new ClientInterceptor[]{});
        }
        return response;
    }

    private String createDigest(String algorithm, String userInfoContent) throws Exception {
        MessageDigest msgDigest = MessageDigest.getInstance(algorithm, "SUN");
        msgDigest.update(userInfoContent.getBytes());
        return Base64.getEncoder().encodeToString(msgDigest.digest());
    }

    private String canonicalize(String signedInfoString) throws Exception {
        // Parse the SignedInfo XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(signedInfoString)));

        // Canonicalize the SignedInfo using ByteArrayOutputStream
        Canonicalizer canonicalizer = Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        canonicalizer.canonicalizeSubtree(doc.getDocumentElement(), baos);
        return baos.toString("UTF-8");
    }

    private String createSignature(String algorithm, String canonValue) throws Exception {
        // Load the keystore from external path
        KeyStore keystore = KeyStore.getInstance("JKS");

        // Check if the path starts with "file:" or "classpath:"
        String path = keystorePath;
        if (path.startsWith("file:")) {
            // Remove "file:" prefix and load from file system
            path = path.substring(5);
            try (FileInputStream fis = new FileInputStream(path)) {
                keystore.load(fis, keystorePassword.toCharArray());
            }
        } else {
            // Assume classpath resource
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is == null) {
                    throw new FileNotFoundException("Keystore file not found at: " + path);
                }
                keystore.load(is, keystorePassword.toCharArray());
            }
        }

        // Get the private key
        Key key = keystore.getKey(keystoreAlias, keystorePassword.toCharArray());
        if (!(key instanceof PrivateKey)) {
            throw new RuntimeException("The specified key alias '" + keystoreAlias + "' does not contain a private key");
        }
        PrivateKey privateKey = (PrivateKey) key;

        // Create and initialize the signature
        Signature sig = Signature.getInstance(algorithm);
        sig.initSign(privateKey);
        sig.update(canonValue.getBytes(StandardCharsets.UTF_8));

        // Generate the signature
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private void addSecurityHeader(SoapMessage soapMessage, String serviceName) {
        try {
            SoapHeader header = soapMessage.getSoapHeader();

            // Get current service config
            SoapServiceConfig serviceConfig = serviceConfigs.get(serviceName);

            // Create timestamp in GMT+00:00
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            String timestamp = sdf.format(new Date());

            // Get username and correlation from service config
            String effectiveUsername = serviceConfig != null && serviceConfig.getUsername() != null ?
                    serviceConfig.getUsername() : username;
            String effectiveCorr = serviceConfig != null && serviceConfig.getCorrelation() != null ?
                    serviceConfig.getCorrelation() : "_CORR_";

            // Format the UserInfo string
            String userInfoContent = String.format("USER=%s;CORR=%s;TIMESTAMP=%s",
                    effectiveUsername,
                    effectiveCorr,
                    timestamp);

            // Create Security element
            QName securityQName = new QName(
                    "http://schemas.xmlsoap.org/ws/2002/4/secext",
                    "Security",
                    "wsse"
            );
            SoapHeaderElement security = header.addHeaderElement(securityQName);

            // Add DisableInclusivePrefixList
            String disablePrefix =
                    "<sunsp:DisableInclusivePrefixList xmlns:sunsp=\"htt://schemas.sun.com/2006/03/wss/client\"></sunsp:DisableInclusivePrefixList>";

            // Create digest value
            String digestValue = createDigest("SHA1", userInfoContent);

            // Create SignedInfo section
            String signedInfoString = String.format(
                    "<ds:SignedInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">" +
                            "<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/>" +
                            "<ds:SignatureMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#rsa-sha1\"/>" +
                            "<ds:Reference URI=\"#secinfo\">" +
                            "<ds:DigestMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#sha1\"/>" +
                            "<ds:DigestValue>%s</ds:DigestValue>" +
                            "<ds:Transforms>" +
                            "<ds:Transform Algorithm=\"http://www.w3.org/TR/1999/REC-xpath-19991116\">" +
                            "<ds:XPath>//*[@id='secinfo']/child::*/text()</ds:XPath>" +
                            "</ds:Transform>" +
                            "</ds:Transforms>" +
                            "</ds:Reference>" +
                            "</ds:SignedInfo>",
                    digestValue
            );

            // Canonicalize SignedInfo
            String canonicalizedSignedInfo = canonicalize(signedInfoString);

            // Create signature value
            String signatureValue = createSignature("SHA1withRSA", canonicalizedSignedInfo);

            // Create complete Signature section
            String signatureSection = String.format(
                    "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">" +
                            "%s" +
                            "<ds:SignatureValue>%s</ds:SignatureValue>" +
                            "<ds:KeyInfo>" +
                            "<ds:KeyName>%s</ds:KeyName>" +
                            "</ds:KeyInfo>" +
                            "</ds:Signature>",
                    signedInfoString,
                    signatureValue,
                    effectiveUsername
            );

            // Create UsernameToken section
            String tokenXml = String.format(
                    "<t:UsernameToken xmlns:t=\"http://schemas.xmlsoap.org/ws/2002/4/secext\" id=\"secinfo\">" +
                            "<t:UserInfo>%s</t:UserInfo>" +
                            "</t:UsernameToken>",
                    userInfoContent
            );

            // Parse and add all sections to the security header
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            // Add DisableInclusivePrefixList
            Document disableDoc = builder.parse(new InputSource(new StringReader(disablePrefix)));
            DOMSource securitySource = (DOMSource) security.getSource();
            org.w3c.dom.Node securityNode = securitySource.getNode();
            securityNode.appendChild(
                    securityNode.getOwnerDocument().importNode(disableDoc.getDocumentElement(), true)
            );

            // Add Signature section
            Document signatureDoc = builder.parse(new InputSource(new StringReader(signatureSection)));
            securityNode.appendChild(
                    securityNode.getOwnerDocument().importNode(signatureDoc.getDocumentElement(), true)
            );

            // Add UsernameToken section
            Document tokenDoc = builder.parse(new InputSource(new StringReader(tokenXml)));
            securityNode.appendChild(
                    securityNode.getOwnerDocument().importNode(tokenDoc.getDocumentElement(), true)
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to create security header", e);
        }
    }
}
