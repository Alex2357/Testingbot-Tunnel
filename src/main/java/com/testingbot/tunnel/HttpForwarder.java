package com.testingbot.tunnel;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.testingbot.tunnel.proxy.ForwarderServlet;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
/**
 *
 * @author TestingBot
 */
public class HttpForwarder {
    private App app;
    private final Server httpProxy;
    
    public HttpForwarder(App app) {
        this.app = app;
        httpProxy = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(Integer.parseInt(app.getSeleniumPort()));
        connector.setMaxIdleTime(400000);
        connector.setThreadPool(new QueuedThreadPool(128));
        
        httpProxy.setGracefulShutdown(3000);
        httpProxy.setStopAtShutdown(true);
        
        httpProxy.addConnector(connector);
        ServletHandler servletHandler = new ServletHandler();
        ServletHolder servletHolder = new ServletHolder(new ForwarderServlet(app));
        servletHandler.addServletWithMapping(servletHolder, "/*");
        
        servletHolder.setInitParameter("idleTimeout", "90000");
        servletHolder.setInitParameter("timeout", "90000");
 
        httpProxy.setHandler(servletHandler);
        try {
            httpProxy.start();
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Could not set up local forwarder. Please make sure this program can open port 4445 on this computer.");
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void stop() {
        try {
            httpProxy.stop();
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public boolean testForwarding() {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpGet getRequest = new HttpGet("http://127.0.0.1:" + app.getSeleniumPort());
        
        HttpResponse response;
        try {
            response = httpClient.execute(getRequest);
        } catch (IOException ex) {
            return false;
        }

        return (response.getStatusLine().getStatusCode() == 200);
    }
}

