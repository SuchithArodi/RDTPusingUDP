import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * @author Suchith Chandrashekhara Arodi
 * @version 1.0
 * 
 */

/*
 * TCP RENO implementation Class divides the data file into small pieces and
 * sends over the network.
 */
public class Sender implements Runnable {
	// Different variables
	public static DatagramSocket socket = null; // UDP socket
	public static int Port;// Port
	private static InetAddress destination_address;// Destination address
	public static long timeout_value;// timeout
	public static boolean quiet = false;// log
	private static String file_name; // name of the file
	private int seq_no = 0;
	private static List<Window> file_parts = new LinkedList<>();
	public static int[] arr = new int[4];
	public float congestion_window;
	public int thresh = 6;
	private int congesion_size = 3;
	byte[] dup;

	// Constructor
	public Sender(int port, InetAddress address, long time_out, Boolean Quiet, String sendfile) {
		Port = port;
		destination_address = address;
		timeout_value = time_out;
		quiet = Quiet;
		file_name = sendfile;
	}

	// main method
	public static void main(String[] args) {
		(new Thread(new Sender(Port, destination_address, timeout_value, quiet, file_name))).start();
	}

	@Override
	public void run() {
		try {
			socket = new DatagramSocket(Port);
		} catch (SocketException e1) {
			e1.printStackTrace();
		}
		Calculate_checksum checksum = new Calculate_checksum();
		arr[0] = (congesion_size * 2) - thresh;
		arr[1] = (thresh) / (congesion_size * 2);
		arr[3] = arr[1];
		congestion_window = (float) arr[1];
		try {
			int offset = 0;
			Long Check = checksum.checksum(false, dup, 0, file_name);
			String hexcrc = Long.toHexString(Check);
			System.out.println("CRC32 Hash of the file is " + hexcrc);

			// splitting the file into chunks
			byte[] data = Files.readAllBytes(Paths.get(file_name));
			while (offset < data.length) {
				byte[] output_byte;
				if (data.length - offset < 1440) {
					output_byte = new byte[data.length - offset];
					System.arraycopy(data, offset, output_byte, 0, data.length - offset);
					Packets pac = new Packets();
					pac.setData(output_byte);
					pac.setSequence_number(seq_no++);
					pac.setEnd_of_file(true);
					Window w = new Window();
					w.setPacket(pac);
					file_parts.add(w);
					break;
				}
				output_byte = new byte[1440];
				System.arraycopy(data, offset, output_byte, 0, 1440);
				offset += 1440;
				Packets pac = new Packets();
				pac.setData(output_byte);
				pac.setSequence_number(seq_no++);
				Window w = new Window();
				w.setPacket(pac);
				file_parts.add(w);
			}

			// sending the first packet
			UDP_Send(socket, destination_address, Port, 0, file_parts, true);

			// Starting the timer and time out task
			Timer timer = new Timer();
			TimerTask task = new TimerTask() {
				@Override
				public void run() {
					int j = arr[0];
					while (j < arr[1]) {
						if (timeout_value < (System.currentTimeMillis() - file_parts.get(j).getTime_stamp())) {
							if ((file_parts.get(j) != null) && (!file_parts.get(j).isAck())) {
								try {
									UDP_Send(socket, destination_address, Port, j, file_parts, false);
								} catch (IOException e) {
									e.printStackTrace();
								}

							}
						}
						j++;
					}
				}
			};
			if (task != null) {
				timer.schedule(task, 0, 1000);
			}

			// setting end of file for the last packet
			if (arr[0] == 0)
				file_parts.get(file_parts.size() - 1).getPacket().setEnd_of_file(true);

			// Start a new thread for handling ACK from the receiver
			Runnable Rev_Ack = new Runnable() {
				@Override
				public void run() {
					Boolean start_receiving = false;
					Boolean ack_check = true;
					byte[] data_arriving = new byte[2880];
					DatagramPacket data_packet = new DatagramPacket(data_arriving, data_arriving.length);
					while (!start_receiving) {
						try {
							// Receive the ACK from the other side
							socket.receive(data_packet);
							byte[] data = data_packet.getData();
							ByteArrayInputStream in = new ByteArrayInputStream(data);
							ObjectInputStream msg = new ObjectInputStream(in);
							Packets deserial_pac = (Packets) msg.readObject();
							int maintain_ack = arr[0];
							if (quiet == false) {
								System.out.println("ACK Received: " + deserial_pac.getACK());
							}

							// maintain the ACK
							if (data != null) {
								while (arr[1] > maintain_ack) {
									if ((deserial_pac != null) && (deserial_pac.getACK() == file_parts.get(maintain_ack)
											.getPacket().getSequence_number())) {
										if (!(deserial_pac.getACK() < 0)) {
											file_parts.get(maintain_ack).increment_dup();
											if ((file_parts.get(maintain_ack) != null)
													&& (file_parts.get(maintain_ack).isAck() == true)) {
												file_parts.get(maintain_ack).increment_dup();
											} else {
												file_parts.get(maintain_ack).setAck(true);
											}
											if ((file_parts.get(maintain_ack) != null)
													&& (file_parts.get(maintain_ack).getDupack() > 3)) {
												ack_check = false;
											}
										} else {
											if (quiet == false)
												System.out.println("Negative Ack");
										}
									}
									if ((deserial_pac != null) && (deserial_pac.getACK() > file_parts.get(maintain_ack)
											.getPacket().getSequence_number())) {
										if ((file_parts.get(maintain_ack) != null))
											file_parts.get(maintain_ack).setAck(true);
									}
									maintain_ack++;
								}
							}

							// send again if lost
							if (!ack_check) {
								int ack = deserial_pac.getACK();
								int cong = (int) congestion_window;
								if (thresh > 0 && congestion_window > 0) {
									thresh = cong;
									congestion_window = (int) (congestion_window / 2);
									if (quiet == false) {
										System.out.println("ssthresh = " + thresh);
									}
								}
								if (congestion_window != -1) {
									UDP_Send(socket, destination_address, Port, ack, file_parts, true);
									arr[2] = arr[1];
								}

							} else if (ack_check) {
								int ack = deserial_pac.getACK();

								// Maintain congestion window
								if ((thresh > 0) && (congestion_window > 0)) {
									if (thresh < congestion_window) {
										congestion_window = congestion_window + (((float) arr[3]) / congestion_window);
									} else if (thresh >= congestion_window) {
										congestion_window = congestion_window + 1;
									}
									if (quiet == false) {
										System.out.println("Congestion window size: " + congestion_window);
									}
								}

								if (deserial_pac != null) {
									do {
										if (ack == arr[0]) {
											// dup[0] = 0;
										} else {
											arr[0] = ack;
										}
										arr[2] = arr[0] + (int) congestion_window;
									} while (arr[0] == -1);
								}
								int loop_send = arr[1];
								do {
									if ((!file_parts.isEmpty()) && (loop_send < file_parts.size())
											&& (arr[2] > loop_send)) {
										UDP_Send(socket, destination_address, Port, loop_send, file_parts, true);

									}
									loop_send++;
								} while (arr[2] > loop_send);
							} else {
								if (quiet == false)
									System.out.println("Invalid");
							}

							if (file_parts.size() != 0) {
								if (file_parts.size() < arr[2]) {
									arr[2] = file_parts.size();
								}
								if (arr[2] != -1)
									arr[1] = arr[2];
							}

							// check to see the loop end
							start_receiving = true;
							for (Window item : file_parts) {
								if (item.isAck() == false) {
									start_receiving = false;
								}
							}

						} catch (IOException | ClassNotFoundException e) {
							e.printStackTrace();
						}

					}

				}
			};
			Thread rev = new Thread(Rev_Ack);
			rev.start();
		} catch (

		IOException e) {
			e.printStackTrace();
		}
	}

