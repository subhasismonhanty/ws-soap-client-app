package com.example.soapclient.config;

import lombok.Data;

@Data
public class SoapServiceConfig {
    private String url;
    private boolean headerRequired;
    private String namespace;  // default namespace
    private String envelopePrefix;           // default prefix
    private String envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/"; // default SOAP namespace
    private String requestNamespace;
    private String bodyNamespace;
    private String bodyPrefix = "soap";               // default body prefix
    private String startTag = "<Add>";
    private String endTag = "</Add>";
    private String soapAction = "http://tempuri.org/Add";  // default SOAP action
    private String username;  // username for UserInfo
    private String correlation;  // correlation ID for UserInfo
    private boolean proxyEnabled;  // new flag: enable proxy
    private String proxyHost;     // proxy hostname for this service
    private int proxyPort; // proxy port for this service
} 