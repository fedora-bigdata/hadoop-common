/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package org.apache.hadoop.security.authentication.client;

import junit.framework.Assert;
import org.apache.hadoop.security.authentication.server.AuthenticationFilter;
import junit.framework.TestCase;
import org.mockito.Mockito;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.DispatcherType;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Properties;
import java.util.EnumSet;

public abstract class AuthenticatorTestCase extends TestCase {
  private Server server;
  private String host = null;
  private int port = -1;
  ServletContextHandler context;

  private static Properties authenticatorConfig;

  protected static void setAuthenticationHandlerConfig(Properties config) {
    authenticatorConfig = config;
  }

  public static class TestFilter extends AuthenticationFilter {

    @Override
    protected Properties getConfiguration(String configPrefix, FilterConfig filterConfig) throws ServletException {
      return authenticatorConfig;
    }
  }

  @SuppressWarnings("serial")
  public static class TestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
      resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
      InputStream is = req.getInputStream();
      OutputStream os = resp.getOutputStream();
      int c = is.read();
      while (c > -1) {
        os.write(c);
        c = is.read();
      }
      is.close();
      os.close();
      resp.setStatus(HttpServletResponse.SC_OK);
    }
  }

  protected void start() throws Exception {
    server = new Server(0);
    context = new ServletContextHandler();
    context.setContextPath("/foo");
    server.setHandler(context);
    context.addFilter(new FilterHolder(TestFilter.class), "/*", EnumSet.of(DispatcherType.REQUEST));
    context.addServlet(new ServletHolder(TestServlet.class), "/bar");
    host = "localhost";
    ServerSocket ss = new ServerSocket(0);
    port = ss.getLocalPort();
    ss.close();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost(host);
    connector.setPort(port);
    server.setConnectors(new Connector[] { connector });
    server.start();
    System.out.println("Running embedded servlet container at: http://" + host + ":" + port);
  }

  protected void stop() throws Exception {
    try {
      server.stop();
    } catch (Exception e) {
    }

    try {
      server.destroy();
    } catch (Exception e) {
    }
  }

  protected String getBaseURL() {
    return "http://" + host + ":" + port + "/foo/bar";
  }

  private static class TestConnectionConfigurator
      implements ConnectionConfigurator {
    boolean invoked;

    @Override
    public HttpURLConnection configure(HttpURLConnection conn)
        throws IOException {
      invoked = true;
      return conn;
    }
  }

  private String POST = "test";

  protected void _testAuthentication(Authenticator authenticator, boolean doPost) throws Exception {
    start();
    try {
      URL url = new URL(getBaseURL());
      AuthenticatedURL.Token token = new AuthenticatedURL.Token();
      Assert.assertFalse(token.isSet());
      TestConnectionConfigurator connConf = new TestConnectionConfigurator();
      AuthenticatedURL aUrl = new AuthenticatedURL(authenticator, connConf);
      HttpURLConnection conn = aUrl.openConnection(url, token);
      Assert.assertTrue(token.isSet());
      Assert.assertTrue(connConf.invoked);
      String tokenStr = token.toString();
      if (doPost) {
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
      }
      conn.connect();
      if (doPost) {
        Writer writer = new OutputStreamWriter(conn.getOutputStream());
        writer.write(POST);
        writer.close();
      }
      assertEquals(HttpURLConnection.HTTP_OK, conn.getResponseCode());
      if (doPost) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String echo = reader.readLine();
        assertEquals(POST, echo);
        assertNull(reader.readLine());
      }
      aUrl = new AuthenticatedURL();
      conn = aUrl.openConnection(url, token);
      conn.connect();
      assertEquals(HttpURLConnection.HTTP_OK, conn.getResponseCode());
      assertEquals(tokenStr, token.toString());
    } finally {
      stop();
    }
  }

}
