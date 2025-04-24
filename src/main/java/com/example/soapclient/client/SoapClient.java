package com.example.soapclient.client;

import org.springframework.ws.client.core.support.WebServiceGatewaySupport;
import org.springframework.ws.soap.client.core.SoapActionCallback;

public class SoapClient extends WebServiceGatewaySupport {

    public Object callSoapService(Object request, String soapAction) {
        return getWebServiceTemplate().marshalSendAndReceive(
            request,
            new SoapActionCallback(soapAction)
        );
    }
} 