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
                seq = (seq + 1) % 128;
            }
        }
        return packets;
    }

    private void performHandshake() throws IOException {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int attempts = 0;

        while (attempts < MAX_TIMEOUTS) {
            sendPacket(sot);
            System.out.println("[SENDER] SOT sent (Seq=0)");
            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[SENDER] Handshake complete — ACK 0 received");
                    return;
                }
            } catch (SocketTimeoutException e) {
                attempts++;
                System.out.println("[SENDER] Timeout waiting for SOT ACK, attempt " + attempts);
            }
        }
        System.out.println("Unable to transfer file.");
        close();
        System.exit(1);
    }

    private void sendPacket(DSPacket packet) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, receiverIP, receiverPort);
        dataSocket.send(dp);
    }

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

    private DSPacket waitForACK() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        ackSocket.receive(dp);
        return new DSPacket(buf);
    }

    private void transferStopAndWait() throws IOException {
        if (packetList.isEmpty())
            return;

        for (int i = 0; i < packetList.size(); i++) {
            DSPacket pkt = packetList.get(i);
            int timeoutCount = 0;
            boolean acked = false;

            while (!acked) {
                sendPacket(pkt);
                System.out.println("[SENDER] S&W Sent DATA Seq=" + pkt.getSeqNum());

                try {
                    DSPacket ack = waitForACK();
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == pkt.getSeqNum()) {
                        System.out.println("[SENDER] S&W ACK received Seq=" + ack.getSeqNum());
                        acked = true;
                    } else {
                        System.out.println(
                                "[SENDER] S&W Wrong ACK Seq=" + ack.getSeqNum() + ", expected " + pkt.getSeqNum());
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[SENDER] S&W Timeout #" + timeoutCount + " for Seq=" + pkt.getSeqNum());
                    if (timeoutCount >= MAX_TIMEOUTS) {
                        System.out.println("Unable to transfer file.");
                        close();
                        System.exit(1);
                    }
                }
            }
        }
    }

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
                    System.out.println("[SENDER] GBN Sent DATA Seq=" + pkt.getSeqNum());
                }
            }

            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    System.out.println("[SENDER] GBN ACK received Seq=" + ackSeq);

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
                        System.out.println("[SENDER] GBN base advanced, now expecting string index " + baseIdx);
                    }
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[SENDER] GBN Timeout #" + timeoutCount);
                if (timeoutCount >= MAX_TIMEOUTS) {
                    System.out.println("Unable to transfer file.");
                    close();
                    System.exit(1);
                }

                List<DSPacket> retransmit = new ArrayList<>();
                int limit = Math.min(totalPackets, baseIdx + windowSize);
                for (int i = baseIdx; i < limit; i++) {
                    retransmit.add(packetList.get(i));
                }

                System.out.println(
                        "[SENDER] GBN Retransmitting window from base seq: " + packetList.get(baseIdx).getSeqNum());
                List<DSPacket> ordered = applyPermutation(retransmit);
                for (DSPacket pkt : ordered) {
                    sendPacket(pkt);
                    System.out.println("[SENDER] GBN Re-Sent DATA Seq=" + pkt.getSeqNum());
                }
                nextIdx = limit;
            }
        }
    }

    private void performTeardown(int eotSeq) throws IOException {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int attempts = 0;
        boolean acked = false;

        while (!acked && attempts < MAX_TIMEOUTS) {
            sendPacket(eot);
            System.out.println("[SENDER] EOT sent (Seq=" + eotSeq + ")");
            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    acked = true;
                    System.out.println("[SENDER] EOT ACK received — transfer complete");
                }
            } catch (SocketTimeoutException e) {
                attempts++;
                System.out.println("[SENDER] Timeout waiting for EOT ACK, attempt " + attempts);
            }
        }

        long endTime = System.currentTimeMillis();
        double totalSeconds = (endTime - startTime) / 1000.0;
        System.out.printf("Total Transmission Time: %.2f seconds\n", totalSeconds);

        if (!acked) {
            System.out.println("[SENDER] Warning: EOT ACK never received");
        }
    }

    private void close() {
        if (dataSocket != null && !dataSocket.isClosed())
            dataSocket.close();
        if (ackSocket != null && !ackSocket.isClosed())
            ackSocket.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println(
                    "Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIP = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);
        int windowSize = (args.length == 6) ? Integer.parseInt(args[5]) : -1;

        if (windowSize > 0 && (windowSize % 4 != 0 || windowSize > 128)) {
            System.err.println("Error: window_size must be a multiple of 4 and <= 128");
            System.exit(1);
        }

        Sender sender = new Sender(rcvIP, rcvDataPort, senderAckPort, timeoutMs, windowSize);
        try {
            sender.packetList = sender.readFileIntoPackets(inputFile);
            System.out.println("[SENDER] File read: " + sender.packetList.size() + " DATA packets");

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