import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    private static final int MAX_TIMEOUTS = 3;

    private DatagramSocket dataSocket;   // sends data to receiver
    private DatagramSocket ackSocket;    // listens for ACKs from receiver
    private InetAddress receiverIP;
    private int receiverPort;
    private int timeout;
    private int windowSize;
    private int base;
    private int nextSeq;
    private int timeoutCount;
    private List<DSPacket> packetList;

    private long startTime;
    private boolean isGBN;

    /**
     * Constructor — sets up sockets and config.
     * windowSize = -1 signals Stop-and-Wait mode.
     */
    public Sender(String rcvIP, int rcvDataPort, int senderAckPort, int timeoutMs, int windowSize) throws Exception {
        this.receiverIP   = InetAddress.getByName(rcvIP);
        this.receiverPort = rcvDataPort;
        this.timeout      = timeoutMs;
        this.windowSize   = windowSize;
        this.isGBN        = (windowSize > 0);

        this.base         = 1;   // first DATA seq
        this.nextSeq      = 1;
        this.timeoutCount = 0;

        // Socket for sending data out (any available local port)
        this.dataSocket = new DatagramSocket();

        // Socket for receiving ACKs, bound to senderAckPort
        this.ackSocket = new DatagramSocket(senderAckPort);
        this.ackSocket.setSoTimeout(timeoutMs);
    }

    /**
     * Reads the input file into a list of DSPacket DATA packets.
     * Sequence numbers start at 1 and wrap mod 128.
     */
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

    /**
     * Phase 1: Handshake
     * Sender sends SOT (Type=0, Seq=0), waits for ACK 0 from receiver.
     */
    private void performHandshake() throws IOException {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);

        boolean ackReceived = false;
        int attempts = 0;

        while (!ackReceived && attempts < MAX_TIMEOUTS) {
            sendPacket(sot);
            System.out.println("[SENDER] SOT sent (Seq=0)");

            try {
                DSPacket ack = waitForACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[SENDER] Handshake complete — ACK 0 received");
                    ackReceived = true;
                }
            } catch (SocketTimeoutException e) {
                attempts++;
                System.out.println("[SENDER] Timeout waiting for SOT ACK, attempt " + attempts);
            }
        }

        if (!ackReceived) {
            System.out.println("Unable to transfer file.");
            close();
            System.exit(1);
        }
    }

    /**
     * Sends a single DSPacket over the data socket to the receiver.
     */
    private void sendPacket(DSPacket packet) throws IOException {
        byte[] data = packet.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, receiverIP, receiverPort);
        dataSocket.send(dp);
    }

    /**
     * GBN: Sends all packets currently in the window [base, base+windowSize).
     * Applies ChaosEngine permutation on every group of 4.
     */
    private void sendWindow() throws IOException {
        // Collect packets from nextSeq up to base + windowSize
        List<DSPacket> toSend = new ArrayList<>();

        int seq = nextSeq;
        while (((seq - base + 128) % 128) < windowSize && (seq - 1 + 128) % 128 < packetList.size()) {
            int idx = seq - 1; // packetList is 0-indexed, seq starts at 1
            if (idx < 0) idx += 128;
            if (idx < packetList.size()) {
                toSend.add(packetList.get(idx));
            }
            seq = (seq + 1) % 128;
        }

        // Apply chaos permutation in groups of 4
        List<DSPacket> ordered = applyPermutation(toSend);

        for (DSPacket pkt : ordered) {
            sendPacket(pkt);
            System.out.println("[SENDER] GBN Sent DATA Seq=" + pkt.getSeqNum());
            nextSeq = (nextSeq + 1) % 128;
        }
    }

    /**
     * GBN Chaos: Permutes every group of 4 packets using ChaosEngine.
     * Groups of < 4 at the end are sent in normal order.
     */
    private List<DSPacket> applyPermutation(List<DSPacket> packets) {
        List<DSPacket> result = new ArrayList<>();
        int i = 0;

        while (i < packets.size()) {
            if (i + 4 <= packets.size()) {
                List<DSPacket> group = packets.subList(i, i + 4);
                result.addAll(ChaosEngine.permutePackets(new ArrayList<>(group)));
                i += 4;
            } else {
                // Fewer than 4 remaining — send in normal order
                result.addAll(packets.subList(i, packets.size()));
                break;
            }
        }

        return result;
    }

    /**
     * Blocks waiting for an ACK packet on the ack socket.
     * Throws SocketTimeoutException if timeout elapses.
     */
    private DSPacket waitForACK() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        ackSocket.receive(dp);
        return new DSPacket(buf);
    }

    /**
     * GBN: Processes a received ACK — advances base if ACK is within window.
     * Resets timeout counter on any forward progress.
     */
    private void handleACK(DSPacket ackPacket) {
        int ackSeq = ackPacket.getSeqNum();

        // Check if this ACK is within our window (cumulative)
        if (isInWindow(ackSeq)) {
            // Advance base past the ACKed packet
            base = (ackSeq + 1) % 128;
            timeoutCount = 0;
            System.out.println("[SENDER] GBN ACK received Seq=" + ackSeq + ", base advanced to " + base);
        } else {
            System.out.println("[SENDER] GBN Duplicate/out-of-window ACK Seq=" + ackSeq + " ignored");
        }
    }

    /**
     * Returns true if seqNum is within the current window [base, base+windowSize).
     * Handles mod-128 wrap-around.
     */
    private boolean isInWindow(int seqNum) {
        return ((seqNum - base + 128) % 128) < windowSize;
    }

    /**
     * On timeout: increments counter and retransmits entire window from base (GBN)
     * or retransmits the single current packet (S&W).
     */
    private void handleTimeout() throws IOException {
        timeoutCount++;
        System.out.println("[SENDER] Timeout #" + timeoutCount + " for base Seq=" + base);

        if (isCriticalFailure()) {
            System.out.println("Unable to transfer file.");
            close();
            System.exit(1);
        }

        if (isGBN) {
            // Retransmit entire window from base
            nextSeq = base;
            System.out.println("[SENDER] GBN Retransmitting window from Seq=" + base);
            sendWindow();
        }
        // For S&W, the calling loop handles retransmission directly
    }

    /**
     * Returns true if 3 consecutive timeouts have occurred without window progress.
     */
    private boolean isCriticalFailure() {
        return timeoutCount >= MAX_TIMEOUTS;
    }

    /**
     * Main transfer logic — routes to S&W or GBN based on configuration.
     */
    private void transferFile(String inputFile) throws IOException {
        packetList = readFileIntoPackets(inputFile);
        System.out.println("[SENDER] File read: " + packetList.size() + " DATA packets");

        if (isGBN) {
            transferGBN();
        } else {
            transferStopAndWait();
        }
    }

    /**
     * Stop-and-Wait transfer loop.
     * Sends one packet at a time, waits for its ACK, retransmits on timeout.
     * Fails after 3 consecutive timeouts on the same packet.
     */
    private void transferStopAndWait() throws IOException {
        // Handle empty file
        if (packetList.isEmpty()) {
            System.out.println("[SENDER] S&W Empty file — skipping data transfer");
            return;
        }

        int seq = 1;

        for (int i = 0; i < packetList.size(); i++) {
            DSPacket pkt = packetList.get(i);
            timeoutCount = 0;
            boolean acked = false;

            while (!acked) {
                sendPacket(pkt);
                System.out.println("[SENDER] S&W Sent DATA Seq=" + pkt.getSeqNum());

                try {
                    DSPacket ack = waitForACK();

                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == pkt.getSeqNum()) {
                        System.out.println("[SENDER] S&W ACK received Seq=" + ack.getSeqNum());
                        timeoutCount = 0;
                        acked = true;
                    } else {
                        System.out.println("[SENDER] S&W Wrong ACK Seq=" + ack.getSeqNum() + ", expected " + pkt.getSeqNum());
                    }

                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[SENDER] S&W Timeout #" + timeoutCount + " for Seq=" + pkt.getSeqNum());

                    if (isCriticalFailure()) {
                        System.out.println("Unable to transfer file.");
                        close();
                        System.exit(1);
                    }
                    // Loop back and retransmit
                }
            }
        }
    }

    /**
     * Go-Back-N transfer loop.
     * Sends a full window, handles cumulative ACKs, retransmits window on timeout.
     */
    private void transferGBN() throws IOException {
        // Handle empty file
        if (packetList.isEmpty()) {
            System.out.println("[SENDER] GBN Empty file — skipping data transfer");
            return;
        }

        base    = 1;
        nextSeq = 1;
        int totalPackets = packetList.size();

        // Keep going until base has moved past the last packet
        while (base <= totalPackets) {
            // Send new packets that fit in window
            while (((nextSeq - base + 128) % 128) < windowSize && (nextSeq - 1) < totalPackets) {
                int idx = nextSeq - 1;
                DSPacket pkt = packetList.get(idx);

                // Apply chaos permutation in groups of 4
                // We send individually here; permutation handled in batch below
                sendPacket(pkt);
                System.out.println("[SENDER] GBN Sent DATA Seq=" + pkt.getSeqNum());
                nextSeq = (nextSeq + 1) % 128;
                if (nextSeq == 0) nextSeq = 128 % 128; // keep 1-based for indexing clarity
            }

            // Wait for ACKs
            try {
                DSPacket ack = waitForACK();

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    System.out.println("[SENDER] GBN ACK received Seq=" + ackSeq);

                    // Advance base to ackSeq + 1 (cumulative)
                    int newBase = (ackSeq + 1) % 128;
                    if (newBase == 0) newBase = 128;

                    if (newBase > base || (base > 100 && newBase < 10)) {
                        // Forward progress
                        base = newBase;
                        timeoutCount = 0;
                        System.out.println("[SENDER] GBN base advanced to " + base);
                    }
                }

            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[SENDER] GBN Timeout #" + timeoutCount + " for base Seq=" + base);

                if (isCriticalFailure()) {
                    System.out.println("Unable to transfer file.");
                    close();
                    System.exit(1);
                }

                // Retransmit from base
                System.out.println("[SENDER] GBN Retransmitting from base Seq=" + base);
                nextSeq = base;
            }
        }
    }

    /**
     * Phase 3: Teardown
     * Sends EOT, waits for ACK of EOT, prints total transmission time.
     */
    private void performTeardown(int eotSeq) throws IOException {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        boolean acked = false;
        int attempts = 0;

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
        System.out.printf("Total Transmission Time: %.2f seconds%n", totalSeconds);

        if (!acked) {
            System.out.println("[SENDER] Warning: EOT ACK never received");
        }
    }

    /**
     * Closes both sockets gracefully.
     */
    private void close() {
        if (dataSocket != null && !dataSocket.isClosed()) dataSocket.close();
        if (ackSocket  != null && !ackSocket.isClosed())  ackSocket.close();
    }

    /**
     * Entry point.
     *
     * Usage:
     *   java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
     *
     * Omit window_size → Stop-and-Wait
     * Provide window_size → Go-Back-N
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIP        = args[0];
        int rcvDataPort     = Integer.parseInt(args[1]);
        int senderAckPort   = Integer.parseInt(args[2]);
        String inputFile    = args[3];
        int timeoutMs       = Integer.parseInt(args[4]);
        int windowSize      = (args.length == 6) ? Integer.parseInt(args[5]) : -1;

        // Validate GBN window size
        if (windowSize > 0 && (windowSize % 4 != 0 || windowSize > 128)) {
            System.err.println("Error: window_size must be a multiple of 4 and <= 128");
            System.exit(1);
        }

        Sender sender = new Sender(rcvIP, rcvDataPort, senderAckPort, timeoutMs, windowSize);

        try {
            // Handshake
            sender.performHandshake();

            // Start timer after handshake
            sender.startTime = System.currentTimeMillis();

            // Transfer
            sender.transferFile(inputFile);

            // Compute EOT sequence number = (last DATA seq + 1) % 128
            int lastDataSeq = sender.packetList.isEmpty() ? 0 : sender.packetList.get(sender.packetList.size() - 1).getSeqNum();
            int eotSeq = (lastDataSeq + 1) % 128;

            // Teardown
            sender.performTeardown(eotSeq);

        } finally {
            sender.close();
        }
    }
}