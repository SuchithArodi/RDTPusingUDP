import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @author Suchith Chandrashekhara Arodi
 * @version 1.0
 * 
 */

/*
 * Main class 
 */
public class fcntcp {
	// variables to hold the input details.
	private static boolean client = false;
	private static boolean server = false;
	private static String filename;
	private static long time_out = 1000;
	private static Boolean quiet = false;
	
	// Main method 
	public static void main(String[] args) throws UnknownHostException {
		int argu_length = args.length;
		int port = 0;
		String address = null;
		int next;
		
		// parsing the input 
		for (int i = 0; i < argu_length; i++) {
			if (args[i].equals("-s")) {
				server = true;
			}
			if (args[i].equals("-f")) {
				int pos = i + 1;
				filename = args[pos];
				i++;
			}
			if (args[i].equals("-c")) {
				client = true;
			}

			if (args[i].equals("-t")) {
				next = i + 1;
				time_out = Long.parseLong(args[next]);
				i++;
			}
			if (args[i].equals("-q")) {
				quiet = true;
			}
		}
		port = Integer.parseInt(args[argu_length - 1]);
		address = args[argu_length - 2];
		
		// Call out the specific functionality 
		if (client) {
			(new Thread(new Sender(port, InetAddress.getByName(address), time_out, quiet, filename))).start();

		} else if (server) {
			(new Thread(new Receiver(port, quiet))).start();
		} else {
			System.out.println("Invalid Command Line!\nPlease follow the CLI guidelines.");
		}
	}

}
