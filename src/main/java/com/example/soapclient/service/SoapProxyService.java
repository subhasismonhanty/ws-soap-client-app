package com.example.soapclient.service;

import com.example.soapclient.config.SoapServiceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
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
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.xml.transform.StringSource;

import javax.annotation.PostConstruct;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

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

    @Autowired
    private Wss4jSecurityInterceptor securityInterceptor;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            serviceConfigs = mapper.readValue(servicesJson, new TypeReference<Map<String, SoapServiceConfig>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse soap.services configuration", e);
        }
    }

    public String processSoapRequest(String serviceName, String xmlPayload, String soapAction, Map<String, String> headers) {
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

        try {
            // Configure security interceptor based on headerRequired
            if (serviceConfig.isHeaderRequired()) {
                logger.info("Adding security interceptor for service: {}", serviceName);
                webServiceTemplate.setInterceptors(new ClientInterceptor[]{securityInterceptor});
            } else {
                logger.info("Removing security interceptor for service: {}", serviceName);
                webServiceTemplate.setInterceptors(new ClientInterceptor[]{});
            }

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
            };

            // Send request to SOAP service
            webServiceTemplate.sendSourceAndReceiveToResult(
                serviceConfig.getUrl(),
                requestSource,
                messageCallback,
                result
            );

            String response = responseWriter.toString();
            logger.debug("Received SOAP response: {}", response);
            return response;

        } finally {
            // Reset interceptors to default state
            webServiceTemplate.setInterceptors(new ClientInterceptor[]{});
        }
    }

    private void addSecurityHeader(SoapMessage soapMessage) {
        SoapHeader header = soapMessage.getSoapHeader();
        
        // Create Security element
        QName securityQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "Security",
            "wsse"
        );
        SoapHeaderElement security = header.addHeaderElement(securityQName);
        security.addAttribute(new QName("http://schemas.xmlsoap.org/soap/envelope/", "mustUnderstand"), "1");
        security.addAttribute(new QName("xmlns:wsu"), 
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd");

        // Add Timestamp
        QName timestampQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "Timestamp",
            "wsu"
        );
        SoapHeaderElement timestamp = header.addHeaderElement(timestampQName);
        String timestampId = "TS-" + UUID.randomUUID();
        timestamp.addAttribute(new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "Id",
            "wsu"
        ), timestampId);

        // Add Created and Expires elements
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime expiresTime = now.plusMinutes(5);
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        QName createdQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "Created",
            "wsu"
        );
        SoapHeaderElement created = header.addHeaderElement(createdQName);
        created.setText(formatter.format(now));

        QName expiresQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "Expires",
            "wsu"
        );
        SoapHeaderElement expires = header.addHeaderElement(expiresQName);
        expires.setText(formatter.format(expiresTime));

        // Add UsernameToken
        QName usernameTokenQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "UsernameToken",
            "wsse"
        );
        SoapHeaderElement usernameToken = header.addHeaderElement(usernameTokenQName);
        String tokenId = "UsernameToken-" + UUID.randomUUID();
        usernameToken.addAttribute(new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
            "Id",
            "wsu"
        ), tokenId);

        // Add Username
        QName usernameQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "Username",
            "wsse"
        );
        SoapHeaderElement usernameElement = header.addHeaderElement(usernameQName);
        usernameElement.setText(username);

        // Generate nonce
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        
        // Create password digest
        String createdTime = formatter.format(now);
        String passwordDigest = DigestUtils.sha1Hex(nonce + createdTime + password);

        // Add Password
        QName passwordQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "Password",
            "wsse"
        );
        SoapHeaderElement passwordElement = header.addHeaderElement(passwordQName);
        passwordElement.addAttribute(new QName("Type"), 
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest");
        passwordElement.setText(passwordDigest);

        // Add Nonce
        QName nonceQName = new QName(
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
            "Nonce",
            "wsse"
        );
        SoapHeaderElement nonceElement = header.addHeaderElement(nonceQName);
        nonceElement.addAttribute(new QName("EncodingType"), 
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary");
        nonceElement.setText(nonce);

        // Add Created to UsernameToken
        SoapHeaderElement createdElement = header.addHeaderElement(createdQName);
        createdElement.setText(createdTime);
    }
} 