	/*
	 * Method to create the packets and send over the network using UDP. Prints
	 * the sequence number of the packet sent.
	 */
	public static void UDP_Send(DatagramSocket soc, InetAddress dest, int port, int index, List<Window> list_pac,
			Boolean sent) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ObjectOutputStream os;
		list_pac.get(index).setTime_stamp(System.currentTimeMillis());
		Packets pac = file_parts.get(index).getPacket();
		pac.setDestination_port(port);
		pac.setSource_port(port);
		Calculate_checksum c = new Calculate_checksum();
		Long s = c.checksum(true, pac.getData(), pac.getData().length, null);
		pac.setCheck_sum(s);
		if (quiet == false) {
			if (sent)
				System.out.println("Sent, Sequence no: " + pac.getSequence_number());
			else
				System.out.println("Resent, Sequence no: " + pac.getSequence_number());
		}
		try {
			os = new ObjectOutputStream(outputStream);
			os.writeObject(pac);
			byte[] data = outputStream.toByteArray();
			DatagramPacket sendPacket = new DatagramPacket(data, data.length, dest, port);
			soc.send(sendPacket);
			os.close();
			outputStream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}
}

/*
 * Check sum class to calculate the check sum of the file and data.
 */
class Calculate_checksum {
	public Long checksum(Boolean data, byte[] b, int length, String file) throws IOException {
		if (data) {
			Checksum checksum = new CRC32();
			checksum.update(b, 0, length);
			return checksum.getValue();
		} else {
			CRC32 crc32 = new CRC32();
			int count;
			InputStream in = new BufferedInputStream(new FileInputStream(file));
			while ((count = in.read()) != -1) {
				crc32.update(count);
			}
			in.close();
			return crc32.getValue();
		}
	}
}

