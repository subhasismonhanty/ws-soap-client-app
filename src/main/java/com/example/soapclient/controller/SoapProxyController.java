package com.example.soapclient.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.example.soapclient.service.SoapProxyService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CrossOrigin
@RestController
@RequestMapping("/api/soap")
public class SoapProxyController {
    
    private static final Logger logger = LoggerFactory.getLogger(SoapProxyController.class);
    
    @Autowired
    private SoapProxyService soapProxyService;

    @PostMapping(value = "/{serviceName}", 
                consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
                produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public String proxyRequest(
            @PathVariable String serviceName,
            @RequestBody String xmlPayload,
            @RequestHeader(value = "SOAPAction", required = false) String soapAction,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("Received request for service: {}", serviceName);
        logger.debug("XML Payload: {}", xmlPayload);
        logger.debug("SOAPAction: {}", soapAction);
        
        return soapProxyService.processSoapRequest(serviceName, xmlPayload, soapAction, headers);
    }
} 