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
            int availableBytes = in.available();
            if(0 == availableBytes) {
                logger.debug("Wait for next message");
                return -1;
            }

            logger.debug("Writing to {}", data.address());

            var writer = new OutputStreamWriter(out);
            writer.write("Echo: ");
            out.write(in.readNBytes(availableBytes));
            writer.write('\n');
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
