package com.example.soapclient.config;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.http.HttpComponentsConnection;
import org.springframework.ws.transport.http.HttpComponentsMessageSender;

import java.io.IOException;
import java.net.URI;

// This custom message sender extends HttpComponentsMessageSender
// and attempts to remove the Content-Length header using getHttpRequest()
// or reflection if necessary.
public class CustomHttpComponentsMessageSender extends HttpComponentsMessageSender {

    // Logger for this class
    private static final Logger logger = LoggerFactory.getLogger(CustomHttpComponentsMessageSender.class);

    // Constructor that takes an HttpClient, similar to the parent class
    public CustomHttpComponentsMessageSender(HttpClient httpClient) {
        super(httpClient);
        // Create a new HttpClient with our interceptor
        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.addInterceptorFirst(new HeaderCleanupInterceptor());
        
        // If the provided client is a CloseableHttpClient, copy its configuration
        if (httpClient instanceof org.apache.http.impl.client.CloseableHttpClient) {
            // Set the new client with our interceptor
            setHttpClient(builder.build());
        }
    }

    // Override the createConnection method to intercept the request
    // and remove the problematic header before the connection is established.
    @Override
    public WebServiceConnection createConnection(URI uri) throws IOException {
        WebServiceConnection connection = super.createConnection(uri);
        
        if (connection instanceof HttpComponentsConnection) {
            HttpComponentsConnection httpConnection = (HttpComponentsConnection) connection;
            HttpPost httpPost = httpConnection.getHttpPost();
            
            // Remove Content-Length and Transfer-Encoding headers if they exist
            if (httpPost.containsHeader("Content-Length")) {
                httpPost.removeHeaders("Content-Length");
                logger.debug("Removed Content-Length header from HttpPost");
            }
            if (httpPost.containsHeader("Transfer-Encoding")) {
                httpPost.removeHeaders("Transfer-Encoding");
                logger.debug("Removed Transfer-Encoding header from HttpPost");
            }
        }
        
        return connection;
    }

    private static class HeaderCleanupInterceptor implements HttpRequestInterceptor {
        @Override
        public void process(HttpRequest request, HttpContext context) throws IOException {
            // Log headers before cleanup
            logger.debug("Headers before cleanup:");
            for (Header header : request.getAllHeaders()) {
                logger.debug("{}: {}", header.getName(), header.getValue());
            }

            // Remove problematic headers
            if (request.containsHeader("Content-Length")) {
                request.removeHeaders("Content-Length");
                logger.debug("Removed Content-Length header in interceptor");
            }
            if (request.containsHeader("Transfer-Encoding")) {
                request.removeHeaders("Transfer-Encoding");
                logger.debug("Removed Transfer-Encoding header in interceptor");
            }

            // Log headers after cleanup
            logger.debug("Headers after cleanup:");
            for (Header header : request.getAllHeaders()) {
                logger.debug("{}: {}", header.getName(), header.getValue());
            }
        }
    }
}
