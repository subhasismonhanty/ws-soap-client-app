package com.example.soapclient.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.soapclient.client.SoapClient;

@Service
public class ExampleService {

    @Autowired
    private SoapClient soapClient;

    public Object makeExampleRequest(Object request) {
        return soapClient.callSoapService(
            request,
            "http://example.com/soap/action"
        );
    }
} 