/*
 * Packets class to hold all the attributes of the packet. Packets are sent over
 * the network.
 * 
 */
class Packets implements Serializable {
	private static final long serialVersionUID = 123456789L;
	private int Sequence_number;
	private boolean end_of_file = false;
	private int source_port;
	private int destination_port;
	private Long check_sum;
	private int ACK;

	public int getACK() {
		return ACK;
	}

	public void setACK(int aCK) {
		this.ACK = aCK;
	}

	public int getSource_port() {
		return source_port;
	}

	public void setSource_port(int source_port) {
		this.source_port = source_port;
	}

	public int getDestination_port() {
		return destination_port;
	}

	public void setDestination_port(int destination_port) {
		this.destination_port = destination_port;
	}

	public Long getCheck_sum() {
		return check_sum;
	}

	public void setCheck_sum(Long check_sum) {
		this.check_sum = check_sum;
	}

	public boolean isEnd_of_file() {
		return end_of_file;
	}

	public void setEnd_of_file(boolean end_of_file) {
		this.end_of_file = end_of_file;
	}

	public int getSequence_number() {
		return Sequence_number;
	}

	public void setSequence_number(int sequence_number) {
		this.Sequence_number = sequence_number;
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	private byte[] data = new byte[1440];
}

/*
 * Window class to maintain window in TCP
 */

class Window {
	private long time_stamp;
	private boolean acknowledge = false;
	private int duplicate_ack = 0;
	private int window_size = 0;

	public int getWindow_size() {
		return window_size;
	}

	public void setWindow_size(int window_size) {
		this.window_size = window_size;
	}

	public long getTime_stamp() {
		return time_stamp;
	}

	public void setTime_stamp(long time_stamp) {
		this.time_stamp = time_stamp;
	}

	public boolean isAck() {
		return acknowledge;
	}

	public void setAck(boolean ack) {
		this.acknowledge = ack;
	}

	public int getDupack() {
		return duplicate_ack;
	}

	public void setDupack(int dupack) {
		this.duplicate_ack = dupack;
	}

	public Packets getPacket() {
		return packet;
	}

	public void setPacket(Packets packet) {
		this.packet = packet;
	}

	public void increment_dup() {
		this.duplicate_ack++;
	}

	private Packets packet;
}
