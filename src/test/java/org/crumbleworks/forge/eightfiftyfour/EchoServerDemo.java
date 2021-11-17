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

	public static void main(String[] args) {
		var server = new TelnetServer(new int[]{23}, new EchoServerDemo());
		
		server.start();
	}
	
	@Override
	public long process(InputStream in, OutputStream out, TelnetSession data) {
		logger.info("PROCESSING");
		var writer = new OutputStreamWriter(out);
		try {
			writer.write("Echo: ");
			out.write(in.readAllBytes());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		return -1;
	}
}
