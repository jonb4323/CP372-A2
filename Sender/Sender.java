
// imports
import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    private static final int MAX_TIMEOUTS = 3;

    private DatagramSocket dataSocket;
    private DatagramSocket ackSocket;
    private InetAddress receiverIP;
    private int receiverPort;
    private int timeout;
    private int windowSize;

    private List<DSPacket> packetList;
    private long startTime;
    private boolean isGBN;

    // creating sender with receiver (static) address, ports, timeout, and window
    // size
    public Sender(String rcvIP, int rcvDataPort, int senderAckPort, int timeoutMs, int windowSize) throws Exception {
        this.receiverIP = InetAddress.getByName(rcvIP);
        this.receiverPort = rcvDataPort;
        this.timeout = timeoutMs;
        this.windowSize = windowSize;
        this.isGBN = (windowSize > 0);
        this.dataSocket = new DatagramSocket();
        this.ackSocket = new DatagramSocket(senderAckPort);
        this.ackSocket.setSoTimeout(timeoutMs);
    }

    // reading the input file and then splitting it into a list of DATA packets
    private List<DSPacket> readFileIntoPackets(String inputFile) throws IOException {
        List<DSPacket> packets = new ArrayList<>();
        File file = new File(inputFile);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];
            int seq = 1;
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] payload = Arrays.copyOf(buffer, bytesRead);
                packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, payload));
                seq = (seq + 1) % 128; // SN wraps around at 128 (0-127)
            }
        }
        return packets;
    }

    // sending the SOT packet and waiting for ACK to establish connection with
    // receiver
    private void performHandshake() throws IOException {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int attempts = 0;

        while (attempts < MAX_TIMEOUTS) {
            sendPacket(sot);
            System.out.println("[SND] SOT sent (Seq = 0)");
            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[SND] Handshake done, ACK 0 received");
                    return;
                }
            } catch (SocketTimeoutException e) {
                attempts++;
                System.out.println("[SND] Timeout waiting for SOT ACK, attempt " + attempts);
            }
        }
        System.out.println("[NOT ABLE TO SEND FILE]");
        close();
        System.exit(1);
    }

    // sending a single packet to the receiver over UDP
    private void sendPacket(DSPacket packet) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, receiverIP, receiverPort);
        dataSocket.send(dp);
    }

    // reordering packets in groups of 4 using the ChaosEngine to simulate out of
    // order delivery
    private List<DSPacket> applyPermutation(List<DSPacket> packets) {
        List<DSPacket> result = new ArrayList<>();
        int i = 0;
        while (i < packets.size()) {
            if (i + 4 <= packets.size()) {
                List<DSPacket> group = packets.subList(i, i + 4);
                result.addAll(ChaosEngine.permutePackets(new ArrayList<>(group)));
                i += 4;
            } else {
                result.addAll(packets.subList(i, packets.size()));
                break;
            }
        }
        return result;
    }

    // waiting to receive an Acknowledgement packet from the receiver
    private DSPacket waitForACK() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        ackSocket.receive(dp);
        return new DSPacket(buf);
    }

    // transferring all packets using Stop-and-Wait protocol (one packet at a time)
    private void transferStopAndWait() throws IOException {
        if (packetList.isEmpty())
            return;

        for (int i = 0; i < packetList.size(); i++) {
            DSPacket pkt = packetList.get(i);
            int timeoutCount = 0;
            boolean acked = false;

            while (!acked) {
                sendPacket(pkt);
                System.out.println("[SND] Stop-and-Wait Sent DATA Seq = " + pkt.getSeqNum());

                try {
                    DSPacket ack = waitForACK();
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == pkt.getSeqNum()) {
                        System.out.println("[SND] Stop-and-Wait ACK received Seq = " + ack.getSeqNum());
                        acked = true;
                    } else {
                        System.out.println(
                                "[SND] Stop-and-Wait Wrong ACK Seq = " + ack.getSeqNum() + ", expected "
                                        + pkt.getSeqNum());
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out
                            .println("[SND] Stop-and-Wait Timeout Number: " + timeoutCount + " for Seq = "
                                    + pkt.getSeqNum());
                    if (timeoutCount >= MAX_TIMEOUTS) {
                        System.out.println("Unable to transfer file.");
                        close();
                        System.exit(1);
                    }
                }
            }
        }
    }

    // transferring all packets using Go-Back-N protocol with a sliding window
    private void transferGBN() throws IOException {
        if (packetList.isEmpty())
            return;

        int baseIdx = 0;
        int nextIdx = 0;
        int totalPackets = packetList.size();
        int timeoutCount = 0;

        while (baseIdx < totalPackets) {
            List<DSPacket> toSend = new ArrayList<>();
            while (nextIdx - baseIdx < windowSize && nextIdx < totalPackets) {
                toSend.add(packetList.get(nextIdx));
                nextIdx++;
            }

            if (!toSend.isEmpty()) {
                List<DSPacket> ordered = applyPermutation(toSend);
                for (DSPacket pkt : ordered) {
                    sendPacket(pkt);
                    System.out.println("[SND] Go-Back-N Sent DATA Seq = " + pkt.getSeqNum());
                }
            }

            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    System.out.println("[SND] Go-Back-N ACK received Seq = " + ackSeq);

                    int advancedTo = -1;
                    for (int i = baseIdx; i < nextIdx; i++) {
                        if (packetList.get(i).getSeqNum() == ackSeq) {
                            advancedTo = i + 1;
                            break;
                        }
                    }

                    if (advancedTo > baseIdx) {
                        baseIdx = advancedTo;
                        timeoutCount = 0;
                        System.out.println("[SND] Go-Back-N base advanced, now expecting string index " + baseIdx);
                    }
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[SND] Go-Back-N Timeout Number: " + timeoutCount);
                if (timeoutCount >= MAX_TIMEOUTS) {
                    System.out.println("[NOT ABLE TO SEND FILE]");
                    close();
                    System.exit(1);
                }

                List<DSPacket> retransmit = new ArrayList<>();
                int limit = Math.min(totalPackets, baseIdx + windowSize);
                for (int i = baseIdx; i < limit; i++) {
                    retransmit.add(packetList.get(i));
                }

                System.out.println(
                        "[SND] Go-Back-N Retransmitting window from base seq: " + packetList.get(baseIdx).getSeqNum());
                List<DSPacket> ordered = applyPermutation(retransmit);
                for (DSPacket pkt : ordered) {
                    sendPacket(pkt);
                    System.out.println("[SND] Go-Back-N Re-Sent DATA Seq = " + pkt.getSeqNum());
                }
                nextIdx = limit;
            }
        }
    }

    // sends End of Transmission packet then waits for acknowledgement to end the
    // transfer, then prints total time
    private void performTeardown(int eotSeq) throws IOException {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int attempts = 0;
        boolean acked = false;

        while (!acked && attempts < MAX_TIMEOUTS) {
            sendPacket(eot);
            System.out.println("[SND] EOT sent (Seq = " + eotSeq + ")");
            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    acked = true;
                    System.out.println("[SND] EOT ACK received, transfer of file complete");
                }
            } catch (SocketTimeoutException e) {
                attempts++;
                System.out.println("[SND] Timeout waiting for EOT ACK, attempt " + attempts);
            }
        }

        long endTime = System.currentTimeMillis();
        double totalSeconds = (endTime - startTime) / 1000.0;
        System.out.printf("Total Transmission Time: %.2f seconds\n", totalSeconds);

        if (!acked) {
            System.out.println("[SND] ERROR: EOT ACK never received");
        }
    }

    // closing both the data and acknowledgement sockets
    private void close() {
        if (dataSocket != null && !dataSocket.isClosed())
            dataSocket.close();
        if (ackSocket != null && !ackSocket.isClosed())
            ackSocket.close();
    }

    // the entry point that will load arguments and run the sender transfer
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println(
                    "[ERROR] *Must provide 5 arguments: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1); // exit if arguments are incorrect
        }

        String rcvIP = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);
        int windowSize = (args.length == 6) ? Integer.parseInt(args[5]) : -1;

        if (windowSize > 0 && (windowSize % 4 != 0 || windowSize > 128)) {
            System.err.println("[ERROR] window_size must be a multiple of 4 and <= 128");
            System.exit(1);
        }

        Sender sender = new Sender(rcvIP, rcvDataPort, senderAckPort, timeoutMs, windowSize);
        try {
            sender.packetList = sender.readFileIntoPackets(inputFile);
            System.out.println("[SND] File read: " + sender.packetList.size() + " DATA packets");

            sender.performHandshake();
            sender.startTime = System.currentTimeMillis();

            if (sender.isGBN) {
                sender.transferGBN();
            } else {
                sender.transferStopAndWait();
            }

            int lastDataSeq = sender.packetList.isEmpty() ? 0
                    : sender.packetList.get(sender.packetList.size() - 1).getSeqNum();
            int eotSeq = (lastDataSeq + 1) % 128;
            sender.performTeardown(eotSeq);
        } finally {
            sender.close();
        }
    }
}