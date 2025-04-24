package com.example.soapclient.config;

import com.example.soapclient.client.SoapClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;

@Configuration
public class SoapClientConfig {

    @Value("${soap.service.username:test}")
    private String username;

    @Value("${soap.service.password:test123}")
    private String password;

    @Bean
    public WebServiceTemplate webServiceTemplate() {
        return new WebServiceTemplate();
    }

    @Bean
    public Wss4jSecurityInterceptor securityInterceptor() {
        Wss4jSecurityInterceptor securityInterceptor = new Wss4jSecurityInterceptor();
        securityInterceptor.setSecurementActions("UsernameToken Timestamp");
        securityInterceptor.setSecurementUsername(username);
        securityInterceptor.setSecurementPassword(password);
        securityInterceptor.setSecurementPasswordType("PasswordDigest");
        return securityInterceptor;
    }

    @Bean
    public SoapClient soapClient() {
        SoapClient client = new SoapClient();
        client.setWebServiceTemplate(webServiceTemplate());
        return client;
    }
} 