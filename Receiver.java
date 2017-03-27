import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Suchith Chandrashekhara Arodi
 * @version 1.0
 * 
 */

/*
 * TCP RENO. Class that receives the data pieces from the sender and creates the
 * file.
 */
public class Receiver implements Runnable {
	// class memebers to hold the data
	private static int udp_port;
	public static boolean Quiet = false;// log
	private static DatagramSocket socket = null;
	private static List<Packets> file_parts = new LinkedList<>();
	private static int rseq = 0;

	// Constructor
	public Receiver(int port, Boolean quiet) {
		udp_port = port;
		Quiet = quiet;
	}

	// main method
	public static void main(String[] args) {
		(new Thread(new Receiver(udp_port, Quiet))).start();
	}

	@Override
	public void run() {

		try {
			socket = new DatagramSocket(udp_port);
			byte[] data_arriving = new byte[2880];
			DatagramPacket data_packet = new DatagramPacket(data_arriving, data_arriving.length);
			boolean loop = true;
			String File = "output_new.txt";
			InetAddress dest;
			byte[] dup = null;
			while (loop) {
				if (socket != null) {
					socket.receive(data_packet);
					byte[] data = data_packet.getData();
					dest = data_packet.getAddress();
					ByteArrayInputStream in = new ByteArrayInputStream(data);
					ObjectInputStream msg = new ObjectInputStream(in);
					Packets deserial_msg = (Packets) msg.readObject();
					if (Quiet == false) {
						System.out.println("Received, Sequence no: " + deserial_msg.getSequence_number());
					}
					// check for corruption
					Long pac_cs = deserial_msg.getCheck_sum();
					Calculate_checksum c = new Calculate_checksum();
					Long s = c.checksum(true, deserial_msg.getData(), deserial_msg.getData().length, null);
					if (deserial_msg != null) {
						if (((pac_cs.equals(s))) && (!(contains_seq(file_parts, deserial_msg.getSequence_number())))) {
							file_parts.add(deserial_msg);
						}
					}
					// Sort
					Collections.sort(file_parts, new Comparator<Packets>() {
						public int compare(Packets p1, Packets p2) {
							return p1.getSequence_number() - p2.getSequence_number();
						}
					});

					// Write to the file if all the packets have been received.
					int First = getfirst(file_parts).getSequence_number();
					if (file_parts.size() != 0) {
						int first = getfirst(file_parts).getSequence_number();
						if ((getLast(file_parts).isEnd_of_file())) {
							if ((is_ordered(first, file_parts))) {
								FileOutputStream fileOutputStream = new FileOutputStream(File);
								for (Packets item : file_parts) {
									fileOutputStream.write(item.getData());
									fileOutputStream.flush();
								}
								Calculate_checksum checksum = new Calculate_checksum();
								Long Check = checksum.checksum(false, dup, 0, File);
								String hexcrc = Long.toHexString(Check);
								System.out.println("CRC32 Hash of the file is " + hexcrc);
								loop = false;
								fileOutputStream.close();
							}

						}
						if (First > -1) {
							for (Packets i : file_parts) {
								if (i.getSequence_number() == First) {
									First++;
								} else {
									break;
								}

							}
						}

					}

					// sent the ACK to the sender
					if (socket != null) {
						Packets packet = new Packets();
						packet.setSequence_number(rseq++);
						packet.setACK(First);
						packet.setSource_port(udp_port);
						ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
						try {
							ObjectOutputStream os = new ObjectOutputStream(outputStream);
							os.writeObject(packet);
							byte[] data1 = outputStream.toByteArray();
							DatagramPacket sendPacket = new DatagramPacket(data1, data1.length, dest, udp_port);
							socket.send(sendPacket);
							if (Quiet == false) {
								System.out.println("Sending ACK: " + packet.getACK());
							}
							os.close();
							outputStream.close();

						} catch (IOException e) {
							e.printStackTrace();
						}
					} else {
						System.out.println("Socket null");
					}

				}

			}
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}

	}

	/*
	 * Check to see if the list contains packet with the sequence number.
	 */
	public boolean contains_seq(List<Packets> list, int seq) {
		if (seq > -1) {
			for (Packets item : file_parts) {
				if (item.getSequence_number() == seq) {
					return true;
				}
			}
		}
		return false;

	}

	// Method to get last element
	public static Packets getLast(List<Packets> list) {
		return list != null && !list.isEmpty() ? list.get(list.size() - 1) : null;
	}

	// Method to get first element
	public static Packets getfirst(List<Packets> list) {
		return list != null && !list.isEmpty() ? list.get(0) : null;
	}

	// Method to check if all the packets are ordered by the seq no
	public boolean is_ordered(int first, List<Packets> file) {
		if (file != null && first > -1) {
			for (Packets item : file_parts) {
				if (first != item.getSequence_number()) {
					return false;
				} else {
					first++;
				}
			}
		}
		return true;
	}
}
