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
} 