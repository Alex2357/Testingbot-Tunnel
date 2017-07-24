package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.thread.QueuedThreadPool;


public class TunnelProxyServlet extends AsyncProxyServlet {
    
    class TunnelProxyResponseListener extends ProxyResponseListener
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        public long startTime = System.currentTimeMillis();
        
        protected TunnelProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
        {
            super(request, response);
            this.request = request;
            this.response = response;
        }
        
        @Override
        public void onBegin(Response proxyResponse)
        {
            startTime = System.currentTimeMillis();
            super.onBegin(proxyResponse);
        }
        
        @Override
        public void onComplete(Result result)
        {
            long endTime = System.currentTimeMillis();
            Logger.getLogger(App.class.getName()).log(Level.INFO, "<< [{0}] {1} ({2}) - {3}", new Object[]{request.getMethod(), request.getRequestURL().toString(), response.toString().substring(9, 12), (endTime-this.startTime) + " ms"});
            if (getServletConfig().getInitParameter("tb_debug") != null) {
                Enumeration<String> headerNames = request.getHeaderNames();
                if (headerNames != null) {
                    StringBuilder sb = new StringBuilder();
                    String header;
 
                    while (headerNames.hasMoreElements()) {
                        header = headerNames.nextElement();
                        sb.append(header).append(": ").append(request.getHeader(header)).append(System.getProperty("line.separator"));
                    }
                    
                    Logger.getLogger(App.class.getName()).log(Level.INFO, sb.toString());
                }
            }
            super.onComplete(result);
        }
    }
    
    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure)
    {
        if (clientRequest.getRequestURL().toString().indexOf("squid-internal") == -1) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING, "{0} for request {1}\n{2}", new Object[]{failure.getMessage(), clientRequest.getMethod() + " - " + clientRequest.getRequestURL().toString(), ExceptionUtils.getStackTrace(failure)});
        }
        
        super.onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
    }
    
    @Override
    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
    {
        return new TunnelProxyResponseListener(request, response);
    }
    
    @Override
    protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest)
    {
        super.addProxyHeaders(clientRequest, proxyRequest);
        if (getServletContext().getAttribute("extra_headers") != null) {
            HashMap<String, String> headers = (HashMap<String, String>) getServletContext().getAttribute("extra_headers");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                proxyRequest.header(entry.getKey(), entry.getValue());
            }
        }
    }
    
    protected HttpClient createHttpClient() throws ServletException
    {
        ServletConfig config = getServletConfig();

        HttpClient client = newHttpClient();

        // Redirects must be proxied as is, not followed.
        client.setFollowRedirects(false);

        // Must not store cookies, otherwise cookies of different clients will mix.
        client.setCookieStore(new HttpCookieStore.Empty());

        Executor executor;
        String value = config.getInitParameter("maxThreads");
        if (value == null || "-".equals(value))
        {
            executor = (Executor)getServletContext().getAttribute("org.eclipse.jetty.server.Executor");
            if (executor==null)
                throw new IllegalStateException("No server executor for proxy");
        }
        else
        {
            QueuedThreadPool qtp= new QueuedThreadPool(Integer.parseInt(value));
            String servletName = config.getServletName();
            int dot = servletName.lastIndexOf('.');
            if (dot >= 0)
                servletName = servletName.substring(dot + 1);
            qtp.setName(servletName);
            executor=qtp;
        }

        client.setExecutor(executor);

        value = config.getInitParameter("maxConnections");
        if (value == null)
            value = "256";
        client.setMaxConnectionsPerDestination(Integer.parseInt(value));

        value = config.getInitParameter("idleTimeout");
        if (value == null)
            value = "30000";
        client.setIdleTimeout(Long.parseLong(value));

        value = config.getInitParameter("timeout");
        if (value == null)
            value = "60000";
        setTimeout(Long.parseLong(value));

        value = config.getInitParameter("requestBufferSize");
        if (value != null)
            client.setRequestBufferSize(Integer.parseInt(value));

        value = config.getInitParameter("responseBufferSize");
        if (value != null)
            client.setResponseBufferSize(Integer.parseInt(value));

        try
        {
            client.start();

            // Content must not be decoded, otherwise the client gets confused.
            client.getContentDecoderFactories().clear();

            return client;
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }
    
    @Override
    protected HttpClient newHttpClient()
    {
        HttpClient client = new HttpClient();
        
        final String proxy = getServletConfig().getInitParameter("proxy");
        if (proxy != null && !proxy.isEmpty())
        {
            String[] splitted = proxy.split(":");
            ProxyConfiguration proxyConfig = client.getProxyConfiguration();
            proxyConfig.getProxies().add(new HttpProxy(splitted[0], Integer.parseInt(splitted[1])));
            
            String proxyAuth = getServletConfig().getInitParameter("proxyAuth");
            if (proxyAuth != null && !proxyAuth.isEmpty())
            {
                String[] credentials = proxyAuth.split(":");
                
                AuthenticationStore auth = client.getAuthenticationStore();
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Proxy auth " + credentials[0] + " : " + credentials[1]);
                try {
                    auth.addAuthentication(new BasicAuthentication(new URI("http://" + proxy), Authentication.ANY_REALM, credentials[0], credentials[1]));
                } catch (URISyntaxException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
        }
        
        return client;
    }
}
