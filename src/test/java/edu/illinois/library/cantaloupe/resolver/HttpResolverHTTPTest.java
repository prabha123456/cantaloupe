package edu.illinois.library.cantaloupe.resolver;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.test.WebServer;
import org.junit.Before;

import java.net.URI;

public class HttpResolverHTTPTest extends HttpResolverTest {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        server = new WebServer();
        server.setHTTP1Enabled(true);
        server.setHTTP2Enabled(true);
        server.setHTTPS1Enabled(false);
        server.setHTTPS2Enabled(false);

        Configuration config = Configuration.getInstance();
        config.setProperty(Key.HTTPRESOLVER_URL_PREFIX, server.getHTTPURI() + "/");
    }

    @Override
    String getScheme() {
        return "http";
    }

    @Override
    URI getServerURI() {
        return server.getHTTPURI();
    }

}
