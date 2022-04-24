package org.crumbleworks.forge.eightfiftyfour;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import org.crumbleworks.forge.eightfiftyfour.processing.TelnetProcessor;
import org.crumbleworks.forge.eightfiftyfour.processing.TelnetSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EchoServerDemo implements TelnetProcessor {

    private static final Logger logger = LoggerFactory
            .getLogger(EchoServerDemo.class);
    private static boolean kill = false; // you should somewhere set this to
                                         // true

    public static void main(String[] args) throws IOException {
        var server = new TelnetServer(new int[] { 23 }, new EchoServerDemo());

        server.start();

        while(!kill) {
            // busy loop; you decide when the server shuts down
        }

        server.close();
    }

    @Override
    public void setup(TelnetSession data) {
        logger.debug("Hello {}", data.address());
    }

    @Override
    public long process(InputStream in, OutputStream out,
            TelnetSession data) {
        try {
            StringBuffer sb = new StringBuffer("Echo: ");
            
            int c = in.read();
            while('\n' != c && -1 != c) {
                sb.append((char)c);
                c = in.read();
            }

            logger.debug("Writing '{}' to {}", sb.toString(), data.address());
            
            var writer = new OutputStreamWriter(out);
            writer.write(sb.toString());
            writer.flush();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        return 0;
    }

    @Override
    public void teardown(InputStream in, TelnetSession data) {
        logger.debug("Goodbye {}", data.address());
    }
}
